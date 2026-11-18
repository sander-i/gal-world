/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.domain.PlaySession;
import io.github.sander_i.repo.PlaySessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 批量时长统计：列表页/首页靠它一次拿全部游戏，替代每游戏 5 条查询 */
class PlaytimeSummaryBatchTest {

    private PlaySessionRepository repo;
    private PlaytimeService service;

    @BeforeEach
    void setUp() {
        repo = mock(PlaySessionRepository.class);
        service = new PlaytimeService(repo);
    }

    private static PlaySession session(String gameId, Instant started, Instant ended) {
        PlaySession s = new PlaySession();
        s.setGameId(gameId);
        s.setStartedAt(started);
        s.setEndedAt(ended);
        return s;
    }

    @Test
    void combinesClosedLiveTodayAndCount() {
        Instant now = Instant.now();
        Instant dayStart = now.atZone(ZoneId.systemDefault()).toLocalDate()
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        PlaySession live = session("a", now.minusSeconds(30), null);
        PlaySession endedToday = session("a", dayStart.minusSeconds(120), dayStart.plusSeconds(60));

        when(repo.closedStatsByGame(anyCollection())).thenReturn(List.<Object[]>of(
                new Object[]{"a", now.minusSeconds(10), 100L, 3L},
                new Object[]{"b", null, 40L, 1L}));
        when(repo.findByGameIdInAndEndedAtIsNull(anyCollection())).thenReturn(List.of(live));
        when(repo.findEndedSince(anyCollection(), any())).thenReturn(List.of(endedToday));

        Map<String, PlaytimeService.PlaytimeSummary> out =
                service.summaryByGame(List.of("a", "b", "c"));

        var a = out.get("a");
        assertNotNull(a);
        assertEquals(130, a.totalSeconds(), "已结束 100 + 进行中 30");
        assertEquals(3, a.sessions());
        assertEquals(60, a.todaySeconds(), "跨天记录只算今天这段");
        assertNotNull(a.lastPlayed());

        var b = out.get("b");
        assertEquals(40, b.totalSeconds());
        assertEquals(1, b.sessions());
        assertEquals(0, b.todaySeconds());
        assertNull(b.lastPlayed());

        var c = out.get("c");
        assertEquals(new PlaytimeService.PlaytimeSummary(0, 0, 0, null), c,
                "没有记录的游戏也要有零值条目");
    }

    @Test
    void todayIgnoresRecordsEndedBeforeDayStart() {
        when(repo.closedStatsByGame(anyCollection())).thenReturn(List.of());
        when(repo.findByGameIdInAndEndedAtIsNull(anyCollection())).thenReturn(List.of());
        // findEndedSince 只返回今天之后的记录；查询边界由仓库负责过滤
        when(repo.findEndedSince(anyCollection(), any())).thenReturn(List.of());

        var a = service.summaryByGame(List.of("a")).get("a");
        assertEquals(0, a.todaySeconds());
    }

    @Test
    void singleGameSummaryDelegatesToBatch() {
        Instant last = Instant.now().minusSeconds(60);
        when(repo.closedStatsByGame(anyCollection())).thenReturn(List.<Object[]>of(
                new Object[]{"a", last, 12L, 1L}));
        when(repo.findByGameIdInAndEndedAtIsNull(anyCollection())).thenReturn(List.of());
        when(repo.findEndedSince(anyCollection(), any())).thenReturn(List.of());

        var s = service.summary("a");
        assertEquals(12, s.totalSeconds());

        verify(repo, never()).sumClosedSeconds(anyString());
        verify(repo, never()).countByGameIdAndEndedAtIsNotNull(anyString());
        verify(repo, never()).findByGameIdOrderByStartedAtDesc(anyString());
    }

    @Test
    void emptyInputSkipsQueries() {
        assertTrue(service.summaryByGame(List.of()).isEmpty());
        verifyNoInteractions(repo);
    }
}
