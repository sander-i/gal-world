/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.domain.Game;
import io.github.sander_i.repo.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
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

    private static PlaytimeService.PlaytimeSummary summary(long total, Instant lastPlayed) {
        return new PlaytimeService.PlaytimeSummary(total, 0, 0, lastPlayed);
    }

    private void stats(Map<String, PlaytimeService.PlaytimeSummary> byGame) {
        when(playtime.summaryByGame(anyCollection())).thenReturn(byGame);
    }

    @BeforeEach
    void setUp() {
        games = mock(GameRepository.class);
        playtime = mock(PlaytimeService.class);
        GameScanService scan = mock(GameScanService.class);

        when(games.findAll()).thenReturn(List.of(
                game("a", "A", 10), game("b", "B", 20), game("c", "C", 30)));
        stats(Map.of(
                "a", summary(0, null), "b", summary(0, null), "c", summary(0, null)));
        service = new RecommendService(games, playtime, scan);
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
        stats(Map.of(
                "a", summary(5000, null),
                "b", summary(9000, null),
                "c", summary(0, null)));
        List<Game> out = service.recommend(10, null);
        assertEquals("b", out.get(0).getId());
        assertEquals("a", out.get(1).getId());
    }

    @Test
    void recentlyPlayedRanksAbovePlaytime() {
        Instant yesterday = Instant.now().minusSeconds(24 * 3600);
        stats(Map.of(
                "a", summary(0, yesterday),
                "b", summary(9999, null),
                "c", summary(0, null)));
        List<Game> out = service.recommend(10, null);
        assertEquals("a", out.get(0).getId());
        assertEquals("b", out.get(1).getId());
    }

    @Test
    void oldSessionDoesNotCountAsRecentlyPlayed() {
        Instant longAgo = Instant.now().minusSeconds(30L * 24 * 3600);
        stats(Map.of(
                "a", summary(10, longAgo),
                "b", summary(5000, null),
                "c", summary(0, null)));
        List<Game> out = service.recommend(10, null);
        assertEquals("b", out.get(0).getId(), "超出 7 天窗口的游玩不参与最近游玩排序");
    }

    /** 排序在内存里做：一次批量统计，绝不能再回到「每次比较查一次库」 */
    @Test
    void sortsWithSingleBatchQuery() {
        service.recommend(10, null);
        verify(playtime, times(1)).summaryByGame(anyCollection());
        verify(playtime, never()).summary(anyString());
    }

    @Test
    void clampsLimitToRange() {
        assertEquals(3, service.recommend(0, null).size());
        assertEquals(3, service.recommend(999, null).size());
    }
}
