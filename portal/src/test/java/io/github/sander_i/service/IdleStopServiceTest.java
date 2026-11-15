/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.domain.Game;
import io.github.sander_i.domain.PlaySession;
import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.repo.PlaySessionRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 空闲自动停止的判定基准。
 *
 * 回归点：以前用「该游戏历史上最后一次心跳」，刚启动、还没进游玩页的会话
 * 会被立刻判成空闲数天而停掉（实测 9-nine 启动 20 秒即被杀）。
 */
class IdleStopServiceTest {

    private static final int THRESHOLD = 3;

    private Game game(String id) {
        Game g = new Game();
        g.setId(id);
        g.setPort(9001);
        return g;
    }

    private SettingsService settings() {
        SettingsService s = mock(SettingsService.class);
        when(s.idleStopMinutes()).thenReturn(THRESHOLD);
        return s;
    }

    private IdleStopService service(GameRepository games, PlaytimeService playtime,
                                    SessionService sessions) {
        return new IdleStopService(settings(), games, playtime, sessions);
    }

    private GameRepository gamesWith(Game g) {
        GameRepository repo = mock(GameRepository.class);
        when(repo.findAll()).thenReturn(List.of(g));
        return repo;
    }

    private SessionService running() {
        SessionService sessions = mock(SessionService.class);
        when(sessions.status(anyString(), anyInt())).thenReturn(
                new SessionService.SessionStatus("demo", SessionService.State.RUNNING,
                        "active", null, false));
        return sessions;
    }

    @Test
    void 刚启动没有心跳时按启动时间判定不停止() {
        Game g = game("demo");
        PlaytimeService playtime = mock(PlaytimeService.class);
        SessionService sessions = running();
        when(playtime.lastBeat("demo")).thenReturn(null);
        when(sessions.startedAt("demo")).thenReturn(Instant.now().minusSeconds(20));

        service(gamesWith(g), playtime, sessions).sweep();

        verify(sessions, never()).stop(anyString(), anyInt());
    }

    @Test
    void 没有心跳也拿不到启动时间时跳过这一轮() {
        Game g = game("demo");
        PlaytimeService playtime = mock(PlaytimeService.class);
        SessionService sessions = running();
        when(playtime.lastBeat("demo")).thenReturn(null);
        when(sessions.startedAt("demo")).thenReturn(null);

        service(gamesWith(g), playtime, sessions).sweep();

        verify(sessions, never()).stop(anyString(), anyInt());
    }

    @Test
    void 本次会话心跳超时则停止() {
        Game g = game("demo");
        PlaytimeService playtime = mock(PlaytimeService.class);
        SessionService sessions = running();
        when(playtime.lastBeat("demo")).thenReturn(Instant.now().minus(Duration.ofMinutes(10)));

        service(gamesWith(g), playtime, sessions).sweep();

        verify(sessions).stop("demo", 9001);
    }

    @Test
    void 启动时间超过阈值也会停止() {
        Game g = game("demo");
        PlaytimeService playtime = mock(PlaytimeService.class);
        SessionService sessions = running();
        when(playtime.lastBeat("demo")).thenReturn(null);
        when(sessions.startedAt("demo")).thenReturn(Instant.now().minus(Duration.ofMinutes(30)));

        service(gamesWith(g), playtime, sessions).sweep();

        verify(sessions).stop("demo", 9001);
    }

    @Test
    void 会话没在运行时不检查() {
        Game g = game("demo");
        PlaytimeService playtime = mock(PlaytimeService.class);
        SessionService sessions = mock(SessionService.class);
        when(sessions.status(anyString(), anyInt())).thenReturn(
                new SessionService.SessionStatus("demo", SessionService.State.STOPPED,
                        "inactive", null, false));

        service(gamesWith(g), playtime, sessions).sweep();

        verifyNoInteractions(playtime);
        verify(sessions, never()).stop(anyString(), anyInt());
    }

    @Test
    void PlaytimeService的lastBeat只取进行中的记录() {
        // 这是判定的数据来源：历史记录不能参与，否则新会话会被误判为空闲
        PlaySessionRepository repo = mock(PlaySessionRepository.class);
        PlaySession open = new PlaySession();
        open.setGameId("demo");
        open.setStartedAt(Instant.now().minusSeconds(5));
        open.setLastBeatAt(Instant.now());
        when(repo.findFirstByGameIdAndEndedAtIsNullOrderByStartedAtDesc("demo"))
                .thenReturn(Optional.of(open));

        PlaytimeService svc = new PlaytimeService(repo);

        assertEquals(open.getLastBeatAt(), svc.lastBeat("demo"));
        assertEquals(open.getStartedAt(), svc.sessionStartedAt("demo"));
        verify(repo, never()).lastBeatOf(anyString());
    }

    @Test
    void 没有进行中记录时lastBeat为null() {
        PlaySessionRepository repo = mock(PlaySessionRepository.class);
        when(repo.findFirstByGameIdAndEndedAtIsNullOrderByStartedAtDesc("demo"))
                .thenReturn(Optional.empty());

        PlaytimeService svc = new PlaytimeService(repo);

        assertNull(svc.lastBeat("demo"));
        assertNull(svc.sessionStartedAt("demo"));
    }
}
