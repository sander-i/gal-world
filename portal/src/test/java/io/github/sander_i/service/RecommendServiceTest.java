/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.domain.Game;
import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.repo.PlaySessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RecommendServiceTest {

    private GameRepository games;
    private PlaytimeService playtime;
    private RecommendService service;

    private Game game(String id, String name, int createdAtOffset) {
        Game g = new Game();
        g.setId(id);
        g.setName(name);
        g.setPort(9000);
        g.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(createdAtOffset));
        return g;
    }

    @BeforeEach
    void setUp() {
        games = mock(GameRepository.class);
        PlaySessionRepository sessions = mock(PlaySessionRepository.class);
        playtime = mock(PlaytimeService.class);
        GameScanService scan = mock(GameScanService.class);

        when(games.findAll()).thenReturn(List.of(
                game("a", "A", 10), game("b", "B", 20), game("c", "C", 30)));
        when(sessions.findFirstByGameIdAndEndedAtIsNotNullOrderByEndedAtDesc(anyString()))
                .thenReturn(Optional.empty());
        when(playtime.summary(anyString()))
                .thenReturn(new PlaytimeService.PlaytimeSummary(0, 0, 0, null));
        service = new RecommendService(games, sessions, playtime, scan);
    }

    @Test
    void returnsAtMostLimit() {
        assertEquals(2, service.recommend(2, null).size());
    }

    @Test
    void excludesGivenGame() {
        List<Game> out = service.recommend(10, "a");
        assertEquals(2, out.size());
        assertTrue(out.stream().noneMatch(g -> g.getId().equals("a")));
    }

    @Test
    void newestFirstWhenNoPlayHistory() {
        List<Game> out = service.recommend(10, null);
        assertEquals("c", out.get(0).getId());
        assertEquals("a", out.get(2).getId());
    }

    @Test
    void mostPlayedRanksFirst() {
        when(playtime.summary("a"))
                .thenReturn(new PlaytimeService.PlaytimeSummary(5000, 0, 3, null));
        when(playtime.summary("b"))
                .thenReturn(new PlaytimeService.PlaytimeSummary(9000, 0, 4, null));
        List<Game> out = service.recommend(10, null);
        assertEquals("b", out.get(0).getId());
        assertEquals("a", out.get(1).getId());
    }

    @Test
    void clampsLimitToRange() {
        assertEquals(3, service.recommend(0, null).size());
        assertEquals(3, service.recommend(999, null).size());
    }
}
