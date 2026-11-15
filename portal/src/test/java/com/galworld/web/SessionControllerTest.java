/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.web;

import com.galworld.domain.Game;
import com.galworld.platform.CommandRunner;
import com.galworld.repo.GameRepository;
import com.galworld.service.SessionDiagnosticsService;
import com.galworld.service.SessionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class SessionControllerTest {

    private Game game(String id, int port) {
        Game g = new Game();
        g.setId(id);
        g.setName(id);
        g.setPort(port);
        return g;
    }

    @Test
    void sessionReportsStateAndStreamReadiness() {
        GameRepository repo = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        SessionDiagnosticsService diagnostics = mock(SessionDiagnosticsService.class);
        CommandRunner runner = mock(CommandRunner.class);

        when(repo.findById("demo")).thenReturn(Optional.of(game("demo", 9001)));
        when(sessions.status("demo", 9001)).thenReturn(new SessionService.SessionStatus(
                "demo", SessionService.State.RUNNING, "active", null, false));
        when(runner.portOpen(9001)).thenReturn(true);

        var controller = new SessionController(repo, sessions, diagnostics, runner);
        var body = controller.session("demo").getBody();

        assertNotNull(body);
        assertEquals("demo", body.id());
        assertEquals("RUNNING", body.state());
        assertFalse(body.crashed());
        assertTrue(body.streamReady());
    }

    @Test
    void explicitStreamUrlMeansReadyWithoutPortProbe() {
        GameRepository repo = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        CommandRunner runner = mock(CommandRunner.class);

        when(repo.findById("demo")).thenReturn(Optional.of(game("demo", 0)));
        when(sessions.status("demo", 0)).thenReturn(new SessionService.SessionStatus(
                "demo", SessionService.State.RUNNING, "running",
                "http://host/stream.html?appId=1", false));

        var controller = new SessionController(repo, sessions,
                mock(SessionDiagnosticsService.class), runner);
        var body = controller.session("demo").getBody();

        assertNotNull(body);
        assertTrue(body.streamReady());
        verify(runner, never()).portOpen(anyInt());
    }

    @Test
    void outOfRangePortIsNeverProbed() {
        GameRepository repo = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        CommandRunner runner = mock(CommandRunner.class);

        when(repo.findById("demo")).thenReturn(Optional.of(game("demo", 80)));
        when(sessions.status("demo", 80)).thenReturn(new SessionService.SessionStatus(
                "demo", SessionService.State.RUNNING, "active", null, false));

        var controller = new SessionController(repo, sessions,
                mock(SessionDiagnosticsService.class), runner);
        assertNotNull(controller.session("demo").getBody());
        verify(runner, never()).portOpen(anyInt());
    }

    @Test
    void sessionReturns404ForUnknownGame() {        GameRepository repo = mock(GameRepository.class);
        when(repo.findById("nope")).thenReturn(Optional.empty());
        var controller = new SessionController(repo, mock(SessionService.class),
                mock(SessionDiagnosticsService.class), mock(CommandRunner.class));
        assertEquals(404, controller.session("nope").getStatusCode().value());
    }

    @Test
    void diagnosticsDelegatesToService() {
        GameRepository repo = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        SessionDiagnosticsService diagnostics = mock(SessionDiagnosticsService.class);
        CommandRunner runner = mock(CommandRunner.class);

        when(repo.findById("demo")).thenReturn(Optional.of(game("demo", 9001)));
        var status = new SessionService.SessionStatus(
                "demo", SessionService.State.FAILED, "failed", null, true);
        when(sessions.status("demo", 9001)).thenReturn(status);
        var diag = new SessionDiagnosticsService.Diagnostics("demo", "FAILED", true,
                3, null, "游戏会话异常终止，退出码 3", List.of("boom"), "demo.log");
        when(diagnostics.of("demo", status)).thenReturn(diag);

        var controller = new SessionController(repo, sessions, diagnostics, runner);
        assertEquals(diag, controller.diagnostics("demo").getBody());
    }
}
