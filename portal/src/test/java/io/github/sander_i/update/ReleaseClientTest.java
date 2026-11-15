/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sander_i.service.SettingsService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReleaseClientTest {

    private static final String API = "https://gitee.com/api/v5/repos";
    private static final String REPO_PATH = "sander-o/gal-world";
    private static final String REPO = API + "/" + REPO_PATH;

    /** 用固定状态码替代真实 HTTP：key 为完整 URL，未命中返回 599 以避免误判 */
    private ReleaseClient client(Map<String, Integer> statuses) {
        return new ReleaseClient(new ObjectMapper(), API, "", "0.1.0", REPO_PATH,
                "https://gitee.com") {
            @Override
            @SuppressWarnings("unchecked")
            HttpResponse<String> send(String url, int maxBytes) {
                HttpResponse<String> res = mock(HttpResponse.class);
                when(res.statusCode()).thenReturn(statuses.getOrDefault(url, 599));
                when(res.body()).thenReturn("{}");
                return res;
            }
        };
    }

    @Test
    void latestStableSkipsPrereleasesAndPicksHighestVersion() throws Exception {
        // Gitee 的 /releases/latest 会把预发布也算进去，分支包会盖住正式版
        String body = """
                [{"tag_name":"v0.1.4-verify","name":"verify","html_url":"u1"},
                 {"tag_name":"v0.1.4","name":"stable","html_url":"u2"},
                 {"tag_name":"v0.1.2","name":"old","html_url":"u3"}]
                """;
        ReleaseClient c = new ReleaseClient(new ObjectMapper(), API, "", "0.1.0", REPO_PATH,
                "https://gitee.com") {
            @Override
            @SuppressWarnings("unchecked")
            HttpResponse<String> send(String url, int maxBytes) {
                HttpResponse<String> res = mock(HttpResponse.class);
                when(res.statusCode()).thenReturn(200);
                when(res.body()).thenReturn(body);
                return res;
            }
        };
        assertEquals("v0.1.4", c.latestStable().tag());
    }

    @Test
    void latestStableReturnsNullWhenOnlyPrereleasesExist() throws Exception {
        String body = "[{\"tag_name\":\"v0.1.4-verify\",\"name\":\"verify\"}]";
        ReleaseClient c = new ReleaseClient(new ObjectMapper(), API, "", "0.1.0", REPO_PATH,
                "https://gitee.com") {
            @Override
            @SuppressWarnings("unchecked")
            HttpResponse<String> send(String url, int maxBytes) {
                HttpResponse<String> res = mock(HttpResponse.class);
                when(res.statusCode()).thenReturn(200);
                when(res.body()).thenReturn(body);
                return res;
            }
        };
        assertNull(c.latestStable());
    }

    @Test
    void latestReturnsNullWhenPublicRepoHasNoRelease() throws Exception {
        ReleaseClient c = client(Map.of(
                REPO + "/releases/latest", 404,
                REPO, 200));
        assertNull(c.latest());
    }

    @Test
    void latestFailsClearlyWhenRepositoryIsPrivateOrMissing() {
        ReleaseClient c = client(Map.of(
                REPO + "/releases/latest", 404,
                REPO, 404));
        IOException e = assertThrows(IOException.class, () -> c.latest());
        assertTrue(e.getMessage().contains("仓库不存在或不可见"),
                "message was: " + e.getMessage());
    }

    @Test
    void byTagFailsClearlyWhenRepositoryIsPrivateOrMissing() {
        ReleaseClient c = client(Map.of(
                REPO + "/releases/tags/v0.1.1", 404,
                REPO, 403));
        IOException e = assertThrows(IOException.class, () -> c.byTag("v0.1.1"));
        assertTrue(e.getMessage().contains("仓库不存在或不可见"),
                "message was: " + e.getMessage());
    }

    @Test
    void byTagReturnsNullWhenPublicRepoHasNoSuchTag() throws Exception {
        ReleaseClient c = client(Map.of(
                REPO + "/releases/tags/v9.9.9", 404,
                REPO, 200));
        assertNull(c.byTag("v9.9.9"));
    }

    @Test
    void privateRepo401And403BothReportClearMessage() {
        for (int status : new int[] {401, 403}) {
            ReleaseClient c = client(Map.of(
                    REPO + "/releases/latest", 404,
                    REPO, status));
            IOException e = assertThrows(IOException.class, () -> c.latest());
            assertTrue(e.getMessage().contains("仓库不存在或不可见"),
                    "status " + status + " message was: " + e.getMessage());
        }
    }

    @Test
    void repoPathIsConfigurable() throws Exception {
        // fork 或换托管平台时只改配置，源码不动
        String other = "someone/my-fork";
        String url = API + "/" + other + "/releases/latest";
        ReleaseClient c = new ReleaseClient(new ObjectMapper(), API, "", "0.1.0", other,
                "https://gitee.com") {
            @Override
            @SuppressWarnings("unchecked")
            HttpResponse<String> send(String u, int maxBytes) {
                HttpResponse<String> res = mock(HttpResponse.class);
                when(res.statusCode()).thenReturn(url.equals(u) ? 404 : 200);
                when(res.body()).thenReturn("{}");
                return res;
            }
        };
        assertNull(c.latest());
        assertEquals("https://gitee.com/" + other + "/releases", c.releasesWebUrl());
    }

    @Test
    void sourceFollowsSettingsOverride() throws Exception {
        // 连不上默认站时，管理页把三项改成镜像地址即可，不用改源码或重启
        SettingsService settings = mock(SettingsService.class);
        when(settings.updateApiBase()).thenReturn("https://mirror.example/api/v1/repos");
        when(settings.updateRepo()).thenReturn("mirror/gal-world");
        when(settings.updateWebBase()).thenReturn("https://mirror.example");
        java.util.List<String> seen = new java.util.ArrayList<>();
        ReleaseClient c = new ReleaseClient(new ObjectMapper(), API, "", "0.1.0", REPO_PATH,
                "https://gitee.com", settings) {
            @Override
            @SuppressWarnings("unchecked")
            HttpResponse<String> send(String u, int maxBytes) {
                seen.add(u);
                HttpResponse<String> res = mock(HttpResponse.class);
                when(res.statusCode()).thenReturn(200);
                when(res.body()).thenReturn("{}");
                return res;
            }
        };
        c.latest();
        assertEquals("https://mirror.example/api/v1/repos/mirror/gal-world/releases/latest",
                seen.get(0));
        assertEquals("https://mirror.example/api/v1/repos/mirror/gal-world", c.repoApiUrl());
        assertEquals("https://mirror.example/mirror/gal-world/releases", c.releasesWebUrl());
    }

    @Test
    void blankSettingsFallBackToConfiguredSource() throws Exception {
        // 设置里没填（或老库没有这三个键）时行为不变，仍用 GALWORLD_UPDATE_* 的默认值
        SettingsService settings = mock(SettingsService.class);
        when(settings.updateApiBase()).thenReturn("");
        when(settings.updateRepo()).thenReturn("");
        when(settings.updateWebBase()).thenReturn("");
        java.util.List<String> seen = new java.util.ArrayList<>();
        ReleaseClient c = new ReleaseClient(new ObjectMapper(), API, "", "0.1.0", REPO_PATH,
                "https://gitee.com", settings) {
            @Override
            @SuppressWarnings("unchecked")
            HttpResponse<String> send(String u, int maxBytes) {
                seen.add(u);
                HttpResponse<String> res = mock(HttpResponse.class);
                when(res.statusCode()).thenReturn(200);
                when(res.body()).thenReturn("{}");
                return res;
            }
        };
        c.latest();
        assertEquals(REPO + "/releases/latest", seen.get(0));
        assertEquals("https://gitee.com/" + REPO_PATH + "/releases", c.releasesWebUrl());
    }
}
