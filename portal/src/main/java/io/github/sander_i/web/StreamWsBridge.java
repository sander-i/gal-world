/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.i18n.I18n;
import io.github.sander_i.repo.GameRepository;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 桥接：浏览器 ← 门户 ← Selkies。
 *
 * Selkies 的画面数据走 WebSocket，门户必须转发，否则 iframe 只看到静态页。
 * 做法：门户作为客户端再连一条到 127.0.0.1:&lt;port&gt;，两条连接双向原样透传。
 *
 * 用 jakarta.websocket 的 @ServerEndpoint 而不是 Spring 的 WebSocketHandler：
 * 后者需要 spring-websocket 依赖，而本机构建是离线的，仓库里没有这个包。
 * Tomcat 自带 jakarta.websocket 实现，够用且不加依赖。
 *
 * 路径必须与 Selkies 自己的路由完全一致：`&lt;prefix&gt;/api/websockets`
 * （见 selkies/websockets_mode.py 的路由注册）。门户只是把它包了一层，
 * 客户端仍然请求 /stream/{gameId}/api/websockets。
 */
@Component
@ServerEndpoint("/stream/{gameId}/api/websockets")
public class StreamWsBridge {

    private static final Logger log = LoggerFactory.getLogger(StreamWsBridge.class);

    /**
     * 静态持有 GameRepository。
     *
     * @ServerEndpoint 的实例由容器为每个连接新建，拿不到 Spring 注入，
     * 只能通过静态字段把依赖传进来。
     */
    private static GameRepository games;

    @Autowired
    public void setGames(GameRepository repo) {
        StreamWsBridge.games = repo;
    }

    /** 遥测：浏览器连接断开时记录原因，用来和崩溃时间对齐 */
    private static io.github.sander_i.service.PlayTelemetryService telemetry;

    @Autowired
    public void setTelemetry(io.github.sander_i.service.PlayTelemetryService service) {
        StreamWsBridge.telemetry = service;
    }

    /**
     * 信任回环自签证书的 SSL 上下文。
     *
     * Selkies 用现场生成的自签证书，且只监听 127.0.0.1；目标地址由服务端写死，
     * 客户端无法指定任意主机，所以放宽校验不引入额外风险。
     */
    private static final javax.net.ssl.SSLContext LOOPBACK_TLS = buildLoopbackTls();

