/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.domain.PlaySession;
import io.github.sander_i.repo.PlaySessionRepository;
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
        return summaryByGame(List.of(gameId)).getOrDefault(gameId, EMPTY);
    }

    private static final PlaytimeSummary EMPTY = new PlaytimeSummary(0, 0, 0, null);

    /**
     * 批量统计：列表页、首页一次性拿到所有游戏的时长，替代每游戏 5 条查询。
     *
     * 固定 3 条查询：已结束聚合 / 进行中的记录 / 今日结束的记录。
     * 返回的 map 对每个请求的 id 都有值（无记录时是零值），调用方不用判空。
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, PlaytimeSummary> summaryByGame(
            java.util.Collection<String> gameIds) {
        java.util.Map<String, PlaytimeSummary> out = new java.util.HashMap<>();
        if (gameIds == null || gameIds.isEmpty()) {
            return out;
        }
        List<String> ids = gameIds.stream().filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (ids.isEmpty()) {
            return out;
        }
        Instant now = Instant.now();
        Instant dayStart = now.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();

        // 1) 已结束记录：最后游玩时间 + 累计秒数 + 场次
        java.util.Map<String, long[]> agg = new java.util.HashMap<>();
        java.util.Map<String, Instant> last = new java.util.HashMap<>();
        for (Object[] row : repo.closedStatsByGame(ids)) {
            String id = (String) row[0];
            last.put(id, (Instant) row[1]);
            agg.put(id, new long[]{((Number) row[2]).longValue(), ((Number) row[3]).longValue()});
        }
        // 2) 进行中的记录：按「现在 - 开始」实时计入
        for (PlaySession s : repo.findByGameIdInAndEndedAtIsNull(ids)) {
            long live = Math.max(0, Duration.between(s.getStartedAt(), now).toSeconds());
            long[] a = agg.computeIfAbsent(s.getGameId(), k -> new long[2]);
            a[0] += live;
        }
        // 3) 今日时长：只取今天结束的记录，不再把全部历史拉进内存遍历
        java.util.Map<String, Long> today = new java.util.HashMap<>();
        for (PlaySession s : repo.findEndedSince(ids, dayStart)) {
            // 查询与遍历之间可能有别的线程把 endedAt 置空（心跳开新记录时结算旧记录），
            // 实体是同一持久化上下文里的同一个对象，这里读到的可能是 null。
            if (s.getEndedAt() == null) {
                continue;
            }
            Instant from = s.getStartedAt().isBefore(dayStart) ? dayStart : s.getStartedAt();
            long secs = Math.max(0, Duration.between(from, s.getEndedAt()).toSeconds());
            today.merge(s.getGameId(), secs, Long::sum);
        }

        for (String id : ids) {
            long[] a = agg.getOrDefault(id, new long[2]);
            out.put(id, new PlaytimeSummary(a[0], today.getOrDefault(id, 0L),
                    (int) a[1], last.get(id)));
        }
        return out;
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
