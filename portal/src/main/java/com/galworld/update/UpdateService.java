/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.galworld.config.PathConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class UpdateService {

    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);
    private static final long CHECK_TTL_MS = 5 * 60 * 1000L;
    /** 活跃状态超过这个时长没有推进，视为残留任务，允许新的申请覆盖。 */
    private static final long STALE_MS = 10 * 60 * 1000L;
    private static final int MANIFEST_MAX = 1024 * 1024;
    private static final Set<String> ACTIVE = Set.of(
            "queued", "precheck", "downloading", "verifying", "backing_up",
            "stopping", "replacing", "starting");
    private static final Set<String> TERMINAL = Set.of("success", "rolled_back", "failed");

    public record CheckResult(String currentVersion, String latestVersion,
                              String releaseName, boolean hasUpdate, String message,
                              String releaseUrl, String publishedAt) {}

    private final PathConfig paths;
    private final ObjectMapper mapper;
    private final ReleaseClient client;
    private final String currentVersion;
    private final boolean enabled;
    private final boolean allowOverride;
    private final UpdaterLauncher launcher;

    private volatile CheckResult cached;
    private volatile long cachedAt;

    public UpdateService(PathConfig paths, ObjectMapper mapper, ReleaseClient client,
                         @Value("${galworld.version:dev}") String currentVersion,
                         @Value("${galworld.update.enabled:true}") boolean enabled,
                         @Value("${galworld.update.allow-override:false}") boolean allowOverride,
                         UpdaterLauncher launcher) {
        this.paths = paths;
        this.mapper = mapper;
        this.client = client;
        this.currentVersion = currentVersion;
        this.enabled = enabled;
        this.allowOverride = allowOverride;
        this.launcher = launcher;
    }

    public String currentVersion() {
        return currentVersion;
    }

    /** 是否允许指定版本覆盖安装（含降级与同版本重装） */
    public boolean overrideEnabled() {
        return allowOverride;
    }

    public CheckResult check() {
        return check(false);
    }

    /** @param force 手动检查：跳过 5 分钟缓存，立即查询 Release */
    public CheckResult check(boolean force) {
        long now = System.currentTimeMillis();
        CheckResult c = cached;
        if (!force && c != null && now - cachedAt < CHECK_TTL_MS) return c;
        try {
            ReleaseClient.Release r = client.latest();
            // Gitee 的 latest 会把预发布算进去：分支包会盖住正式版，有稳定版时以稳定版为准
            if (r != null && !Version.isStableTag(r.tag())) {
                ReleaseClient.Release stable = client.latestStable();
                if (stable != null) r = stable;
            }
            CheckResult out;
            if (r == null) {
                out = new CheckResult(currentVersion, "", "", false,
                        "仓库暂无 Release 发布",
                        client.releasesWebUrl(), "");
            } else {
                boolean newer = Version.isStableTag(r.tag())
                        && Version.isNewer(r.tag(), currentVersion);
                String message;
                if (!Version.isStableTag(r.tag())) {
                    message = "最新发布为预发布版本 " + r.tag() + "，需在管理页覆盖更新";
                } else {
                    message = newer ? "发现新版本 " + r.tag() : "当前已是最新版本";
                }
                out = new CheckResult(currentVersion, r.tag(), r.name(), newer,
                        message,
                        r.htmlUrl().isBlank()
                                ? client.releasesWebUrl() : r.htmlUrl(),
                        r.publishedAt());
            }
            cached = out;
            cachedAt = now;
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("检查更新失败：" + e.getMessage(), e);
        }
    }

    public String apply(String version, String confirm) {
        return apply(version, confirm, false);
    }

    public synchronized String apply(String version, String confirm, boolean override) {
        if (!enabled) throw new UnsupportedOperationException("当前环境未启用远程更新");
        if (override && !allowOverride) {
            throw new UnsupportedOperationException("当前环境未启用覆盖更新");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("缺少目标版本");
        }
        String target = Version.normalize(version);
        // 覆盖更新按用户原样输入比对：v0.1.4-pr7 归一化后是 0.1.4，直接用 target 会要求错文本
        String expected = override ? "OVERRIDE " + version.strip() : "UPDATE " + target;
        if (!expected.equals(confirm == null ? "" : confirm.strip())) {
            throw new IllegalArgumentException("确认文本必须是 " + expected);
        }
        if (!override && !Version.isNewer(target, currentVersion)) {
            throw new IllegalArgumentException("目标版本必须高于当前版本 " + currentVersion);
        }
        if (!launcher.supported()) {
            throw new UnsupportedOperationException("当前平台不支持远程更新");
        }
        Map<String, Object> st = status();
        if (ACTIVE.contains(String.valueOf(st.get("phase")))) {
            if (!isStale(st)) {
                throw new IllegalStateException("已有更新任务在执行");
            }
            // 更新器崩溃/被信号杀死时可能留下 queued 等活跃状态；超过 10 分钟视为残留，
            // 允许重新申请，否则一次异常就会永久 409。
            log.warn("忽略残留的更新状态 phase={}（超过 10 分钟未推进）", st.get("phase"));
        }
        try {
            // Release tag 可能写成 0.1.1 或 v0.1.1：先按请求值查，再按 v<version> 兜底。
            String matchedTag = version;
            ReleaseClient.Release release = client.byTag(version);
            if (release == null) {
                // 回退用原样输入：0.1.4-pr7 对应 tag 是 v0.1.4-pr7，剥掉后缀会查不到
                String raw = version.strip();
                String alt = "v" + (raw.startsWith("V") ? raw.substring(1) : raw);
                if (!alt.equals(version)) {
                    release = client.byTag(alt);
                    matchedTag = alt;
                }
            }
            if (release == null) throw new IllegalArgumentException("Release 不存在: " + version);
            if (release.manifestUrl().isBlank()) {
                throw new IllegalArgumentException("Release 缺少 release-manifest.json");
            }
            JsonNode manifest = mapper.readTree(
                    client.assetText(release.manifestUrl(), MANIFEST_MAX));
            String mv = Version.normalize(manifest.path("version").asText(""));
            String mt = manifest.path("tag").asText("");
            if (!mv.equals(target) || !mt.equals(matchedTag)) {
                throw new IllegalArgumentException("manifest 版本或 tag 不匹配");
            }
            String from = manifest.path("minFromVersion").asText("");
            // 覆盖更新可以降级，minFromVersion 必然不满足；下限由更新器实检 jar 保证
            if (!override && !from.isBlank() && !Version.isNewer(currentVersion, from)
                    && !Version.normalize(currentVersion).equals(Version.normalize(from))) {
                throw new IllegalArgumentException("当前版本过低，低于 minFromVersion " + from);
            }
            String jobId = "upd-" + Instant.now().toEpochMilli() + "-"
                    + UUID.randomUUID().toString().substring(0, 4);
            String startedAt = Instant.now().toString();
            // 先写请求再写 queued 状态：任一写入失败都不会留下「假活跃」状态把后续申请卡死
            writeRequest(target, matchedTag, jobId, override);
            writeStatus(jobId, startedAt, "queued", target, "", "");
            try {
                launchUpdater(jobId);
            } catch (RuntimeException | IOException e) {
                writeStatus(jobId, startedAt, "failed", target, currentVersion,
                        "启动更新器失败：" + e.getMessage());
                throw e;
            }
            return jobId;
        } catch (IOException e) {
            throw new IllegalStateException("申请更新失败：" + e.getMessage(), e);
        }
    }

    /**
     * 活跃状态是否已过期。优先看 phaseStartedAt（当前阶段开始时间），
     * 旧状态文件没有该字段时退回 startedAt；没有可用时间戳按残留处理。
     */
    private boolean isStale(Map<String, Object> st) {
        String at = text(st.get("phaseStartedAt"));
        if (at.isEmpty()) at = text(st.get("startedAt"));
        if (at.isEmpty()) return true;
        try {
            Instant started = Instant.parse(at);
            return !Instant.now().isBefore(started.plusMillis(STALE_MS));
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    /** 测试可覆盖；返回进程 PID */
    long launchUpdater(String jobId) throws IOException {
        return launcher.launch(jobId);
    }

    public Map<String, Object> status() {
        Path file = paths.dataDir().resolve("update-status.json");
        if (!Files.isRegularFile(file)) {
            return Map.of("phase", "idle");
        }
        try {
            JsonNode n = mapper.readTree(Files.readString(file));
            String version = n.path("version").asText("");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("jobId", n.path("jobId").asText(""));
            out.put("phase", n.path("phase").asText("idle"));
            out.put("version", version);
            // toVersion 是状态文件规范里的别名；旧状态文件只有 version 时回退到它。
            out.put("toVersion", n.path("toVersion").asText(version));
            out.put("fromVersion", n.path("fromVersion").asText(""));
            out.put("message", n.path("message").asText(""));
            out.put("startedAt", n.path("startedAt").asText(""));
            out.put("phaseStartedAt", n.path("phaseStartedAt").asText(""));
            out.put("finishedAt", n.path("finishedAt").asText(""));
            return out;
        } catch (IOException e) {
            // IOException#getMessage() 可能为 null，Map.of 不接受 null 值。
            return Map.of("phase", "unknown",
                    "message", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void writeStatus(String jobId, String startedAt, String phase, String version,
                             String from, String message) throws IOException {
        Path file = paths.dataDir().resolve("update-status.json");
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("update-status.json.tmp");
        String now = Instant.now().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId == null ? "" : jobId);
        body.put("phase", phase);
        body.put("version", version == null ? "" : version);
        // 规范要求 toVersion；同时保留 version 兼容旧前端与旧状态文件。
        body.put("toVersion", version == null ? "" : version);
        body.put("fromVersion", from == null ? "" : from);
        body.put("message", message == null ? "" : message);
        // startedAt 固定为任务开始时间，phaseStartedAt 才是当前阶段的开始时间。
        body.put("startedAt", startedAt == null || startedAt.isBlank() ? now : startedAt);
        body.put("phaseStartedAt", now);
        if (TERMINAL.contains(phase)) body.put("finishedAt", now);
        Files.writeString(tmp, mapper.writeValueAsString(body));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    private void writeRequest(String version, String tag, String jobId, boolean override)
            throws IOException {
        Path file = paths.dataDir().resolve("update-request.json");
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("update-request.json.tmp");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("tag", tag);
        body.put("jobId", jobId);
        body.put("requestedAt", Instant.now().toString());
        // 更新器是独立进程（Linux 走 systemd-run），读不到 Portal 的环境变量，
        // 这里把当前生效的发布接口地址带过去，换镜像时两边才用同一个源。
        body.put("releaseApi", client.repoApiUrl());
        // 更新器据此跳过版本方向与 minFromVersion 检查
        body.put("override", override);
        Files.writeString(tmp, mapper.writeValueAsString(body));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
