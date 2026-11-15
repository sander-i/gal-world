/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import com.galworld.domain.PlaySession;
import com.galworld.repo.PlaySessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 游玩时长统计。
 *
 * 口径：**只在游玩页且页面可见时才算**，由游玩页心跳驱动。
 *  - 首个心跳开一条记录
 *  - 每 20s 心跳刷新 lastBeatAt
 *  - 切到别的标签 / 最小化 → 页面停发心跳 → 超过 IDLE_GAP 视为挂机，时长截到最后心跳
 *  - 离开游玩页（返回库、关页面）→ 主动调 leave，立刻结算
 *  - 会话停止时兜底关闭
 *
 * 因此挂机、后台放着都不计时，只算真正在看的时间。
 */
@Service
public class PlaytimeService {

    private static final Logger log = LoggerFactory.getLogger(PlaytimeService.class);

    /** 超过这个间隔没心跳，就把记录截到最后一次心跳 */
    static final Duration IDLE_GAP = Duration.ofSeconds(45);

    private final PlaySessionRepository repo;

    public PlaytimeService(PlaySessionRepository repo) {
        this.repo = repo;
    }

    public record PlaytimeSummary(long totalSeconds, long todaySeconds, int sessions, Instant lastPlayed) {
    }

    @Transactional
    public void heartbeat(String gameId) {
        Instant now = Instant.now();
        PlaySession open = repo.findFirstByGameIdAndEndedAtIsNullOrderByStartedAtDesc(gameId)
                .orElse(null);

        if (open == null) {
            open = new PlaySession();
            open.setGameId(gameId);
            open.setStartedAt(now);
            open.setLastBeatAt(now);
            open.setSeconds(0);
            repo.save(open);
            log.debug("开始记录游玩 {}", gameId);
            return;
        }

        // 心跳断了很久才回来：先把旧的结算掉，再开新记录
        if (Duration.between(open.getLastBeatAt(), now).compareTo(IDLE_GAP) > 0) {
            closeAt(open, open.getLastBeatAt());
            PlaySession fresh = new PlaySession();
            fresh.setGameId(gameId);
            fresh.setStartedAt(now);
            fresh.setLastBeatAt(now);
            repo.save(fresh);
            return;
        }

        open.setLastBeatAt(now);
        repo.save(open);
    }

    /** 会话停止/门户启动时兜底结算，把遗留的进行中记录关掉 */
    @Transactional
    public void closeOpen(String gameId) {
        List<PlaySession> open = repo.findByGameIdAndEndedAtIsNull(gameId);
        for (PlaySession s : open) {
            closeAt(s, s.getLastBeatAt());
        }
    }

    @Transactional
    public void closeAllOpen() {
        repo.findAll().stream()
                .filter(s -> s.getEndedAt() == null)
                .forEach(s -> closeAt(s, s.getLastBeatAt()));
    }

    @Transactional(readOnly = true)
    public PlaytimeSummary summary(String gameId) {
        Instant now = Instant.now();
        long closed = repo.sumClosedSeconds(gameId);
        long live = repo.findByGameIdAndEndedAtIsNull(gameId).stream()
                .mapToLong(s -> Math.max(0, Duration.between(s.getStartedAt(), now).toSeconds()))
                .sum();
        long today = todaySeconds(gameId, now);
        Instant last = repo.findFirstByGameIdAndEndedAtIsNotNullOrderByEndedAtDesc(gameId)
                .map(PlaySession::getEndedAt)
                .orElse(null);
        return new PlaytimeSummary(closed + live, today, (int) repo.countByGameIdAndEndedAtIsNotNull(gameId), last);
    }

    /** 今日已结束的时长（按自然日） */
    @Transactional(readOnly = true)
    public long todaySeconds(String gameId, Instant now) {
        Instant dayStart = now.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        long total = 0;
        for (PlaySession s : repo.findByGameIdOrderByStartedAtDesc(gameId)) {
            if (s.getEndedAt() == null || s.getEndedAt().isBefore(dayStart)) {
                continue;
            }
            Instant from = s.getStartedAt().isBefore(dayStart) ? dayStart : s.getStartedAt();
            total += Math.max(0, Duration.between(from, s.getEndedAt()).toSeconds());
        }
        return total;
    }

    /**
     * 当前这次会话的心跳时间；没有进行中的记录时返回 null。
     *
     * 不能用 {@code max(lastBeatAt)}：那会取到历史会话（可能是几天前）的心跳，
     * 于是刚启动、还没进游玩页的游戏会立刻被判成「空闲很久」而被自动停掉。
     */
    public Instant lastBeat(String gameId) {
        return repo.findFirstByGameIdAndEndedAtIsNullOrderByStartedAtDesc(gameId)
                .map(PlaySession::getLastBeatAt)
                .orElse(null);
    }

    /** 进行中的会话是什么时候开的；没有记录返回 null */
    public Instant sessionStartedAt(String gameId) {
        return repo.findFirstByGameIdAndEndedAtIsNullOrderByStartedAtDesc(gameId)
                .map(PlaySession::getStartedAt)
                .orElse(null);
    }

    public long totalAllSeconds() {
        return repo.sumAllClosedSeconds();
    }

    private void closeAt(PlaySession s, Instant end) {
        Instant safeEnd = end.isBefore(s.getStartedAt()) ? s.getStartedAt() : end;
        s.setEndedAt(safeEnd);
        s.setSeconds(Math.max(0, Duration.between(s.getStartedAt(), safeEnd).toSeconds()));
        repo.save(s);
    }
}
