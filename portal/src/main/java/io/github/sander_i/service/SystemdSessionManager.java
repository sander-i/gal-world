/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.platform.CommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Linux 会话后端：通过 {@code systemctl --user} 控制每个游戏的会话单元。
 *
 * 会话生命周期由 systemd 管理，门户只做启停与状态查询，
 * 因此浏览器关闭、门户重启都不影响正在运行的游戏。
 */
public class SystemdSessionManager implements SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SystemdSessionManager.class);
    private static final Duration SYSTEMCTL_TIMEOUT = Duration.ofSeconds(15);

    private final PathConfig paths;
    private final CommandRunner runner;

    /**
     * 显式配置的串流地址（galworld.stream-url）。
     *
     * Linux 的 Selkies 是每游戏一个端口，通常不需要配；配了就用它，
     * 例如门户在反向代理后面、对外端口与游戏端口不在同一个域名下。
     */
    private final String streamBaseUrl;

    public SystemdSessionManager(PathConfig paths, CommandRunner runner) {
        this(paths, runner, "");
    }

    public SystemdSessionManager(PathConfig paths, CommandRunner runner, String streamBaseUrl) {
        this.paths = paths;
        this.runner = runner;
        this.streamBaseUrl = streamBaseUrl;
    }

    public String unitName(String gameId) {
        return "galworld-game@" + gameId + ".service";
    }

    @Override
    public SessionService.SessionStatus status(String gameId, int port) {
        String unit = unitName(gameId);
        if (!available()) {
            return new SessionService.SessionStatus(gameId,
                    SessionService.State.UNSUPPORTED, "systemctl not available", null, false);
        }
        String value = systemctl("is-active", unit).toLowerCase(Locale.ROOT);
        SessionService.State state = switch (value) {
            case "active" -> SessionService.State.RUNNING;
            case "inactive" -> SessionService.State.STOPPED;
            case "failed" -> SessionService.State.FAILED;
            default -> SessionService.State.UNKNOWN;
        };
        // 不在这里拼主机名：服务端不知道用户是从哪个地址访问门户的
        // （用 GALWORLD_HOST 猜，没配就落到 localhost，浏览器会去连自己）。
        // 返回 null 让前端用「当前页面主机名 + 游戏端口」拼，与 Windows 侧一致。
        String url = state == SessionService.State.RUNNING
                ? streamBaseUrl(port) : null;

        boolean crashed = false;
        boolean userStopped = Files.exists(stoppedMarker(gameId));
        if (userStopped) {
            crashed = false;
        } else if (state == SessionService.State.FAILED) {
            crashed = true;
        } else if (state == SessionService.State.STOPPED) {
            crashed = Files.exists(intentMarker(gameId));
        }
        // 注意：RUNNING 时不清 intentMarker。
        // systemd 在进程刚拉起、随即又退出时，is-active 会先短暂返回 active，
        // 若那一刻把标记删掉，随后判 crashed 就永远为 false，前端拿不到
        // 「进程已退出」的信号，只能干等到 90 秒超时。标记只在 stop() 里清。
        return new SessionService.SessionStatus(gameId, state,
                value.isEmpty() ? "unknown" : value, url, crashed);
    }

    /**
     * 单元进入 active 的时间，用作「本次会话开了多久」的基准。
     *
     * 刚启动的会话还没有心跳（心跳只在游玩页发），空闲判定必须从启动时刻算起。
     */
    @Override
    public java.time.Instant startedAt(String gameId) {
        if (!available()) {
            return null;
        }
        String out = systemctl("show", unitName(gameId),
                "--property=ActiveEnterTimestamp", "--timestamp=unix").strip();
        int eq = out.indexOf('=');
        if (eq < 0) {
            return null;
        }
        String v = out.substring(eq + 1).strip();
        if (v.isEmpty() || v.equals("0")) {
            return null;
        }
        if (v.startsWith("@")) {
            v = v.substring(1);
        }
        try {
            long secs = Long.parseLong(v);
            return secs > 0 ? java.time.Instant.ofEpochSecond(secs) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public SessionService.SessionStatus start(String gameId, int port) {
        if (!available()) {
            return status(gameId, port);
        }
        write(intentMarker(gameId));
        clear(stoppedMarker(gameId));
        systemctl("start", unitName(gameId));
        return status(gameId, port);
    }

    @Override
    public SessionService.SessionStatus stop(String gameId, int port) {
        if (!available()) {
            return status(gameId, port);
        }
        clear(intentMarker(gameId));
        systemctl("stop", unitName(gameId));
        // 主动停止后清掉 failed 状态：被 SIGTERM 结束时 systemd 会记成失败，
        // 不清的话下次打开详情页会误判成「游戏崩溃」
        systemctl("reset-failed", unitName(gameId));
        write(stoppedMarker(gameId));
        return status(gameId, port);
    }

    private Path intentMarker(String gameId) {
        return paths.dataDir().resolve("session-intent").resolve(gameId + ".started");
    }

    private Path stoppedMarker(String gameId) {
        return paths.dataDir().resolve("session-intent").resolve(gameId + ".stopped");
    }

    private void write(Path p) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, String.valueOf(System.currentTimeMillis()));
        } catch (IOException e) {
            log.warn("写会话标记失败 {}: {}", p, e.toString());
        }
    }

    private void clear(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("清会话标记失败 {}: {}", p, e.toString());
        }
    }

    private String systemctl(String action, String unit) {
        CommandRunner.Result r = runner.exec(List.of("systemctl", "--user", action, unit),
                SYSTEMCTL_TIMEOUT);
        return r.output().strip();
    }

    private String systemctl(String action, String unit, String... extra) {
        List<String> cmd = new ArrayList<>(List.of("systemctl", "--user", action, unit));
        cmd.addAll(List.of(extra));
        CommandRunner.Result r = runner.exec(cmd, SYSTEMCTL_TIMEOUT);
        return r.output().strip();
    }

    private boolean available() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    /**
     * 显式配置优先；未配置返回 null，由前端按「当前页面主机名 + 游戏端口」拼。
     *
     * 带查询串的地址（Moonlight 的 stream.html?hostId=..&appId=..）不能再补 "/"。
     */
    private String streamBaseUrl(int port) {
        if (streamBaseUrl == null || streamBaseUrl.isBlank()) {
            return null;
        }
        if (streamBaseUrl.contains("?") || streamBaseUrl.endsWith("/")) {
            return streamBaseUrl;
        }
        return streamBaseUrl + "/";
    }
}
