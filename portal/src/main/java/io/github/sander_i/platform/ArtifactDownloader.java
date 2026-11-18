/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sander_i.config.PathConfig;
import io.github.sander_i.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 元件下载器：Nexus 优先，镜像链回退，全程 SHA256 校验。
 *
 * 设计要点：
 * - 本地缓存：{@code data/artifacts/<file>} 已存在且哈希匹配 → 直接返回，不重复下载
 * - 断点续传：先下到 {@code .part}，失败后用 Range 从已收字节继续
 * - 校验失败即删除并换下一个源，避免半截文件被拿去安装
 * - 所有失败都抛 {@link ArtifactUnavailableException}，由部署步骤转成 FAILED
 */
@Component
public class ArtifactDownloader {

    private static final Logger log = LoggerFactory.getLogger(ArtifactDownloader.class);

    public static class ArtifactUnavailableException extends RuntimeException {
        public ArtifactUnavailableException(String msg) {
            super(msg);
        }
    }

    private final PathConfig paths;
    private final ObjectMapper mapper;
    private final HttpClient http;
    /** 设置页里可配的仓库地址与镜像；为空时回退到 application.yml 的值 */
    private final io.github.sander_i.service.SettingsService settings;

    @Value("${galworld.artifacts.nexus-base:}")
    private String nexusBase;

    @Value("${galworld.artifacts.username:}")
    private String username;

    @Value("${galworld.artifacts.password:}")
    private String password;

    @Value("${galworld.artifacts.timeout:30s}")
    private Duration timeout;

    @Value("${galworld.artifacts.mirrors:}")
    private List<String> mirrors;

    public ArtifactDownloader(PathConfig paths, ObjectMapper mapper,
                              io.github.sander_i.service.SettingsService settings) {
        this.paths = paths;
        this.mapper = mapper;
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 下载后的本地绝对路径。已缓存且校验通过则直接返回。 */
    public Path fetch(String artifactId) {
        return fetch(artifactId, line -> { });
    }

    /**
     * @param onLog 每行进度回调（给 SSE 用），可为空实现
     */
    public Path fetch(String artifactId, Consumer<String> onLog) {
        ArtifactManifest.Entry entry = manifestEntry(artifactId, onLog);
        Path target = paths.dataDir().resolve("artifacts").resolve(entry.file());
        Path part = Path.of(target + ".part");

        if (Files.isRegularFile(target) && sha256Matches(target, entry.sha256())) {
            onLog.accept(I18n.t("已缓存且校验通过: {0}", target.getFileName()));
            return target;
        }

        List<String> urls = candidateUrls(entry);
        if (urls.isEmpty()) {
            throw new ArtifactUnavailableException(
                    I18n.t("元件 {0} 没有可用下载源（Nexus 未配置且无镜像）", artifactId));
        }

        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new ArtifactUnavailableException(I18n.t("无法创建元件目录: {0}", e.getMessage()));
        }

        List<String> failures = new ArrayList<>();
        for (String url : urls) {
            onLog.accept(I18n.t("尝试下载: {0}", url));
            try {
                downloadWithResume(url, part, onLog);
                if (!sha256Matches(part, entry.sha256())) {
                    Files.deleteIfExists(part);
                    String msg = I18n.t("SHA256 校验失败（{0}），已删除并换源", url);
                    onLog.accept(msg);
                    failures.add(msg);
                    continue;
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                onLog.accept(I18n.t("下载完成并通过校验: {0}", target));
                return target;
            } catch (Exception e) {
                String msg = I18n.t("下载失败（{0}）: {1}", url, e.getMessage());
                onLog.accept(msg);
                failures.add(msg);
            }
        }
        throw new ArtifactUnavailableException(
                I18n.t("元件 {0} 所有源均失败:\n  {1}",
                        artifactId, String.join("\n  ", failures)));
    }

    // ---- manifest ----

    private ArtifactManifest.Entry manifestEntry(String artifactId, Consumer<String> onLog) {
        List<String> sources = manifestSources();
        List<String> failures = new ArrayList<>();
        for (String base : sources) {
            try {
                HttpRequest req = authorized(HttpRequest.newBuilder(
                        URI.create(base + "/manifest.json"))).timeout(timeout).GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    failures.add(base + " → HTTP " + resp.statusCode());
                    continue;
                }
                ArtifactManifest manifest = mapper.readValue(resp.body(), ArtifactManifest.class);
                ArtifactManifest.Entry entry = manifest.find(artifactId);
                if (entry == null) {
                    failures.add(I18n.t("{0} → manifest 中没有 {1}", base, artifactId));
                    continue;
                }
                return entry;
            } catch (Exception e) {
                failures.add(base + " → " + e.getMessage());
            }
        }
        throw new ArtifactUnavailableException(
                I18n.t("取 manifest 失败:\n  {0}", String.join("\n  ", failures)));
    }

