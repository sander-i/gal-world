/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.i18n;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 语言包市场下载：地址安全、重定向、文件名与字符集推断。
 *
 * <p>功能用例起一个本地 HTTP 服务，用 {@code allowPrivate=true} 放行——
 * 这个开关本身是给内网部署用的，测试正好借用它。
 */
class LocaleMarketClientTest {

    private HttpServer server;
    private String base;
    /** 每个路径的响应：状态码 / 头 / 正文 */
    private final java.util.Map<String, Resp> routes = new java.util.HashMap<>();
    private final AtomicInteger hits = new AtomicInteger();

    private record Resp(int status, java.util.Map<String, String> headers, byte[] body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            Resp r = routes.get(path);
            if (r == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            r.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(r.status(), r.body().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(r.body());
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void route(String path, int status, String body) {
        route(path, status, body, java.util.Map.of());
    }

    private void route(String path, int status, String body,
                       java.util.Map<String, String> headers) {
        routes.put(path, new Resp(status, headers, body.getBytes(StandardCharsets.UTF_8)));
    }

    private LocaleMarketClient client(boolean allowPrivate, List<String> allowHosts) {
        return new LocaleMarketClient(HttpClient.newHttpClient(), Duration.ofSeconds(10),
                allowHosts, allowPrivate);
    }

    // ---------------- 功能 ----------------

    @Test
    void 下载并按扩展名给文件名() throws IOException {
        route("/packs/ko.po", 200, "msgid \"保存\"\nmsgstr \"저장\"\n");
        LocaleMarketClient.Fetched f = client(true, List.of()).fetch(base + "/packs/ko.po");
        assertEquals("ko.po", f.fileName());
        assertTrue(f.content().contains("저장"));
        assertEquals(base + "/packs/ko.po", f.finalUrl());
    }

    @Test
    void ContentDisposition优先于路径末段() throws IOException {
        route("/download", 200, "{}",
                java.util.Map.of("Content-Disposition", "attachment; filename=\"ru.csv\""));
        LocaleMarketClient.Fetched f = client(true, List.of()).fetch(base + "/download?id=7");
        assertEquals("ru.csv", f.fileName());
    }

    @Test
    void ContentDisposition的路径片段被剥掉() throws IOException {
        route("/x", 200, "{}",
                java.util.Map.of("Content-Disposition", "attachment; filename=\"../../etc/de.arb\""));
        assertEquals("de.arb", client(true, List.of()).fetch(base + "/x").fileName());
    }

    @Test
    void 跟重定向且用最终地址() throws IOException {
        route("/old", 302, "", java.util.Map.of("Location", base + "/new/ko.xlf"));
        route("/new/ko.xlf", 200, "<xliff/>");
        LocaleMarketClient.Fetched f = client(true, List.of()).fetch(base + "/old");
        assertEquals("ko.xlf", f.fileName());
        assertEquals(base + "/new/ko.xlf", f.finalUrl());
    }

    @Test
    void 重定向超过上限即失败() throws IOException {
        // 每跳指向下一跳，永远到不了终点
        for (int i = 0; i < 6; i++) {
            route("/r" + i, 302, "", java.util.Map.of("Location", base + "/r" + (i + 1)));
        }
        IOException e = assertThrows(IOException.class,
                () -> client(true, List.of()).fetch(base + "/r0"));
        assertTrue(e.getMessage().contains("重定向"), e.getMessage());
    }

    @Test
    void 非200即失败() throws IOException {
        route("/gone", 404, "nope");
        IOException e = assertThrows(IOException.class,
                () -> client(true, List.of()).fetch(base + "/gone"));
        assertTrue(e.getMessage().contains("404"), e.getMessage());
    }

    @Test
    void 超过体积上限即中断() throws IOException {
        // 上限 2MB，这里给 3MB 的重复内容
        StringBuilder sb = new StringBuilder(3 * 1024 * 1024);
        for (int i = 0; i < 3 * 1024 * 1024; i++) {
            sb.append('a');
        }
        route("/big.json", 200, sb.toString());
        IOException e = assertThrows(IOException.class,
                () -> client(true, List.of()).fetch(base + "/big.json"));
        assertTrue(e.getMessage().contains("KB"), e.getMessage());
    }

    @Test
    void BOM被摘掉() throws IOException {
        routes.put("/bom.json", new Resp(200, java.util.Map.of(),
                ("﻿{\"id\":\"ko\"}").getBytes(StandardCharsets.UTF_8)));
        String content = client(true, List.of()).fetch(base + "/bom.json").content();
        assertEquals("{\"id\":\"ko\"}", content);
    }

    // ---------------- 字符集 ----------------

    @Test
    void 字符集取自ContentType() {
        assertEquals(StandardCharsets.UTF_8,
                LocaleMarketClient.charsetOf("application/json"));
        assertEquals(StandardCharsets.UTF_8,
                LocaleMarketClient.charsetOf("text/plain; charset=UTF-8"));
        assertEquals(java.nio.charset.Charset.forName("GBK"),
                LocaleMarketClient.charsetOf("text/plain; charset=gbk"));
        assertEquals(StandardCharsets.UTF_8,
                LocaleMarketClient.charsetOf("text/plain; charset=no-such-charset"));
        assertEquals(StandardCharsets.UTF_8, LocaleMarketClient.charsetOf(""));
    }

    @Test
    void 按ContentType字符集解码() throws IOException {
        // 用 GBK 能编码的中文：韩文在 GBK 里没有码位，写进去只会变成问号
        routes.put("/gbk.csv", new Resp(200,
                java.util.Map.of("Content-Type", "text/csv; charset=GBK"),
                "source,target\n保存,存储\n".getBytes(java.nio.charset.Charset.forName("GBK"))));
        String content = client(true, List.of()).fetch(base + "/gbk.csv").content();
        assertTrue(content.contains("保存,存储"), content);
    }

    // ---------------- 地址安全 ----------------

    @Test
    void 拒绝非http协议() {
        LocaleMarketClient c = client(true, List.of());
        assertThrows(IOException.class, () -> c.fetch("file:///etc/passwd"));
        assertThrows(IOException.class, () -> c.fetch("jar:file:/a.jar!/b"));
        assertThrows(IOException.class, () -> c.fetch("ftp://example.com/ko.po"));
    }

    @Test
    void 拒绝空地址与畸形地址() {
        LocaleMarketClient c = client(true, List.of());
        assertThrows(IOException.class, () -> c.fetch(""));
        assertThrows(IOException.class, () -> c.fetch("   "));
        assertThrows(IOException.class, () -> c.fetch("http://"));
    }

    @Test
    void 默认拒绝回环与私网() {
        LocaleMarketClient c = client(false, List.of());
        // 这条请求根本没发出去：地址校验先拦下
        assertThrows(IOException.class, () -> c.fetch(base + "/packs/ko.po"));
        assertEquals(0, hits.get());
    }

    @Test
    void 回环与私网判定() throws Exception {
        assertTrue(LocaleMarketClient.isBlocked(InetAddress.getByName("127.0.0.1")));
        assertTrue(LocaleMarketClient.isBlocked(InetAddress.getByName("127.1.2.3")));
        assertTrue(LocaleMarketClient.isBlocked(InetAddress.getByName("10.1.2.3")));
        assertTrue(LocaleMarketClient.isBlocked(InetAddress.getByName("172.16.0.1")));
        assertTrue(LocaleMarketClient.isBlocked(InetAddress.getByName("192.168.1.1")));
        assertTrue(LocaleMarketClient.isBlocked(InetAddress.getByName("169.254.1.1")));
        assertTrue(LocaleMarketClient.isBlocked(InetAddress.getByName("0.0.0.0")));
        assertTrue(LocaleMarketClient.isBlocked(InetAddress.getByName("100.64.0.1")));
        assertTrue(LocaleMarketClient.isBlocked(InetAddress.getByName("100.127.255.254")));
        assertTrue(LocaleMarketClient.isBlocked(InetAddress.getByName("198.18.0.1")));
        assertTrue(LocaleMarketClient.isBlocked(InetAddress.getByName("198.19.255.254")));
        assertTrue(LocaleMarketClient.isBlocked(InetAddress.getByName("240.0.0.1")));
        assertTrue(LocaleMarketClient.isBlocked(InetAddress.getByName("255.255.255.255")));
        assertTrue(LocaleMarketClient.isBlocked(InetAddress.getByName("::1")));
        assertTrue(LocaleMarketClient.isBlocked(InetAddress.getByName("fe80::1")));
        assertTrue(LocaleMarketClient.isBlocked(InetAddress.getByName("fd00::1")));
        // 公网地址放行
        assertFalse(LocaleMarketClient.isBlocked(InetAddress.getByName("1.1.1.1")));
        assertFalse(LocaleMarketClient.isBlocked(InetAddress.getByName("8.8.8.8")));
        assertFalse(LocaleMarketClient.isBlocked(InetAddress.getByName("100.63.255.254")));
        assertFalse(LocaleMarketClient.isBlocked(InetAddress.getByName("198.17.255.255")));
    }

    @Test
    void 主机清单外的地址被拒() {
        LocaleMarketClient c = client(true, List.of("packs.example.com"));
        IOException e = assertThrows(IOException.class,
                () -> c.fetch("http://other.example.com/ko.po"));
        assertTrue(e.getMessage().contains("允许清单"), e.getMessage());
    }

    @Test
    void 主机清单内的地址跳过地址检查() throws IOException {
        route("/packs/ko.po", 200, "msgid \"a\"\nmsgstr \"b\"\n");
        // 清单里放 127.0.0.1，allowPrivate=false 也应放行——清单即显式放行
        LocaleMarketClient.Fetched f = client(false, List.of("127.0.0.1"))
                .fetch(base + "/packs/ko.po");
        assertEquals("ko.po", f.fileName());
    }

    // ---------------- 与导入链路衔接 ----------------

    @Test
    void 下载的po能被导入器识别() throws IOException {
        route("/packs/ko.po", 200,
                "msgid \"\"\nmsgstr \"\"\n\"Language: ko\\n\"\n\n"
                        + "msgid \"保存\"\nmsgstr \"저장\"\n");
        LocaleMarketClient.Fetched f = client(true, List.of()).fetch(base + "/packs/ko.po");
        String format = LocaleImporter.detect(f.fileName(), f.content());
        assertEquals("po", format);
        LocaleImporter.Parsed parsed = LocaleImporter.parse(format, f.content(), "ko");
        assertEquals("ko", parsed.id());
        assertEquals("저장", parsed.entries().get("保存"));
    }

    @Test
    void 文件名推断覆盖常见形态() {
        assertEquals("ko.po", LocaleMarketClient.fileNameFrom(
                URI.create("https://x/a/ko.po"), ""));
        assertEquals("ko.po", LocaleMarketClient.fileNameFrom(
                URI.create("https://x/a/ko.po?token=abc"), ""));
        assertEquals("", LocaleMarketClient.fileNameFrom(URI.create("https://x/a/"), ""));
        assertEquals("de.arb", LocaleMarketClient.fileNameFrom(
                URI.create("https://x/dl?id=1"),
                "attachment; filename=de.arb"));
    }
}
