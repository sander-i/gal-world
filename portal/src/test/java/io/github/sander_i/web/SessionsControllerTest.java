/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.domain.Game;
import io.github.sander_i.platform.CommandRunner;
import io.github.sander_i.platform.GameWindowProbe;
import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.service.SessionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionsControllerTest {

    private Game game(String id, int port, int display) {
        Game g = new Game();
        g.setId(id);
        g.setPort(port);
        g.setDisplayNum(display);
        return g;
    }

    @Test
    void returnsOnlyRequestedGamesAsMap() {
        GameRepository repo = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        GameWindowProbe probe = mock(GameWindowProbe.class);
        when(repo.findAllById(anyCollection())).thenReturn(List.of(
                game("g1", 9001, 2), game("g2", 9002, 3)));
        when(sessions.statuses(anyList())).thenReturn(Map.of(
                "g1", new SessionService.SessionStatus("g1", SessionService.State.RUNNING,
                        "active", "https://host/stream", false),
                "g2", new SessionService.SessionStatus("g2", SessionService.State.STOPPED,
                        "inactive", null, false)));

        var controller = new SessionsController(repo, sessions,
                mock(CommandRunner.class), probe);
        var out = controller.statuses("g1,g2,g1");

        assertEquals(2, out.size());
        assertEquals("RUNNING", out.get("g1").state());
        assertTrue(out.get("g1").streamReady());
        assertEquals("STOPPED", out.get("g2").state());
        // 窗口探测只对运行中的会话做
        verify(probe, times(1)).present(anyInt());
    }

    @Test
    void emptyIdsReturnsEmptyMap() {
        GameRepository repo = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        var controller = new SessionsController(repo, sessions,
                mock(CommandRunner.class), mock(GameWindowProbe.class));
        assertTrue(controller.statuses(",, ").isEmpty());
        verifyNoInteractions(repo, sessions);
    }
}
