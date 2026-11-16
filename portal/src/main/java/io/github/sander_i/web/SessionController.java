/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.platform.CommandRunner;
import io.github.sander_i.platform.GameWindowProbe;
import io.github.sander_i.platform.OsKind;
import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.service.SessionDiagnosticsService;
import io.github.sander_i.service.SessionService;
import io.github.sander_i.service.WindowGeometry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 游玩页专用的轻量会话接口。
 *
 * 为什么不复用 /api/games/{id}：那个接口会同步磁盘、查标签/公司/媒体、算时长，
 * 游玩页每 1-3 秒轮询一次，用它既慢又会让日志噪声变大。
 *
 * 两个接口都是只读 GET，不受写接口令牌影响。
 */
@RestController
@RequestMapping("/api/games")
public class SessionController {

    /** 游玩页轮询用的最小状态 */
    public record SessionStatusView(String id, String state, boolean crashed,
                                    String streamUrl, boolean streamReady,
                                    Boolean gameAlive) {
    }

    private final GameRepository games;
    private final SessionService sessions;
    private final SessionDiagnosticsService diagnostics;
    private final CommandRunner runner;
    private final GameWindowProbe windowProbe;

    public SessionController(GameRepository games, SessionService sessions,
                             SessionDiagnosticsService diagnostics, CommandRunner runner,
                             GameWindowProbe windowProbe) {
        this.games = games;
        this.sessions = sessions;
        this.diagnostics = diagnostics;
        this.runner = runner;
        this.windowProbe = windowProbe;
    }

    @GetMapping("/{id}/session")
    public ResponseEntity<SessionStatusView> session(@PathVariable String id) {
        return games.findById(id)
                .map(g -> {
                    var st = sessions.status(g.getId(), g.getPort());
                    // 配置了显式 streamUrl（Windows 的 Moonlight Web / 反向代理）
                    // 时没有「游戏端口」可探，直接视为就绪；否则探 Selkies 端口。
                    // 非 Linux（Windows/Moonlight 整机串流）没有每游戏端口可探，
                    // 也直接视为就绪；端口区间外不探测，避免非法端口抛异常。
                    boolean portOk = g.getPort() >= 9000 && g.getPort() <= 9100
                            && runner.portOpen(g.getPort());
                    boolean streamReady = st.streamUrl() != null
                            || OsKind.current() != OsKind.LINUX
                            || portOk;
                    return ResponseEntity.ok(new SessionStatusView(
                            g.getId(), st.state().name(), st.crashed(), st.streamUrl(),
                            streamReady, windowProbe.present(g.getDisplayNum())));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/session/diagnostics")
    public ResponseEntity<SessionDiagnosticsService.Diagnostics> diagnostics(@PathVariable String id) {
        return games.findById(id)
                .map(g -> ResponseEntity.ok(
                        diagnostics.of(g.getId(), sessions.status(g.getId(), g.getPort()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 游戏主窗口在串流画布上的矩形；未运行/测量不到时 404（游玩页退回旧行为）。 */
    @GetMapping("/{id}/window")
    public ResponseEntity<WindowGeometry> window(@PathVariable String id) {
        return games.findById(id)
                .map(g -> {
                    WindowGeometry geo = measureWindow(g.getDisplayNum());
                    return geo == null
                            ? ResponseEntity.notFound().<WindowGeometry>build()
                            : ResponseEntity.ok(geo);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private WindowGeometry measureWindow(int displayNum) {
        CommandRunner.Result r = runner.exec(
                List.of("xwininfo", "-display", ":" + displayNum, "-root", "-tree"),
                Duration.ofSeconds(3));
        return r == null || !r.ok() ? null : WindowGeometry.largest(r.output());
    }
}
