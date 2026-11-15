/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sander_i.config.PathConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 元件下载器验收（docs/ONE-CLICK-DEPLOY.md §12.1）：
 * Nexus 优先 → 失败回退镜像 → SHA256 不符换源 → 本地缓存命中。
 *
 * 用 JDK 自带 HttpServer 起本地源，不依赖网络。
 */
class ArtifactDownloaderTest {

    private static final String FILE = "linux/demo/1.0/demo.bin";
    private static final String PAYLOAD = "hello-artifact-payload";
    private static final String GOOD_SHA = sha256(PAYLOAD.getBytes(StandardCharsets.UTF_8));

    @TempDir
    Path root;

    private HttpServer server;
    private String baseUrl;
    private ArtifactDownloader downloader;

    /** 各路径的返回体与状态码，测试内按需改写 */
    private final java.util.Map<String, byte[]> bodies = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Integer> status = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger assetHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.start();

        downloader = new ArtifactDownloader(new PathConfig(root.toString()), new ObjectMapper(), null);
        set("nexusBase", baseUrl + "/nexus");
        set("mirrors", List.of(baseUrl + "/mirror"));
        set("timeout", Duration.ofSeconds(10));
        set("username", "");
        set("password", "");

        bodies.put("/nexus/manifest.json", manifest(GOOD_SHA).getBytes(StandardCharsets.UTF_8));
        bodies.put("/nexus/" + FILE, PAYLOAD.getBytes(StandardCharsets.UTF_8));
        bodies.put("/mirror/https://upstream.example/demo.bin",
                PAYLOAD.getBytes(StandardCharsets.UTF_8));

        Path target = root.resolve("data/artifacts").resolve(FILE);
        Files.createDirectories(target.getParent());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void downloadsFromNexusAndVerifiesSha() throws Exception {
        Path out = downloader.fetch("demo");
        assertEquals(PAYLOAD, Files.readString(out));
        assertEquals(GOOD_SHA, sha256(Files.readAllBytes(out)));
        assertEquals(1, assetHits.get(), "应只请求一次资产");
    }

    @Test
    void fallsBackToMirrorWhenNexusAssetFails() throws Exception {
        status.put("/nexus/" + FILE, 500);
        Path out = downloader.fetch("demo");
        assertEquals(PAYLOAD, Files.readString(out));
        assertTrue(assetHits.get() >= 2, "Nexus 失败后应再试镜像");
    }

    @Test
    void switchesSourceWhenShaMismatch() throws Exception {
        bodies.put("/nexus/" + FILE, "tampered".getBytes(StandardCharsets.UTF_8));
        Path out = downloader.fetch("demo");
        assertEquals(PAYLOAD, Files.readString(out));
        assertTrue(assetHits.get() >= 2, "SHA 不符后应换下一个源");
    }

    @Test
    void usesLocalCacheWithoutTouchingAssets() throws Exception {
        Path target = root.resolve("data/artifacts").resolve(FILE);
        Files.writeString(target, PAYLOAD);
        Path out = downloader.fetch("demo");
        assertEquals(target, out);
        assertEquals(0, assetHits.get(), "缓存命中时不应下载资产");
    }

    @Test
    void failsWhenAllSourcesBroken() {
        status.put("/nexus/" + FILE, 500);
        status.put("/mirror/https://upstream.example/demo.bin", 404);
        assertThrows(ArtifactDownloader.ArtifactUnavailableException.class,
                () -> downloader.fetch("demo"));
    }

    // ---- helpers ----

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        boolean isAsset = (path.startsWith("/nexus/") || path.startsWith("/mirror/"))
                && !path.endsWith("/manifest.json");
        if (isAsset) {
            assetHits.incrementAndGet();
        }
        Integer code = status.get(path);
        byte[] body = bodies.get(path);
        if (code != null && code != 200) {
            ex.sendResponseHeaders(code, -1);
            ex.close();
            return;
        }
        if (body == null) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        // 支持 Range：从指定偏移返回剩余字节，验证断点续传路径
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=")) {
            int from = Integer.parseInt(range.substring(6, range.indexOf('-')));
            byte[] rest = new byte[Math.max(0, body.length - from)];
            System.arraycopy(body, Math.min(from, body.length), rest, 0, rest.length);
            ex.sendResponseHeaders(206, rest.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(rest);
            }
            return;
        }
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private String manifest(String sha) {
        return """
                {"schema":1,"generatedAt":"2026-11-03T00:00:00Z","artifacts":[
                  {"id":"demo","platform":"linux","version":"1.0","file":"%s",
                   "sha256":"%s","size":%d,
                   "upstream":{"url":"https://upstream.example/demo.bin","license":"MIT"},
                   "note":""}]}
                """.formatted(FILE, sha, PAYLOAD.length());
    }

    private void set(String field, Object value) throws Exception {
        Field f = ArtifactDownloader.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(downloader, value);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
