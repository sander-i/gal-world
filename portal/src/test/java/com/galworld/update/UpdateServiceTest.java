/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.galworld.config.PathConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpdateServiceTest {

    @TempDir Path root;

    ReleaseClient client;
    UpdaterLauncher launcher;
    UpdateService service;

    @BeforeEach
    void setUp() throws Exception {
        PathConfig paths = new PathConfig(root.toString());
        client = mock(ReleaseClient.class);
        launcher = mock(UpdaterLauncher.class);
        when(launcher.supported()).thenReturn(true);
        when(launcher.launch(anyString())).thenReturn(4242L);
        when(client.releasesWebUrl()).thenReturn("https://example.invalid/releases");
        when(client.repoApiUrl()).thenReturn("https://mirror.example/api/v1/repos/o/r");
        service = new UpdateService(paths, new ObjectMapper(), client,
                "0.1.0-SNAPSHOT", true, false, launcher);
    }

    private UpdateService serviceWithOverride() {
        return new UpdateService(new PathConfig(root.toString()), new ObjectMapper(),
                client, "0.1.0-SNAPSHOT", true, true, launcher);
    }

    private ReleaseClient.Release release(String tag, String manifest) {
        return new ReleaseClient.Release(tag, "Gal World " + tag,
                "https://example.invalid/releases/tag/" + tag,
                "2026-11-07T21:00:00Z", manifest);
    }

    private String manifest(String version, String tag) {
        return """
                {"schemaVersion":1,"version":"%s","tag":"%s",
                 "artifact":{"name":"gal-world-portal-%s.jar","size":123,"sha256":"ab"},
                 "minFromVersion":"0.1.0"}
                """.formatted(version, tag, version);
    }

    private void stubRelease(String tag) throws Exception {
        when(client.byTag(tag)).thenReturn(release(tag, "https://x/manifest.json"));
        when(client.assetText("https://x/manifest.json", 1024 * 1024))
                .thenReturn(manifest("0.1.1", tag));
    }

    @Test
    void checkReportsUpdateForNewerTag() throws Exception {
        when(client.latest()).thenReturn(release("v0.1.1", "https://x/manifest.json"));
        var r = service.check();
        assertTrue(r.hasUpdate());
        assertEquals("v0.1.1", r.latestVersion());
        assertEquals("0.1.0-SNAPSHOT", r.currentVersion());
    }

    @Test
    void checkReportsNoReleaseWithoutFailing() throws Exception {
        when(client.latest()).thenReturn(null);
        var r = service.check();
        assertFalse(r.hasUpdate());
        assertEquals("仓库暂无 Release 发布", r.message());
    }

    @Test
    void checkCachesWithinTtl() throws Exception {
        when(client.latest()).thenReturn(release("v0.1.1", "u"));
        service.check();
        service.check();
        verify(client, times(1)).latest();
    }

    @Test
    void forcedCheckBypassesCache() throws Exception {
        // 手动点「检查更新」必须看到刚发布的 Release，不能被 5 分钟缓存挡住
        when(client.latest()).thenReturn(release("v0.1.1", "u"));
        service.check();
        service.check(true);
        verify(client, times(2)).latest();
    }

    @Test
    void applyRejectsWrongConfirm() {
        assertThrows(IllegalArgumentException.class,
                () -> service.apply("v0.1.1", "yes"));
    }

    @Test
    void applyRejectsSameOrOlderVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> service.apply("v0.1.0", "UPDATE 0.1.0"));
    }

    @Test
    void applyRejectsUnsupportedPlatformBeforeWritingAnything() throws Exception {
        when(launcher.supported()).thenReturn(false);
        assertThrows(UnsupportedOperationException.class,
                () -> service.apply("v0.1.1", "UPDATE 0.1.1"));
        assertEquals("idle", service.status().get("phase"));
        assertFalse(Files.exists(root.resolve("data/update-request.json")));
    }

    @Test
    void applyValidatesManifestAndWritesRequest() throws Exception {
        String manifest = """
                {"schemaVersion":1,"version":"0.1.1","tag":"v0.1.1",
                 "artifact":{"name":"gal-world-portal-0.1.1.jar","size":123,"sha256":"ab"},
                 "minFromVersion":"0.1.0"}
                """;
        when(client.byTag("v0.1.1")).thenReturn(release("v0.1.1", "https://x/manifest.json"));
        when(client.assetText("https://x/manifest.json", 1024 * 1024)).thenReturn(manifest);
        String jobId = service.apply("v0.1.1", "UPDATE 0.1.1");
        assertTrue(jobId.startsWith("upd-"));
        String body = Files.readString(root.resolve("data/update-request.json"));
        assertTrue(body.contains("\"version\":\"0.1.1\""));
        assertTrue(body.contains("\"tag\":\"v0.1.1\""));
        // 更新器是独立进程，读不到 Portal 的环境变量：地址必须随请求文件带过去
        assertTrue(body.contains("\"releaseApi\":\"https://mirror.example/api/v1/repos/o/r\""));
    }

    @Test
    void launchFailureResetsStatusToFailed() throws Exception {
        String manifest = """
                {"schemaVersion":1,"version":"0.1.1","tag":"v0.1.1",
                 "artifact":{"name":"gal-world-portal-0.1.1.jar","size":123,"sha256":"ab"}}
                """;
        when(client.byTag("v0.1.1")).thenReturn(release("v0.1.1", "https://x/manifest.json"));
        when(client.assetText("https://x/manifest.json", 1024 * 1024)).thenReturn(manifest);
        when(launcher.launch(anyString()))
                .thenThrow(new IllegalStateException("启动更新器失败"));
        assertThrows(IllegalStateException.class,
                () -> service.apply("v0.1.1", "UPDATE 0.1.1"));
        assertEquals("failed", service.status().get("phase"));
    }

    @Test
    void requestWriteFailureDoesNotLeaveStuckQueuedStatus() throws Exception {
        String manifest = """
                {"schemaVersion":1,"version":"0.1.1","tag":"v0.1.1",
                 "artifact":{"name":"gal-world-portal-0.1.1.jar","size":123,"sha256":"ab"}}
                """;
        when(client.byTag("v0.1.1")).thenReturn(release("v0.1.1", "https://x/manifest.json"));
        when(client.assetText("https://x/manifest.json", 1024 * 1024)).thenReturn(manifest);

        // 让 update-request.json 的原子替换失败（目标是非空目录）
        Path request = root.resolve("data/update-request.json");
        Files.createDirectories(request);
        Files.writeString(request.resolve("blocker"), "x");

        assertThrows(IllegalStateException.class,
                () -> service.apply("v0.1.1", "UPDATE 0.1.1"));
        // 申请没成功，状态不能停留在活跃态，否则后续申请会一直被 409 拒绝
        assertEquals("idle", service.status().get("phase"));

        Files.delete(request.resolve("blocker"));
        Files.delete(request);
        String jobId = service.apply("v0.1.1", "UPDATE 0.1.1");
        assertTrue(jobId.startsWith("upd-"));
    }

    @Test
    void statusReturnsIdleWhenNoStatusFile() {
        assertEquals("idle", service.status().get("phase"));
    }

    @Test
    void statusExposesJobIdToVersionAndTimestamps() throws Exception {
        stubRelease("v0.1.1");
        String jobId = service.apply("v0.1.1", "UPDATE 0.1.1");
        Map<String, Object> st = service.status();
        assertEquals("queued", st.get("phase"));
        assertEquals(jobId, st.get("jobId"));
        assertEquals("0.1.1", st.get("version"));
        assertEquals("0.1.1", st.get("toVersion"));
        assertFalse(String.valueOf(st.get("startedAt")).isBlank());
        assertFalse(String.valueOf(st.get("phaseStartedAt")).isBlank());
        assertEquals("", st.get("finishedAt"));
    }

    @Test
    void terminalStatusWritesFinishedAt() throws Exception {
        stubRelease("v0.1.1");
        when(launcher.launch(anyString()))
                .thenThrow(new IllegalStateException("systemd-run 不可用"));
        assertThrows(IllegalStateException.class,
                () -> service.apply("v0.1.1", "UPDATE 0.1.1"));
        Map<String, Object> st = service.status();
        assertEquals("failed", st.get("phase"));
        assertFalse(String.valueOf(st.get("jobId")).isBlank());
        assertFalse(String.valueOf(st.get("startedAt")).isBlank());
        assertFalse(String.valueOf(st.get("finishedAt")).isBlank());
    }

    @Test
    void applyAcceptsVersionWithoutVPrefixAndFallsBackToVTag() throws Exception {
        // 请求里没有 v，Gitee 上只有 v0.1.1：第二次查询必须命中 v0.1.1。
        when(client.byTag("0.1.1")).thenReturn(null);
        stubRelease("v0.1.1");
        String jobId = service.apply("0.1.1", "UPDATE 0.1.1");
        assertTrue(jobId.startsWith("upd-"));
        String body = Files.readString(root.resolve("data/update-request.json"));
        assertTrue(body.contains("\"version\":\"0.1.1\""));
        assertTrue(body.contains("\"tag\":\"v0.1.1\""));
        assertTrue(body.contains("\"jobId\":\"" + jobId + "\""));
        verify(client).byTag("0.1.1");
        verify(client).byTag("v0.1.1");
    }

    @Test
    void freshActiveStatusBlocksSecondApply() throws Exception {
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("data/update-status.json"),
                "{\"phase\":\"downloading\",\"startedAt\":\"" + Instant.now()
                        + "\",\"phaseStartedAt\":\"" + Instant.now() + "\"}");
        assertThrows(IllegalStateException.class,
                () -> service.apply("v0.1.1", "UPDATE 0.1.1"));
    }

    @Test
    void staleActiveStatusIsTreatedAsLeftoverAndAllowsApply() throws Exception {
        Files.createDirectories(root.resolve("data"));
        String old = Instant.now().minusSeconds(11 * 60).toString();
        Files.writeString(root.resolve("data/update-status.json"),
                "{\"phase\":\"queued\",\"startedAt\":\"" + old
                        + "\",\"phaseStartedAt\":\"" + old + "\"}");
        stubRelease("v0.1.1");
        String jobId = service.apply("v0.1.1", "UPDATE 0.1.1");
        assertTrue(jobId.startsWith("upd-"));
        assertEquals("queued", service.status().get("phase"));
    }

    @Test
    void activeStatusWithoutTimestampIsTreatedAsLeftover() throws Exception {
        // 旧状态文件没有时间戳：不能让它永久阻塞后续申请。
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("data/update-status.json"),
                "{\"phase\":\"queued\"}");
        stubRelease("v0.1.1");
        assertTrue(service.apply("v0.1.1", "UPDATE 0.1.1").startsWith("upd-"));
    }

    @Test
    void overrideConfirmTextKeepsRawRef() {
        UpdateService s = serviceWithOverride();
        // 后缀 tag 归一化后是 0.1.4，但确认文本必须用原样输入
        assertThrows(IllegalArgumentException.class,
                () -> s.apply("v0.1.4-pr7", "OVERRIDE 0.1.4", true));
        assertThrows(IllegalArgumentException.class,
                () -> s.apply("v0.1.4-pr7", "UPDATE 0.1.4", true));
    }

    @Test
    void applyRejectsOverrideWhenDisabled() throws Exception {
        stubRelease("v0.1.1");
        assertThrows(UnsupportedOperationException.class,
                () -> service.apply("v0.1.1", "OVERRIDE v0.1.1", true));
        assertEquals("idle", service.status().get("phase"));
    }

    @Test
    void applyAllowsDowngradeWhenOverrideEnabled() throws Exception {
        UpdateService s = serviceWithOverride();
        when(client.byTag("v0.1.0")).thenReturn(release("v0.1.0", "https://x/manifest.json"));
        when(client.assetText("https://x/manifest.json", 1024 * 1024))
                .thenReturn(manifest("0.1.0", "v0.1.0"));
        String jobId = s.apply("v0.1.0", "OVERRIDE v0.1.0", true);
        assertTrue(jobId.startsWith("upd-"));
        String body = Files.readString(root.resolve("data/update-request.json"));
        assertTrue(body.contains("\"override\":true"));
    }

    @Test
    void applyAllowsSameVersionWhenOverrideEnabled() throws Exception {
        UpdateService s = serviceWithOverride();
        when(client.byTag("0.1.0")).thenReturn(null);
        when(client.byTag("v0.1.0")).thenReturn(release("v0.1.0", "https://x/manifest.json"));
        when(client.assetText("https://x/manifest.json", 1024 * 1024))
                .thenReturn(manifest("0.1.0", "v0.1.0"));
        // 当前 0.1.0-SNAPSHOT 归一化后就是 0.1.0：同版本重装必须放行
        assertTrue(s.apply("0.1.0", "OVERRIDE 0.1.0", true).startsWith("upd-"));
    }

    @Test
    void overrideSkipsMinFromVersion() throws Exception {
        UpdateService s = serviceWithOverride();
        String man = """
                {"schemaVersion":1,"version":"0.1.0","tag":"v0.1.0",
                 "artifact":{"name":"gal-world-portal-0.1.0.jar","size":123,"sha256":"ab"},
                 "minFromVersion":"0.2.0"}
                """;
        when(client.byTag("v0.1.0")).thenReturn(release("v0.1.0", "https://x/manifest.json"));
        when(client.assetText("https://x/manifest.json", 1024 * 1024)).thenReturn(man);
        assertTrue(s.apply("v0.1.0", "OVERRIDE v0.1.0", true).startsWith("upd-"));
    }

    @Test
    void applyRejectsBelowMinFromVersionOnUpgrade() throws Exception {
        String man = """
                {"schemaVersion":1,"version":"0.1.1","tag":"v0.1.1",
                 "artifact":{"name":"gal-world-portal-0.1.1.jar","size":123,"sha256":"ab"},
                 "minFromVersion":"0.2.0"}
                """;
        when(client.byTag("v0.1.1")).thenReturn(release("v0.1.1", "https://x/manifest.json"));
        when(client.assetText("https://x/manifest.json", 1024 * 1024)).thenReturn(man);
        assertThrows(IllegalArgumentException.class,
                () -> service.apply("v0.1.1", "UPDATE 0.1.1"));
    }

    @Test
    void applyNormalPassesOverrideFalseInRequest() throws Exception {
        stubRelease("v0.1.1");
        service.apply("v0.1.1", "UPDATE 0.1.1");
        String body = Files.readString(root.resolve("data/update-request.json"));
        assertTrue(body.contains("\"override\":false"));
    }

    @Test
    void overrideStillRequiresRelease() {
        UpdateService s = serviceWithOverride();
        assertThrows(IllegalArgumentException.class,
                () -> s.apply("v9.9.9", "OVERRIDE v9.9.9", true));
    }

    @Test
    void overrideFindsPrefixedTagForRawSuffixedVersion() throws Exception {
        UpdateService s = serviceWithOverride();
        // 用户不写 v：回退查询必须用原样输入，不能把 -pr7 后缀剥掉
        when(client.byTag("0.1.4-pr7")).thenReturn(null);
        when(client.byTag("v0.1.4-pr7")).thenReturn(release("v0.1.4-pr7", "https://x/manifest.json"));
        when(client.assetText("https://x/manifest.json", 1024 * 1024))
                .thenReturn(manifest("0.1.4", "v0.1.4-pr7"));
        assertTrue(s.apply("0.1.4-pr7", "OVERRIDE 0.1.4-pr7", true).startsWith("upd-"));
    }

    @Test
    void checkIgnoresBranchTagsForNormalUpgrade() throws Exception {
        // 分支包是预发布：未开覆盖更新的实例不能把它当成普通新版本
        when(client.latest()).thenReturn(release("v0.1.4-pr7", "u"));
        var r = service.check();
        assertFalse(r.hasUpdate());
        assertTrue(r.message().contains("预发布"));
    }

    @Test
    void checkFallsBackToStableReleaseWhenGiteeLatestIsPrerelease() throws Exception {
        // Gitee 的 /releases/latest 不排除预发布，重发分支包后会把正式版盖住
        when(client.latest()).thenReturn(release("v0.1.4-verify", "u"));
        when(client.latestStable()).thenReturn(release("v0.1.4", "u"));
        var r = service.check();
        assertEquals("v0.1.4", r.latestVersion());
        // 当前是 0.1.0-SNAPSHOT，0.1.4 确实更新；关键是别再报预发布
        assertTrue(r.hasUpdate());
        assertEquals("发现新版本 v0.1.4", r.message());
    }
}
