/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sander_i.config.PathConfig;
import io.github.sander_i.domain.Game;
import io.github.sander_i.platform.CommandRunner;
import io.github.sander_i.repo.GameRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 游玩页遥测与崩溃关联日志。
 *
 * 客户端每 10 秒上报一次内存/解码状态；发生页面错误时立即上报。
 * 服务端在 WS 断开、客户端报错时补一条记录，并附带当时的服务器环境快照，
 * 用来判断崩溃是客户端问题还是服务端资源问题。
 *
 * 落盘：{@code <root>/data/logs/play-telemetry.log}，JSONL，单文件超过 5MB 轮转一次。
 */
@Service
public class PlayTelemetryService {

    private static final Logger log = LoggerFactory.getLogger(PlayTelemetryService.class);
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int MAX_MESSAGE = 300;

    /** 只接受这些客户端字段，避免任意 JSON 把日志撑爆 */
    private static final Set<String> CLIENT_FIELDS = Set.of(
            "gameId", "event", "ts", "heapMB", "iframeHeapMB", "decoded", "dropped",
            "videoW", "videoH", "videoTime", "readyState", "visibility",
            "viewport", "canvas", "window", "ua", "message");

    private final PathConfig paths;
    private final GameRepository games;
    private final SessionService sessions;
    private final CommandRunner runner;
    private final ObjectMapper json;

    /** @ServerEndpoint 拿不到 Spring 注入，通过静态引用让 StreamWsBridge 也能写日志 */
    private static volatile PlayTelemetryService instance;

    public PlayTelemetryService(PathConfig paths, GameRepository games,
                                SessionService sessions, CommandRunner runner,
                                ObjectMapper json) {
        this.paths = paths;
        this.games = games;
        this.sessions = sessions;
        this.runner = runner;
        this.json = json;
    }

    @PostConstruct
    void register() {
        instance = this;
    }

    public static PlayTelemetryService instance() {
        return instance;
    }

    /** 客户端上报；未知游戏或空 body 返回 false。 */
    public synchronized boolean recordClient(Map<String, Object> body, String remote) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        String gameId = str(body.get("gameId"));
        if (gameId.isEmpty() || games.findById(gameId).isEmpty()) {
            return false;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.now().toString());
        line.put("src", "client");
        line.put("remote", remote == null ? "" : remote);
        for (String k : CLIENT_FIELDS) {
            if (body.containsKey(k)) {
                line.put(k, clean(body.get(k)));
            }
        }
        String event = str(body.get("event"));
        if ("crash".equals(event) || "pageerror".equals(event)) {
            line.put("server", snapshot(gameId));
            log.warn("游玩页崩溃上报 game={} event={} message={}",
                    gameId, event, str(body.get("message")));
        }
        append(line);
        return true;
    }

    /** 服务端事件（WS 打开/关闭/错误），带服务器环境快照。 */
    public synchronized void recordServer(String gameId, String event, Map<String, Object> extra) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.now().toString());
        line.put("src", "server");
        line.put("event", event == null ? "" : event);
        line.put("gameId", gameId == null ? "" : gameId);
        if (extra != null) {
            extra.forEach((k, v) -> line.put(k, clean(v)));
        }
        line.put("server", snapshot(gameId));
        append(line);
        if ("ws_error".equals(event) || "ws_close_abnormal".equals(event)) {
            log.warn("串流连接异常 game={} event={} extra={}", gameId, event, extra);
        }
    }

    /** 服务器当时的环境：会话状态、画布、窗口、负载、可用内存。 */
    private Map<String, Object> snapshot(String gameId) {
        Map<String, Object> s = new LinkedHashMap<>();
        try {
            games.findById(gameId).ifPresent(g -> {
                s.put("state", sessions.status(g.getId(), g.getPort()).state().name());
                s.put("canvas", g.getWidth() + "x" + g.getHeight());
                s.put("display", g.getDisplayNum());
                var window = measureWindow(g.getDisplayNum());
                if (window != null) {
                    s.put("window", window.width() + "x" + window.height()
                            + "+" + window.x() + "+" + window.y());
                }
            });
        } catch (RuntimeException e) {
            s.put("error", e.toString());
        }
        s.put("loadAvg", readFirstLine("/proc/loadavg"));
        s.put("memAvailableMB", memAvailableMb());
        return s;
    }

    private WindowGeometry measureWindow(int displayNum) {
        CommandRunner.Result r = runner.exec(
                List.of("xwininfo", "-display", ":" + displayNum, "-root", "-tree"),
                Duration.ofSeconds(2));
        return r == null || !r.ok() ? null : WindowGeometry.largest(r.output());
    }

    private static String readFirstLine(String file) {
        try {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static Long memAvailableMb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]) / 1024;
                }
            }
        } catch (IOException | RuntimeException e) {
            // 非 Linux 或读取失败：留空
        }
        return null;
    }

    private void append(Map<String, Object> line) {
        Path file = paths.logsDir().resolve("play-telemetry.log");
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
                Files.move(file, file.resolveSibling("play-telemetry.log.1"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, json.writeValueAsString(line) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("写入游玩遥测失败: {}", e.toString());
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 标量限制长度，防止客户端把超长字符串塞进日志 */
    private static Object clean(Object v) {
        if (v instanceof String s) {
            return s.length() > MAX_MESSAGE ? s.substring(0, MAX_MESSAGE) : s;
        }
        if (v instanceof Number || v instanceof Boolean || v == null) {
            return v;
        }
        return String.valueOf(v);
    }
}
