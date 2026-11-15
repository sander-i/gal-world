/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.domain.Game;
import io.github.sander_i.platform.CommandRunner;
import io.github.sander_i.repo.GameRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CanvasCalibrationServiceTest {

    private static final String TREE = """
             0x1800000 (has no name): ()  1x1+0+0  +0+0
             0xa00005 "天使☆纷扰 RE-BOOT!": ("game.exe" "game.exe")  968x593+156+63  +156+63
            """;

    private Game game() {
        Game g = new Game();
        g.setId("demo");
        g.setName("demo");
        g.setWidth(1280);
        g.setHeight(720);
        g.setDisplayNum(2);
        g.setPort(9001);
        return g;
    }

    private CanvasCalibrationService service(GameRepository games, SessionService sessions,
                                             GameScanService scan, SettingsService settings,
                                             CommandRunner runner) {
        return new CanvasCalibrationService(games, sessions, scan, settings, runner);
    }

    @Test
    void 手动校准写回画布并重启() throws Exception {
        Game g = game();
        GameRepository games = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        GameScanService scan = mock(GameScanService.class);
        SettingsService settings = mock(SettingsService.class);
        CommandRunner runner = mock(CommandRunner.class);

        when(games.findById("demo")).thenReturn(Optional.of(g));
        when(sessions.status("demo", 9001)).thenReturn(new SessionService.SessionStatus(
                "demo", SessionService.State.RUNNING, "active", null, false));
        when(runner.exec(anyList(), any(Duration.class)))
                .thenReturn(new CommandRunner.Result(0, TREE));
        when(sessions.start("demo", 9001)).thenReturn(new SessionService.SessionStatus(
                "demo", SessionService.State.RUNNING, "active", null, false));

        var r = service(games, sessions, scan, settings, runner).calibrate("demo", true);

        assertEquals("restarted", r.status());
        assertEquals(992, g.getWidth());
        assertEquals(624, g.getHeight());
        verify(games).save(g);
        verify(scan).writeEnv(g);
        verify(sessions).stop("demo", 9001);
        verify(sessions).start("demo", 9001);
    }

    @Test
    void 自动校准关闭时不产生任何副作用() {
        Game g = game();
        GameRepository games = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        SettingsService settings = mock(SettingsService.class);
        CommandRunner runner = mock(CommandRunner.class);

        when(games.findById("demo")).thenReturn(Optional.of(g));
        when(settings.canvasAutoCalibrate()).thenReturn(false);

        var r = service(games, sessions, mock(GameScanService.class), settings, runner)
                .calibrate("demo", false);

        assertEquals("disabled", r.status());
        verifyNoInteractions(sessions, runner);
    }

    @Test
    void 会话未运行时只返回提示() {
        GameRepository games = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        CommandRunner runner = mock(CommandRunner.class);

        when(games.findById("demo")).thenReturn(Optional.of(game()));
        when(sessions.status("demo", 9001)).thenReturn(new SessionService.SessionStatus(
                "demo", SessionService.State.STOPPED, "inactive", null, false));

        var r = service(games, sessions, mock(GameScanService.class),
                mock(SettingsService.class), runner).calibrate("demo", true);

        assertEquals("not-running", r.status());
        verifyNoInteractions(runner);
    }

    @Test
    void 读不到窗口时不改画布() {
        Game g = game();
        GameRepository games = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        CommandRunner runner = mock(CommandRunner.class);

        when(games.findById("demo")).thenReturn(Optional.of(g));
        when(sessions.status("demo", 9001)).thenReturn(new SessionService.SessionStatus(
                "demo", SessionService.State.RUNNING, "active", null, false));
        when(runner.exec(anyList(), any(Duration.class)))
                .thenReturn(new CommandRunner.Result(1, ""));

        var r = service(games, sessions, mock(GameScanService.class),
                mock(SettingsService.class), runner).calibrate("demo", true);

        assertEquals("no-window", r.status());
        assertEquals(1280, g.getWidth());
        verify(games, never()).save(any());
        verify(sessions, never()).stop(anyString(), anyInt());
        verify(sessions, never()).start(anyString(), anyInt());
    }

    @Test
    void 用户手动指定过分辨率后自动校准跳过() {
        Game g = game();
        GameRepository games = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        SettingsService settings = mock(SettingsService.class);
        CommandRunner runner = mock(CommandRunner.class);

        when(games.findById("demo")).thenReturn(Optional.of(g));
        when(settings.canvasAutoCalibrate()).thenReturn(true);

        var svc = service(games, sessions, mock(GameScanService.class), settings, runner);
        svc.reset("demo");
        var r = svc.calibrate("demo", false);

        assertEquals("ok", r.status());
        assertEquals(1280, g.getWidth());
        verifyNoInteractions(runner, sessions);
    }

    @Test
    void 写gameenv失败时回滚并返回失败() throws Exception {
        Game g = game();
        GameRepository games = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        GameScanService scan = mock(GameScanService.class);
        CommandRunner runner = mock(CommandRunner.class);

        when(games.findById("demo")).thenReturn(Optional.of(g));
        when(sessions.status("demo", 9001)).thenReturn(new SessionService.SessionStatus(
                "demo", SessionService.State.RUNNING, "active", null, false));
        when(runner.exec(anyList(), any(Duration.class)))
                .thenReturn(new CommandRunner.Result(0, TREE));
        doThrow(new IOException("disk full")).when(scan).writeEnv(g);

        var r = service(games, sessions, scan, mock(SettingsService.class), runner)
                .calibrate("demo", true);

        assertEquals("failed", r.status());
        assertEquals(1280, g.getWidth());
        assertEquals(720, g.getHeight());
        verify(games, never()).save(any());
        verify(sessions, never()).stop(anyString(), anyInt());
    }

    @Test
    void 重启后状态不是运行时报告失败() {
        Game g = game();
        GameRepository games = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        CommandRunner runner = mock(CommandRunner.class);

        when(games.findById("demo")).thenReturn(Optional.of(g));
        when(sessions.status("demo", 9001)).thenReturn(new SessionService.SessionStatus(
                "demo", SessionService.State.RUNNING, "active", null, false));
        when(runner.exec(anyList(), any(Duration.class)))
                .thenReturn(new CommandRunner.Result(0, TREE));
        when(sessions.start("demo", 9001)).thenReturn(new SessionService.SessionStatus(
                "demo", SessionService.State.FAILED, "failed", null, false));

        var r = service(games, sessions, mock(GameScanService.class),
                mock(SettingsService.class), runner).calibrate("demo", true);

        assertEquals("failed", r.status());
        assertEquals(992, g.getWidth());
    }
}
