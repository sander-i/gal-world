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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class PlayTelemetryServiceTest {

    @TempDir
    Path root;

    private GameRepository repo(Game g) {
        GameRepository repo = mock(GameRepository.class);
        when(repo.findById("demo")).thenReturn(Optional.of(g));
        return repo;
    }

    private Game game() {
        Game g = new Game();
        g.setId("demo");
        g.setWidth(1280);
        g.setHeight(720);
        g.setDisplayNum(3);
        g.setPort(9003);
        return g;
    }

    private PlayTelemetryService service(GameRepository repo, SessionService sessions,
                                         CommandRunner runner) {
        return new PlayTelemetryService(new PathConfig(root.toString()), repo,
                sessions, runner, new ObjectMapper());
    }

    private String written() throws Exception {
        return Files.readString(root.resolve("data/logs/play-telemetry.log"));
    }

    @Test
    void 普通采样写一行且不抓服务器快照() throws Exception {
        SessionService sessions = mock(SessionService.class);
        CommandRunner runner = mock(CommandRunner.class);
        var svc = service(repo(game()), sessions, runner);

        boolean ok = svc.recordClient(Map.of("gameId", "demo", "event", "sample",
                "heapMB", 8.1, "decoded", 1000, "unknown", "x"), "127.0.0.1");

        assertTrue(ok);
        String line = written();
        assertTrue(line.contains("\"src\":\"client\""));
        assertTrue(line.contains("\"heapMB\":8.1"));
        assertFalse(line.contains("unknown"));
        assertFalse(line.contains("\"server\""));
        verifyNoInteractions(sessions, runner);
    }

    @Test
    void 未知游戏拒绝写入() {
        GameRepository repo = mock(GameRepository.class);
        when(repo.findById("nope")).thenReturn(Optional.empty());
        var svc = service(repo, mock(SessionService.class), mock(CommandRunner.class));

        assertFalse(svc.recordClient(Map.of("gameId", "nope", "event", "sample"), "x"));
    }

    @Test
    void 页面错误附带服务器快照() throws Exception {
        SessionService sessions = mock(SessionService.class);
        CommandRunner runner = mock(CommandRunner.class);
        when(sessions.status("demo", 9003)).thenReturn(new SessionService.SessionStatus(
                "demo", SessionService.State.RUNNING, "active", null, false));
        when(runner.exec(anyList(), any(Duration.class)))
                .thenReturn(new CommandRunner.Result(0, ""));
        var svc = service(repo(game()), sessions, runner);

        svc.recordClient(Map.of("gameId", "demo", "event", "pageerror",
                "message", "Aw, Snap"), "203.0.113.15");

        String line = written();
        assertTrue(line.contains("\"event\":\"pageerror\""));
        assertTrue(line.contains("\"state\":\"RUNNING\""));
        assertTrue(line.contains("\"canvas\":\"1280x720\""));
        assertTrue(line.contains("\"remote\":\"203.0.113.15\""));
        verify(runner).exec(anyList(), any(Duration.class));
    }

    @Test
    void 服务端断开事件也写快照() throws Exception {
        SessionService sessions = mock(SessionService.class);
        CommandRunner runner = mock(CommandRunner.class);
        when(sessions.status("demo", 9003)).thenReturn(new SessionService.SessionStatus(
                "demo", SessionService.State.RUNNING, "active", null, false));
        when(runner.exec(anyList(), any(Duration.class)))
                .thenReturn(new CommandRunner.Result(0, ""));
        var svc = service(repo(game()), sessions, runner);

        svc.recordServer("demo", "ws_close_abnormal", Map.of("code", 1006));

        String line = written();
        assertTrue(line.contains("\"src\":\"server\""));
        assertTrue(line.contains("ws_close_abnormal"));
        assertTrue(line.contains("\"code\":1006"));
    }
}