    private static javax.net.ssl.SSLContext buildLoopbackTls() {
        try {
            // 证书 SAN 不含 127.0.0.1；wss 的主机名校验交给下面的
            // SSLParameters 关闭（Jakarta WebSocket 用的是 JDK 的 TLS 实现）
            var tmf = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {
                        }

                        public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {
                        }

                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }
            };
            var ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, tmf, new java.security.SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(I18n.t("初始化串流 WS 的 TLS 上下文失败"), e);
        }
    }

    /** 浏览器会话 → 上游会话 */
    private static final Map<String, Session> UPSTREAM = new ConcurrentHashMap<>();

    /**
     * 浏览器会话 → 发往上���的写队列。
     *
     * 上游 Session 只能由单一线程写：Tomcat 的 RemoteEndpoint 不是线程安全的，
     * 浏览器 @OnMessage 回调线程与补发线程同时写会抛
     * 「The remote endpoint was in state [...]」，对端看到的就是 1006 断开。
     */
    private static final Map<String, FrameQueue> UP_QUEUE =
            new ConcurrentHashMap<>();

    /**
     * 上游 → 浏览器方向的队列预算。
     *
     * 之前这里是**无界** LinkedBlockingQueue：浏览器一旦消化不过来（快速跳过对话
     * 时码率涨到 10–17 Mbps，而浏览器解码 + 渲染跟不上），队列每秒涨 1 MB 左右，
     * 延迟无限累积。更糟的是 Selkies 只看到「本地 socket 一直被读空」，它自己的
     * 字节预算丢帧（VIDEO_RELAY_BUDGET_SECONDS = 2.0）**永远不会触发**，门户成了
     * 一个静默的黑洞缓冲：跳过 10 秒就积压 10 秒，松手后画面要追赶很久才回到实时。
     *
     * 现在按 Selkies 同样的思路设预算：超过就把最旧的视频帧丢掉。丢帧会让客户端的
     * 解码器出现引用缺口，它自己会发 `LOST_FRAME <id>` / `REQUEST_KEYFRAME`
     * （见 selkies-core.js 与 websockets_mode.py 的对应分支），Selkies 随即
     * invalidation 或补一个 IDR，约 1 秒内自愈。代价是一次短暂跳帧，
     * 换来的是延迟恒定 —— 这是串流里正确的取舍方向。
     */
    private static final int DOWN_MAX_FRAMES = 180;            // 60fps 下约 3 秒
    private static final long DOWN_MAX_VIDEO_BYTES = 4L << 20;  // 与 Selkies 的最小预算一致

    /** 浏览器 → 上游方向：只有控制帧，给个上限防止上游卡死时无限涨 */
    private static final int UP_MAX_FRAMES = 512;

    /**
     * 一条桥接连接的单向帧队列。
     *
     * 两个方向都用它，但策略不同：
     *  - 下游（Selkies → 浏览器）超预算时丢**最旧的视频帧**。视频帧有引用关系，
     *    丢一帧会让客户端解码器出现缺口，它会自己报 LOST_FRAME / REQUEST_KEYFRAME
     *    （selkies-core.js 的 decode gate，服务端见 websockets_mode.py 的
     *    `LOST_FRAME ` / `REQUEST_KEYFRAME` 分支），约 1 秒自愈。
     *  - 上游（浏览器 → Selkies）超预算时只丢最旧的二进制帧（麦克风 PCM），
     *    文本控制帧（SETTINGS / ACK / 键鼠）一律不丢：丢了就是状态不同步。
     */
    /** 包内可见，供 StreamWsBridgeQueueTest 直接验证丢帧策略 */
    static final class FrameQueue {
        private final java.util.concurrent.BlockingQueue<Object[]> q =
                new java.util.concurrent.LinkedBlockingQueue<>();
        private final boolean downstream;
        private final int maxFrames;
        private final long maxVideoBytes;
        /** 已入队但未被写线程取走的字节数（下游预算用） */
        private long bytes;
        /** 因超预算被丢掉的帧数，用于日志与遥测 */
        private long dropped;

        FrameQueue(boolean downstream) {
            this.downstream = downstream;
            this.maxFrames = downstream ? DOWN_MAX_FRAMES : UP_MAX_FRAMES;
            this.maxVideoBytes = downstream ? DOWN_MAX_VIDEO_BYTES : 0;
        }

        /** 入队；超预算时按上面的策略丢帧。永不阻塞。 */
        synchronized void put(Object[] f) {
            if (downstream) {
                int size = f[1] == null ? 0 : ((ByteBuffer) f[1]).remaining();
                // 超预算时只挤视频帧：控制帧（SETTINGS / AUDIO 开关 / 剪贴板）
                // 丢一条就是状态不同步，比丢帧本身更糟。
                while (!q.isEmpty()
                        && (q.size() >= maxFrames
                            || (isVideo(f) && bytes + size > maxVideoBytes))) {
                    Object[] victim = oldestVideo();
                    if (victim == null) {
                        // 队列里全是控制帧：说明上游在灌控制消息而不是画面，
                        // 这种情况不该丢，直接入队（超上限也不会有几个）。
                        break;
                    }
                    if (!q.remove(victim)) {
                        break;
                    }
                    bytes -= victim[1] == null ? 0 : ((ByteBuffer) victim[1]).remaining();
                    dropped++;
                }
                q.offer(f);
                bytes += size;
            } else {
                // 上游方向：只丢最旧的二进制帧（麦克风 PCM），文本控制帧绝不丢 ——
                // 丢了 SETTINGS / ACK / 键鼠就是状态不同步。全是控制帧还满，说明
                // 上游已经卡死，此时超一点上限也无所谓，比丢状态安全。
                if (q.size() >= maxFrames) {
                    Object[] victim = null;
                    for (Object[] cand : q) {
                        if (cand[0] == null) {
                            victim = cand;
                            break;
                        }
                    }
                    if (victim != null && q.remove(victim)) {
                        dropped++;
                    }
                }
                q.offer(f);
            }
        }

        /** 取一帧，超时返回 null。取出时同步扣减字节计数。 */
        Object[] poll(long timeoutMs) throws InterruptedException {
            Object[] f = q.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (f != null && downstream) {
                synchronized (this) {
                    bytes -= f[1] == null ? 0 : ((ByteBuffer) f[1]).remaining();
                }
            }
            return f;
        }

        synchronized long dropped() {
            return dropped;
        }

        synchronized int size() {
            return q.size();
        }

        /** 队列里最旧的一条视频帧；没有则 null。调用方持有锁。 */
        private Object[] oldestVideo() {
            for (Object[] cand : q) {
                if (isVideo(cand)) {
                    return cand;
                }
            }
            return null;
        }

        /** Selkies 的视频分片：首字节 0x04，长度至少 12（帧号/参考帧在头里） */
        static boolean isVideo(Object[] f) {
            if (f[1] == null) {
                return false;
            }
            ByteBuffer b = (ByteBuffer) f[1];
            return b.remaining() >= 12 && b.get(b.position()) == 0x04;
        }
    }


    /**
     * 单条 WS 消息的缓冲上限。
     *
     * 这个值是**单帧**上限，不是队列预算（队列另见 DOWN_MAX_VIDEO_BYTES）。
     * 1080p + CRF 8 + 4:4:4 的一帧远超 1 MB：Selkies 在 1920×1080 下实测会被
     * 门户以 `code 1009 Message Too Big` 断开，随后拆除采集管线，重连再断，
     * 表现为「流传输卡住」。
     *
     * 取 Selkies 自己的上限（`settings.py` 的 `WS_MAX_MESSAGE_BYTES` =
     * min(8 MB, 32 MB)）。两边必须对齐：门户低于上游就会单方面断链。
     * 浏览器侧同理——浏览器解码后的帧也是原样透传，同样按这个值放宽。
     */
    private static final int WS_MAX_MESSAGE_BYTES = 8 << 20;

    public StreamWsBridge() {
    }

    @OnOpen
    public void onOpen(Session browser) {
        // 浏览器→上游方向也会传较大的控制帧（编码参数、剪贴板），
        // Tomcat 服务端默认缓冲同样是 8KB，一并放大。
        browser.setMaxTextMessageBufferSize(WS_MAX_MESSAGE_BYTES);
        browser.setMaxBinaryMessageBufferSize(WS_MAX_MESSAGE_BYTES);
        String gameId = gameIdOf(browser);
        int port = portOf(gameId);
        if (port <= 0) {
            closeQuietly(browser);
            return;
        }
        recordTelemetry(gameId, "ws_open", java.util.Map.of("session", String.valueOf(browser.getId())));
        // 必须在独立线程里连上游：connectToServer 是阻塞调用，
        // 在 Tomcat 的 WS 回调线程里做会拖住握手完成，Selkies 首帧到达时
        // 浏览器侧还没进入可写状态，表现为「连上就断」。
        Thread t = new Thread(() -> connectUpstream(browser, gameId, port),
                "stream-ws-" + browser.getId());
        t.setDaemon(true);
        t.start();
    }

    private void connectUpstream(Session browser, String gameId, int port) {
        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            // 容器是全局单例，多个连接共用；但客户端 Session 由容器管理，
            // 连接建立后必须保持引用，否则可能被回收。见 UPSTREAM 持有。
            // 查询串必须原样带到上游：Selkies 靠 ?role=viewer 区分观众与控制器，
            // 缺了它会把连接当成 controller，从而不下发画面。
            //
            // 不能用 getRequestParameterMap() 重建——Tomcat 会把 {gameId}
            // 路径变量也塞进这个 map，拼出来的串是 "?gameId=sample-game"，
            // 真正的 role 反而丢了。只认 getQueryString()。
            String qs = browser.getQueryString();
            // 不强制 role=viewer：Selkies 默认角色就是 controller，
            // 而 viewer 会 detach 输入上下文——鼠标能动但点不动游戏，
            // 且 detach_context() 把光标设成 none。
            //
            // 早期曾用 viewer 让画面出来，那其实是 Tomcat 8KB 消息缓冲
            // 导致的 TOO_BIG 断开被误判成 role 问题；缓冲修好后
            // controller 同样能拿到画面，且输入可用。
            //
            // 浏览器端本来就是门户的登录用户，给 controller 权限是对的。
            if (qs != null && qs.contains("role=viewer")) {
                qs = qs.replaceAll("(^|&)role=viewer(&|$)", "$1").replaceAll("&$", "");
            }
            log.debug("串流 WS 请求: uri={} 上游 query={}", browser.getRequestURI(), qs);
            // Selkies 强制 HTTPS/WSS（--enable-https），ws:// 连不上
            URI target = URI.create("wss://127.0.0.1:" + port
                    + "/stream/" + gameId + "/api/websockets"
                    + (qs == null || qs.isEmpty() ? "" : "?" + qs));
            // Tomcat 的 wss 客户端默认不做主机名校验（只校验证书链），
            // 证书链已由 LOOPBACK_TLS 放宽，回环地址可直连。
            ClientEndpointConfig cfg = ClientEndpointConfig.Builder.create()
                    .sslContext(LOOPBACK_TLS)
                    .preferredSubprotocols(List.of("selkies"))
                    .build();
            Session up = container.connectToServer(new UpstreamEndpoint(browser), cfg, target);
            // Tomcat 默认文本缓冲 8KB，Selkies 首帧（显示器配置 + 编码参数）远超，
            // 超限时容器直接以 1009 TOO_BIG 关闭连接。视频帧同理，见
            // WS_MAX_MESSAGE_BYTES 的说明。
            up.setMaxTextMessageBufferSize(WS_MAX_MESSAGE_BYTES);
            up.setMaxBinaryMessageBufferSize(WS_MAX_MESSAGE_BYTES);
            if (!browser.isOpen()) {
                // 浏览器在连上游期间断开了，别把上游留着
                closeQuietly(up);
                return;
            }
            UPSTREAM.put(browser.getId(), up);
            // 先起单写线程，再把「上游建立前攒下的帧」灌进队列——
            // 顺序反了会让 flushPending 和写线程同时碰 remote。
            startUpWriter(browser.getId(), up);
            flushPending(browser.getId());
            log.debug("串流 WS 已桥接 {} -> {}", browser.getId(), target);
        } catch (java.net.ConnectException e) {
            // 端口没监听 = 会话已停。游玩页在会话停止后仍会重连 WS，
            // 按 WARN 记会刷屏（与 StreamProxyController 同一问题）。
            log.debug("串流 WS 端口未监听 {}: {}", gameId, e.toString());
            closeQuietly(browser);
        } catch (Exception e) {
            log.warn("串流 WS 连接失败 {}: {}", gameId, e.toString());
            closeQuietly(browser);
        }
    }

    @OnMessage
    public void onText(String text, Session browser) {
        send(browser, text, null);
    }

    @OnMessage
    public void onBinary(ByteBuffer data, Session browser) {
        send(browser, null, data);
    }

    @OnClose
    public void onClose(Session browser, jakarta.websocket.CloseReason reason) {
        Session up = UPSTREAM.remove(browser.getId());
        closeQuietly(up);
        int code = reason == null || reason.getCloseCode() == null
                ? 0 : reason.getCloseCode().getCode();
        recordTelemetry(gameIdOf(browser),
                code == 1000 || code == 1001 ? "ws_close" : "ws_close_abnormal",
                java.util.Map.of("session", String.valueOf(browser.getId()),
                        "code", code,
                        "reason", reason == null ? "" : String.valueOf(reason.getReasonPhrase())));
    }

    @OnError
    public void onError(Session browser, Throwable t) {
        log.warn("串流 WS 错误: {}", t.toString());
        closeQuietly(UPSTREAM.remove(browser.getId()));
        closeQuietly(browser);
        recordTelemetry(gameIdOf(browser), "ws_error",
                java.util.Map.of("session", String.valueOf(browser.getId()),
                        "message", String.valueOf(t)));
    }

    private static void recordTelemetry(String gameId, String event,
                                        java.util.Map<String, Object> extra) {
        var t = telemetry;
        if (t == null || gameId == null || gameId.isBlank()) {
            return;
        }
        try {
            t.recordServer(gameId, event, extra);
        } catch (RuntimeException e) {
            log.debug("记录串流遥测失败: {}", e.toString());
        }
    }

    private void send(Session browser, String text, ByteBuffer data) {
        Session up = UPSTREAM.get(browser.getId());
        if (up == null || !up.isOpen()) {
            // 上游还没连好（onOpen 里是异步建的），先把帧排队。
            // 浏览器建立连接后立刻会发初始配置，丢掉这些帧对方就不知道
            // 客户端在看什么，Selkies 会判定「无显示客户端」并停掉采集。
            PENDING.computeIfAbsent(browser.getId(), k -> new ArrayDeque<>())
                    .add(new Object[]{text, data == null ? null : data.duplicate()});
            return;
        }
        // 不在这里直接写上游：交给单写线程，避免与补发线程并发
        var q = UP_QUEUE.get(browser.getId());
        if (q == null) {
            closeQuietly(browser);
            return;
        }
        q.put(new Object[]{text, data == null ? null : data.duplicate()});
    }

    /** 上游单写线程：从队列取帧按序写，是唯一碰 up.remote 的线程 */
    private static void startUpWriter(String browserId, Session up) {
        var q = new FrameQueue(false);
        UP_QUEUE.put(browserId, q);
        Thread w = new Thread(() -> {
            try {
                // 用显式 running 标志而不是 up.isOpen()：
                // q.take() 会阻塞，上游在阻塞期间关闭时循环不会退出，
                // 下一次 send 抛异常就会以未捕获异常结束线程。
                while (true) {
                    Object[] f = q.poll(200);
                    if (f == null) {
                        if (!up.isOpen()) {
                            return;
                        }
                        continue;
                    }
                    try {
                        if (f[0] != null) {
                            up.getBasicRemote().sendText((String) f[0]);
                        } else {
                            up.getBasicRemote().sendBinary((ByteBuffer) f[1]);
                        }
                    } catch (Exception e) {
                        // 上游已关：静默退出，不算错误
                        log.debug("转发到上游失败: {}", e.toString());
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                UP_QUEUE.remove(browserId);
            }
        }, "stream-ws-in-" + browserId);
        w.setDaemon(true);
        w.start();
    }

    /** 上游连上前，浏览器发来的帧先存着，连上后按序补发 */
    private static final Map<String, ArrayDeque<Object[]>> PENDING = new ConcurrentHashMap<>();

    /** 把上游建立前攒下的帧灌进写队列（不直接写 remote） */
    private static void flushPending(String browserId) {
        ArrayDeque<Object[]> q = PENDING.remove(browserId);
        if (q == null) {
            return;
        }
        var writer = UP_QUEUE.get(browserId);
        if (writer == null) {
            return;
        }
        while (!q.isEmpty()) {
            writer.put(q.poll());
        }
    }

    private static String gameIdOf(Session s) {
        Map<String, String> vars = s.getPathParameters();
        String id = vars == null ? null : vars.get("gameId");
        return id;
    }

    /** 只允许已注册游戏，且端口在 Selkies 区间内，防任意端口探测 */
    private static int portOf(String gameId) {
        if (gameId == null || gameId.isBlank() || games == null) {
            return -1;
        }
        return games.findById(gameId)
                .map(g -> g.getPort() >= 9000 && g.getPort() <= 9100 ? g.getPort() : -1)
                .orElse(-1);
    }

    private static void closeQuietly(Session s) {
        if (s == null) {
            return;
        }
        try {
            if (s.isOpen()) {
                s.close();
            }
        } catch (IOException ignored) {
            // 已经关了
        }
    }

    /** 上游收到的每一帧原样回灌给浏览器 */
    private static final class UpstreamEndpoint extends Endpoint {
        private final Session browser;
        /**
         * 待写队列：Selkies 推帧很快，多线程直接写会撞上 TEXT_FULL_WRITING。
         *
         * 这是**有界**的（见 {@link FrameQueue}）：浏览器消化不过来时丢最旧的
         * 视频帧，而不是无限积压把延迟越拖越长。
         */
        private final FrameQueue queue = new FrameQueue(true);
        private final Thread writer;

        UpstreamEndpoint(Session browser) {
            this.browser = browser;
            // 单线程按序写，天然串行；BasicRemote 的同步语义在单线程里是安全的
            this.writer = new Thread(this::drainLoop, "stream-ws-out-" + browser.getId());
            this.writer.setDaemon(true);
            this.writer.start();
        }

        @Override
        public void onOpen(Session up, EndpointConfig config) {
            log.debug("上游 WS onOpen: id={} subprotocol={}", up.getId(), up.getNegotiatedSubprotocol());
            up.addMessageHandler(String.class, text -> queue.put(new Object[]{text, null}));
            up.addMessageHandler(ByteBuffer.class, data -> queue.put(new Object[]{null, data.duplicate()}));
        }

        /** 只有这个线程碰 browser 的 remote，避免并发写 */
        private void drainLoop() {
            long lastReport = System.currentTimeMillis();
            try {
                while (true) {
                    // 同上游写线程：poll 加超时，阻塞期间浏览器关闭时能退出
                    Object[] f = queue.poll(200);
                    if (f == null) {
                        if (!browser.isOpen()) {
                            return;
                        }
                        continue;
                    }
                    if (!browser.isOpen()) {
                        return;
                    }
                    try {
                        if (f[0] != null) {
                            browser.getBasicRemote().sendText((String) f[0]);
                        } else {
                            browser.getBasicRemote().sendBinary((ByteBuffer) f[1]);
                        }
                    } catch (Exception e) {
                        // 浏览器已断开：正常结束，不当作错误
                        log.debug("回灌浏览器失败: {}", e.toString());
                        return;
                    }
                    // 积压丢帧是正常行为，但要能看见：每 10 秒最多一条，避免刷日志
                    long now = System.currentTimeMillis();
                    if (now - lastReport >= 10_000) {
                        lastReport = now;
                        long dropped = queue.dropped();
                        if (dropped > 0) {
                            log.debug("串流桥接积压丢帧 {} 帧（队列 {}，浏览器 {}）",
                                    dropped, queue.size(), browser.getId());
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * 上游关闭时不要立即关浏览器。
         *
         * Selkies 在「等待客户端设置」阶段可能短暂断开再重连，
         * 立刻关浏览器会让前端停在 "WebSocket disconnected. Attempting to reconnect"。
         * 浏览器自己的 @OnClose 会清理上游，这里只记录。
         */
        @Override
        public void onClose(Session up, jakarta.websocket.CloseReason reason) {
            log.debug("上游 WS 关闭: code={} reason={}", reason.getCloseCode(), reason.getReasonPhrase());
            writer.interrupt();
        }

        @Override
        public void onError(Session up, Throwable t) {
            log.warn("上游 WS 错误: {}", t.toString());
            closeQuietly(browser);
        }
    }
}
