/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从地址下载市面语言包（「语言包市场」）。
 *
 * <p>市场里的语言包形态是「一个直链」：Crowdin / Weblate / Transifex 的导出链接、
 * GitHub raw、Gitee raw、自建仓库的静态文件。下载后交给 {@link LocaleImporter}
 * 按扩展名识别格式，和手工上传走同一条导入链路。
 *
 * <p>这是个服务端发起任意请求的接口，必须当作 SSRF 面来防：
 * <ul>
 *   <li>只允许 http / https</li>
 *   <li>解析出的每个地址都要求是公网地址，回环 / 私网 / 链路本地 / CGNAT / 保留段一律拒绝</li>
 *   <li>重定向自己跟（{@code Redirect.NEVER}），每一跳都重新做上面的检查——
 *       否则公网地址 302 到 {@code 127.0.0.1} 就绕过了第一跳的校验</li>
 *   <li>体积上限与上传一致（{@link I18nService#MAX_PACK_BYTES}），超限即断流</li>
 * </ul>
 *
 * <p>内网部署需要从局域网拉包时，配 {@code galworld.i18n.market-allow-private=true}
 * 或把主机名加进 {@code galworld.i18n.market-allow-hosts}（非空时只认清单内的主机，
 * 跳过地址检查）。
 */
@Component
public class LocaleMarketClient {

    private static final Logger log = LoggerFactory.getLogger(LocaleMarketClient.class);

    /** 最多跟几跳重定向 */
    private static final int MAX_REDIRECTS = 3;

    private static final Pattern CHARSET_PARAM =
            Pattern.compile("(?i)charset\\s*=\\s*\"?([A-Za-z0-9._:-]+)");
    private static final Pattern DISPOSITION_FILENAME =
            Pattern.compile("(?i)filename\\s*\\*?\\s*=\\s*\"?([^\";]+)");

    private final HttpClient http;
    private final Duration timeout;
    private final List<String> allowHosts;
    private final boolean allowPrivate;

    /** 下载结果：文件名用于格式识别，内容交给导入器 */
    public record Fetched(String fileName, String content, String finalUrl) {
    }

    @Autowired
    public LocaleMarketClient(
            @Value("${galworld.i18n.market-timeout:20s}") Duration timeout,
            @Value("${galworld.i18n.market-allow-hosts:}") List<String> allowHosts,
            @Value("${galworld.i18n.market-allow-private:false}") boolean allowPrivate) {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        // 重定向自己跟，每一跳都要重新校验地址
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                timeout, allowHosts, allowPrivate);
    }

    /** 测试用：注入自建 HttpClient */
    LocaleMarketClient(HttpClient http, Duration timeout, List<String> allowHosts,
                       boolean allowPrivate) {
        this.http = http;
        this.timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        this.allowHosts = allowHosts == null ? List.of()
                : allowHosts.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        this.allowPrivate = allowPrivate;
    }

    /**
     * 下载并解码。
     *
     * @param rawUrl 语言包直链
     * @throws IOException 网络失败、被安全策略拒绝、体积超限、HTTP 非 200
     */
    public Fetched fetch(String rawUrl) throws IOException {
        URI uri = parse(rawUrl);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkTarget(uri);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("User-Agent", "gal-world-portal/i18n-market")
                    .header("Accept", "application/json,text/plain,application/xml,*/*")
                    .GET()
                    .build();
            HttpResponse<InputStream> res = send(req);
            int code = res.statusCode();
            if (code >= 300 && code < 400) {
                String location = res.headers().firstValue("Location").orElse("");
                res.body().close();
                if (location.isBlank()) {
                    throw new IOException(I18n.t("重定向缺少 Location 头"));
                }
                uri = uri.resolve(location.strip());
                continue;
            }
            if (code != 200) {
                res.body().close();
                throw new IOException(I18n.t("下载失败：HTTP {0}", code));
            }
            byte[] bytes = readCapped(res.body());
            String content = new String(stripBom(bytes), charsetOf(
                    res.headers().firstValue("Content-Type").orElse("")));
            String fileName = fileNameFrom(uri,
                    res.headers().firstValue("Content-Disposition").orElse(""));
            log.info("语言包已从 {} 下载：{}（{} 字节）", uri, fileName, bytes.length);
            return new Fetched(fileName, content, uri.toString());
        }
        throw new IOException(I18n.t("重定向次数超过 {0} 次", MAX_REDIRECTS));
    }

    private HttpResponse<InputStream> send(HttpRequest req) throws IOException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(I18n.t("下载被中断"), e);
        }
    }

    // ---------------- 地址安全 ----------------

    private static URI parse(String rawUrl) throws IOException {
        String v = rawUrl == null ? "" : rawUrl.strip();
        if (v.isEmpty()) {
            throw new IOException(I18n.t("语言包地址为空"));
        }
        try {
            return new URI(v);
        } catch (URISyntaxException e) {
            throw new IOException(I18n.t("语言包地址格式错误: {0}", v), e);
        }
    }

    /**
     * 校验一跳地址是否可以访问。
     *
     * <p>配了主机清单就只认清单，不做地址检查——清单本身是运维显式放行的意思，
     * 内网主机名在这种场景下很常见。
     */
    void checkTarget(URI uri) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IOException(I18n.t("只支持 http / https 地址: {0}", uri));
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException(I18n.t("地址缺少主机名: {0}", uri));
        }
        if (!allowHosts.isEmpty()) {
            boolean allowed = allowHosts.stream().anyMatch(h -> h.equalsIgnoreCase(host));
            if (!allowed) {
                throw new IOException(I18n.t("主机不在允许清单内: {0}", host));
            }
            return;
        }
        if (allowPrivate) {
            return;
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IOException(I18n.t("无法解析主机: {0}", host), e);
        }
        for (InetAddress a : addrs) {
            if (isBlocked(a)) {
                throw new IOException(I18n.t("拒绝访问内网地址: {0} → {1}",
                        host, a.getHostAddress()));
            }
        }
    }

    /**
     * 是否属于不允许外连的地址段。
     *
     * <p>{@link InetAddress} 自带的方法漏了几段（CGNAT、0.0.0.0/8、198.18/15、240/4、
     * IPv6 ULA），这里补齐。
     */
    static boolean isBlocked(InetAddress addr) {
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            if (b0 == 0) {
                return true;                       // 0.0.0.0/8
            }
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return true;                       // 100.64.0.0/10 CGNAT
            }
            if (b0 == 192 && b1 == 0 && (b[2] & 0xFF) == 0) {
                return true;                       // 192.0.0.0/24 IETF 协议分配
            }
            if (b0 == 198 && (b1 == 18 || b1 == 19)) {
                return true;                       // 198.18.0.0/15 基准测试
            }
            return b0 >= 240;                      // 240.0.0.0/4 保留 + 广播
        }
        if (b.length == 16) {
            // fc00::/7 唯一本地地址
            return (b[0] & 0xFE) == 0xFC;
        }
        return true;
    }

    // ---------------- 响应处理 ----------------

    private static byte[] readCapped(InputStream in) throws IOException {
        long cap = I18nService.MAX_PACK_BYTES;
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
            byte[] buf = new byte[16 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > cap) {
                    throw new IOException(
                            I18n.t("语言包文件超过 {0} KB", cap / 1024));
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** UTF-8 BOM 会让 JSON 解析直接失败，先摘掉 */
    private static byte[] stripBom(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return java.util.Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        return bytes;
    }

    /** 从 Content-Type 取字符集；缺省 UTF-8（市场导出的语言包基本都是 UTF-8） */
    static Charset charsetOf(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        Matcher m = CHARSET_PARAM.matcher(contentType);
        if (!m.find()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(m.group(1));
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * 推断文件名，供 {@link LocaleImporter#detect} 按扩展名识别格式。
     *
     * <p>优先用 {@code Content-Disposition}（GitHub raw 之类会给真名），
     * 否则取 URL 路径最后一段。两者都拿不到时返回空串，由导入器按内容嗅探。
     */
    static String fileNameFrom(URI uri, String contentDisposition) {
        if (contentDisposition != null && !contentDisposition.isBlank()) {
            Matcher m = DISPOSITION_FILENAME.matcher(contentDisposition);
            if (m.find()) {
                String name = sanitizeFileName(m.group(1));
                if (!name.isEmpty()) {
                    return name;
                }
            }
        }
        String path = uri == null || uri.getPath() == null ? "" : uri.getPath();
        int slash = path.lastIndexOf('/');
        return sanitizeFileName(slash < 0 ? path : path.substring(slash + 1));
    }

    /** 只留最后一段，去掉路径分隔符与控制字符，限长 */
    private static String sanitizeFileName(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.strip();
        int cut = Math.max(v.lastIndexOf('/'), v.lastIndexOf('\\'));
        if (cut >= 0) {
            v = v.substring(cut + 1);
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length() && sb.length() < 120; i++) {
            char c = v.charAt(i);
            if (Character.isISOControl(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