    private List<String> manifestSources() {
        List<String> out = new ArrayList<>();
        String base = effectiveNexusBase();
        if (!base.isBlank()) {
            out.add(stripTrailingSlash(base));
        }
        return out;
    }

    /** 设置页优先，其次 application.yml；返回空串表示未配置 */
    private String effectiveNexusBase() {
        String fromSettings = settings == null ? "" : settings.artifactNexusBase();
        if (fromSettings != null && !fromSettings.isBlank()) {
            return fromSettings;
        }
        return nexusBase == null ? "" : nexusBase;
    }

    private List<String> effectiveMirrors() {
        List<String> fromSettings = settings == null ? List.of() : settings.artifactMirrors();
        if (!fromSettings.isEmpty()) {
            return fromSettings;
        }
        return mirrors == null ? List.of() : mirrors;
    }

    /**
     * 候选下载地址：Nexus 优先，其次镜像（镜像地址按 manifest 里的 upstream.url 改写）。
     *
     * 镜像模板形如 {@code https://gh-proxy.com/https://github.com/{owner}/{repo}/...}，
     * 这里只做前缀拼接，因为 gh-proxy 的规则就是「前缀 + 原始 URL」。
     */
    private List<String> candidateUrls(ArtifactManifest.Entry entry) {
        List<String> out = new ArrayList<>();
        String base = effectiveNexusBase();
        if (!base.isBlank()) {
            out.add(stripTrailingSlash(base) + "/" + entry.file());
        }
        String upstream = entry.upstream() == null ? "" : entry.upstream().url();
        if (upstream != null && !upstream.isBlank()) {
            for (String m : effectiveMirrors()) {
                if (m == null || m.isBlank()) {
                    continue;
                }
                out.add(stripTrailingSlash(m) + "/" + upstream);
            }
        }
        return out;
    }

    // ---- 下载 ----

    private void downloadWithResume(String url, Path part, Consumer<String> logLine)
            throws IOException, InterruptedException {
        long have = Files.isRegularFile(part) ? Files.size(part) : 0;
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(30));
        if (have > 0) {
            b.header("Range", "bytes=" + have + "-");
            logLine.accept(I18n.t("断点续传，已收 {0} 字节", have));
        }
        HttpRequest req = authorized(b).GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());

        int code = resp.statusCode();
        if (code != 200 && code != 206) {
            resp.body().close();
            throw new IOException("HTTP " + code);
        }
        // 服务器不支持 Range（返回 200）时只能从头写
        boolean append = have > 0 && code == 206;
        if (have > 0 && code == 200) {
            logLine.accept(I18n.t("服务端不支持断点续传，从头下载"));
        }

        long total = resp.headers().firstValue("Content-Length")
                .map(Long::parseLong).orElse(-1L);
        long written = append ? have : 0;
        long lastLog = System.currentTimeMillis();

        try (InputStream in = resp.body();
             var out = append
                     ? Files.newOutputStream(part, java.nio.file.StandardOpenOption.APPEND)
                     : Files.newOutputStream(part, java.nio.file.StandardOpenOption.CREATE,
                             java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                written += n;
                if (System.currentTimeMillis() - lastLog > 2000) {
                    logLine.accept(I18n.t("已下载 {0}{1}", human(written),
                            total > 0 ? " / " + human(append ? have + total : total) : ""));
                    lastLog = System.currentTimeMillis();
                }
            }
        }
        logLine.accept(I18n.t("下载结束，共 {0}", human(written)));
    }

    // ---- 工具 ----

    private HttpRequest.Builder authorized(HttpRequest.Builder b) {
        if (username != null && !username.isBlank()) {
            String token = java.util.Base64.getEncoder()
                    .encodeToString((username + ":" + (password == null ? "" : password))
                            .getBytes(StandardCharsets.UTF_8));
            b.header("Authorization", "Basic " + token);
        }
        b.header("User-Agent", "gal-world-portal/artifact-downloader");
        return b;
    }

    private boolean sha256Matches(Path file, String expected) {
        if (expected == null || expected.isBlank()) {
            // manifest 没给哈希：不当作通过，也不当作失败——记日志后拒绝使用
            log.warn("manifest 缺少 sha256，拒绝使用 {}", file);
            return false;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            String actual = toHex(md.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                log.warn("SHA256 不符 {}: 期望 {} 实际 {}", file, expected, actual);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("计算哈希失败 {}: {}", file, e.toString());
            return false;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
