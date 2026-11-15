/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sander_i.service.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class ReleaseClient {

    public record Release(String tag, String name, String htmlUrl,
                          String publishedAt, String manifestUrl) {}

    private static final String DEFAULT_API = "https://gitee.com/api/v5/repos";
    private static final String DEFAULT_WEB_BASE = "https://gitee.com";
    /** 上游仓库路径（owner/repo）。fork 或自建发布时用 GALWORLD_UPDATE_REPO 覆盖。 */
    private static final String DEFAULT_REPO = "sander-o/gal-world";
    private static final int MAX_JSON = 512 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper;
    private final String apiBase;
    private final String repoPath;
    private final String webBase;
    private final String token;
    private final String userAgent;
    /** 可为 null（测试直接构造时）：非 null 时每次请求都按设置里的更新源解析 */
    private final SettingsService settings;

    @Autowired
    public ReleaseClient(ObjectMapper mapper,
                         @Value("${galworld.update.api-base:" + DEFAULT_API + "}") String apiBase,
                         @Value("${galworld.update.token:${galworld.update.gitee-token:}}") String token,
                         @Value("${galworld.version:dev}") String version,
                         @Value("${galworld.update.repo:" + DEFAULT_REPO + "}") String repo,
                         @Value("${galworld.update.web-base:" + DEFAULT_WEB_BASE + "}") String webBase,
                         SettingsService settings) {
        this.mapper = mapper;
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.repoPath = "/" + repo.replaceAll("^/+|/+$", "");
        this.webBase = webBase.replaceAll("/+$", "");
        this.token = token == null ? "" : token.strip();
        this.userAgent = "gal-world-portal/" + version;
        this.settings = settings;
    }

    /** 测试用：不读设置，固定用传入的地址 */
    ReleaseClient(ObjectMapper mapper, String apiBase, String token, String version,
                  String repo, String webBase) {
        this(mapper, apiBase, token, version, repo, webBase, null);
    }

    /** 当前生效的接口根地址：管理页/设置里的值优先，否则用配置默认值 */
    private String apiBase() {
        if (settings != null) {
            try {
                String v = settings.updateApiBase();
                if (!v.isEmpty()) return v;
            } catch (RuntimeException ignored) {
                // 设置库不可用时退回配置默认值，不让更新检查直接报错
            }
        }
        return apiBase;
    }

    /** 当前生效的仓库路径（带前导斜杠） */
    private String repoPath() {
        if (settings != null) {
            try {
                String v = settings.updateRepo();
                if (!v.isEmpty()) return "/" + v;
            } catch (RuntimeException ignored) {
                // 同上
            }
        }
        return repoPath;
    }

    /** 当前生效的仓库网页根地址 */
    private String webBase() {
        if (settings != null) {
            try {
                String v = settings.updateWebBase();
                if (!v.isEmpty()) return v;
            } catch (RuntimeException ignored) {
                // 同上
            }
        }
        return webBase;
    }

    /**
     * 发布接口里本仓库的完整地址（apiBase + owner/repo）。
     * 更新器进程读不到 Portal 的环境变量，靠请求文件里的这个值跟随同一更新源。
     */
    public String repoApiUrl() {
        return apiBase() + repoPath();
    }

    /** 仓库网页地址，用于「暂无 Release」等提示里的兜底链接。 */
    public String repoWebUrl() {
        return webBase() + repoPath();
    }

    /** Release 列表网页地址。 */
    public String releasesWebUrl() {
        return repoWebUrl() + "/releases";
    }

    public Release latest() throws IOException {
        return parseRelease(get(repoPath() + "/releases/latest"));
    }

    /**
     * 最新正式版。Gitee 的 {@code /releases/latest} 不排除预发布，
     * 重发分支包后会把正式版盖住；这里按版本号从列表里挑最大的正式 tag。
     */
    public Release latestStable() throws IOException {
        JsonNode list = get(repoPath() + "/releases?per_page=100");
        if (list == null || !list.isArray()) {
            return null;
        }
        Release best = null;
        for (JsonNode node : list) {
            String tag = node.path("tag_name").asText("").strip();
            if (tag.isEmpty() || !Version.isStableTag(tag)) {
                continue;
            }
            if (best == null || Version.isNewer(tag, best.tag())) {
                Release parsed = parseRelease(node);
                if (parsed != null) best = parsed;
            }
        }
        return best;
    }

    public Release byTag(String tag) throws IOException {
        return parseRelease(get(repoPath() + "/releases/tags/"
                + java.net.URLEncoder.encode(tag, StandardCharsets.UTF_8)));
    }

    public String assetText(String url, int maxBytes) throws IOException {
        HttpResponse<String> res = send(url, maxBytes);
        if (res.statusCode() != 200) {
            throw new IOException("下载资产失败: HTTP " + res.statusCode());
        }
        return res.body();
    }

    private JsonNode get(String path) throws IOException {
        HttpResponse<String> res = send(apiBase() + path, MAX_JSON);
        if (res.statusCode() == 404) {
            // 私有/不存在的仓库同样返回 404，不能直接当成「暂无 Release」
            requireRepoVisible();
            return null;
        }
        if (res.statusCode() != 200) {
            throw new IOException("更新源返回 " + res.statusCode());
        }
        return mapper.readTree(res.body());
    }

    /** Release 接口 404 时探测仓库本身，区分「没有 Release」与「仓库不存在/不可见」。 */
    void requireRepoVisible() throws IOException {
        int status = send(apiBase() + repoPath(), MAX_JSON).statusCode();
        if (status == 200) {
            return;
        }
        if (status == 404 || status == 401 || status == 403) {
            throw new IOException("仓库不存在或不可见（私有仓库需公开或配置更新源 token）");
        }
        throw new IOException("更新源返回 " + status);
    }

    HttpResponse<String> send(String url, int maxBytes) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .GET();
        if (!token.isEmpty()) b.header("Authorization", "token " + token);
        try {
            HttpResponse<String> res = http.send(b.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.body().length() > maxBytes) {
                throw new IOException("响应超过 " + maxBytes + " 字节");
            }
            return res;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求中断", e);
        }
    }

    private Release parseRelease(JsonNode node) {
        if (node == null || node.isMissingNode()) return null;
        String tag = node.path("tag_name").asText("").strip();
        if (tag.isEmpty()) return null;
        String manifestUrl = "";
        for (JsonNode asset : node.path("assets")) {
            if ("release-manifest.json".equals(asset.path("name").asText())) {
                manifestUrl = asset.path("browser_download_url").asText("");
                break;
            }
        }
        return new Release(tag, node.path("name").asText(tag),
                node.path("html_url").asText(""),
                node.path("published_at").asText(""), manifestUrl);
    }
}
