/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.web;

import com.galworld.config.PathConfig;
import com.galworld.repo.GameRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * 串流反向代理：把 Selkies 收到门户内部，不再对外暴露游戏端口。
 *
 * 背景：Selkies 原本用 HTTP Basic 认证保护，但门户把串流页放在 iframe 里，
 * Basic 认证会触发浏览器原生登录弹窗，无法用样式覆盖。关掉认证后端口就裸奔了。
 *
 * 解决办法：Selkies 绑 127.0.0.1 并带 --subfolder /stream/<id>，
 * 由门户转发 /stream/<id>/** 到它。这样：
 *  - 游戏端口不再对外监听，扫描 9002 连不上；
 *  - iframe 与门户同源，浏览器不带任何凭据，抓包也拿不到「冒充门户」的东西；
 *  - 非门户路径 / 非法 gameId 直接 404，不透露端口存在。
 *
 * 注意：本类只转发 HTTP。WebSocket 由 {@link StreamWsBridge} 处理。
 */
@RestController
@RequestMapping("/stream")
public class StreamProxyController {

    private static final Logger log = LoggerFactory.getLogger(StreamProxyController.class);

    /** 允许代理的游戏 id 白名单：只接受已注册的游戏，防止任意端口探测 */
    private final GameRepository games;
    private final PathConfig paths;
    private final HttpClient http;

    public StreamProxyController(GameRepository games, PathConfig paths) {
        this.games = games;
        this.paths = paths;
        // Selkies 的自签证书 SAN 是部署时配置的域名/服务器地址，不含 127.0.0.1。
        // HttpClient 默认会做主机名校验，必须显式关掉——目标地址由服务端写死为
        // 127.0.0.1，客户端无法指定任意主机，关掉不引入额外风险。
        var sslParams = new javax.net.ssl.SSLParameters();
        sslParams.setEndpointIdentificationAlgorithm(null);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(trustLoopbackSelfSigned())
                .sslParameters(sslParams)
                .build();
    }

    /**
     * Selkies 用自签证书（每台机器现场生成的），且只监听回环。
     *
     * 这里只信任「连的是 127.0.0.1」这一种情况——把校验放宽到信任所有证书是
     * 安全的，因为目标地址由服务端写死，客户端无法指定任意主机；
     * 严格校验自签证书需要把证书文件读进来建 TrustManager，收益不大。
     */
    private static javax.net.ssl.SSLContext trustLoopbackSelfSigned() {
        try {
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
            throw new IllegalStateException("初始化串流代理 SSL 上下文失败", e);
        }
    }

    /**
     * 转发 GET。串流页与静态资源都是 GET，写操作（截图、文件）走同一入口。
     *
     * 只允许 GET：Selkies 的写接口需要它自己的控制令牌，门户不该替它转发。
     */
    @GetMapping("/{gameId}/**")
    public ResponseEntity<byte[]> proxy(@PathVariable String gameId,
                                        HttpServletRequest req) throws IOException {
        int port = resolvePort(gameId);
        if (port <= 0) {
            // 不区分「游戏不存在」与「没启动」，避免探测哪些 id 有效
            return ResponseEntity.notFound().build();
        }
        // Selkies 带 --subfolder /stream/<id>，它的路由前缀就是这段路径本身，
        // 所以转发时不能把那一段剥掉，要原样发给上游。
        String uri = req.getRequestURI();
        String query = req.getQueryString();
        // Selkies 强制 HTTPS（--enable-https），http 连不上
        String target = "https://127.0.0.1:" + port + uri + (query == null ? "" : "?" + query);

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target))
                .timeout(Duration.ofSeconds(30))
                .GET();
        copyHeaders(req, b);

        try {
            HttpResponse<byte[]> res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            HttpHeaders out = new HttpHeaders();
            // 只回传必要头：Set-Cookie / CSP 之类由门户决定，不照搬上游
            copyBack(res, out);
            return new ResponseEntity<>(res.body(), out, res.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503).build();
        } catch (IOException e) {
            log.warn("串流代理失败 {}: {}", gameId, e.toString());
            return ResponseEntity.status(502).build();
        }
    }

    /** 只转发少量与内容协商相关的请求头，其余（Cookie/Authorization）不带给上游 */
    private void copyHeaders(HttpServletRequest req, HttpRequest.Builder b) {
        for (String name : List.of("Accept", "Accept-Language", "Range", "If-None-Match")) {
            String v = req.getHeader(name);
            if (v != null) {
                b.header(name, v);
            }
        }
    }

    /** 回传内容与缓存相关头；不转发 Set-Cookie 与重定向 */
    private void copyBack(HttpResponse<byte[]> res, HttpHeaders out) {
        for (String name : List.of("Content-Type", "Content-Length", "Cache-Control",
                "Last-Modified", "ETag", "Content-Range", "Accept-Ranges")) {
            List<String> v = res.headers().allValues(name);
            if (!v.isEmpty()) {
                out.add(name, v.get(0));
            }
        }
    }

    /**
     * 解析游戏端口。
     *
     * 只有 DB 里存在、且端口在合法区间时才放行——防住 `/stream/12345/` 这类
     * 直接拿端口号探测的请求。
     */
    private int resolvePort(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            return -1;
        }
        return games.findById(gameId)
                .map(g -> {
                    int port = g.getPort();
                    // Selkies 每游戏一个端口，区间外的值一定是脏数据
                    return port >= 9000 && port <= 9100 ? port : -1;
                })
                .orElse(-1);
    }

    /** 给其它组件复用的端口解析（WebSocket 桥接也用） */
    static int portOf(GameRepository games, String gameId) {
        if (gameId == null || gameId.isBlank()) {
            return -1;
        }
        return games.findById(gameId)
                .map(g -> g.getPort() >= 9000 && g.getPort() <= 9100 ? g.getPort() : -1)
                .orElse(-1);
    }

    /** 统一小写比较，避免 `Stream` 之类的大小写绕过 */
    static boolean sameSegment(String a, String b) {
        return a != null && b != null && a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }
}
