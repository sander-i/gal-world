/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.domain.Game;
import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.repo.PlaySessionRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 首页「为你推荐」的候选排序。
 *
 * 当前占位策略（与 HeroService 一致）：
 *   1. 最近 7 天玩过的，最近玩的在前
 *   2. 没玩过任何游戏的，按累计时长降序
 *   3. 其余按加入时间降序
 *
 * 排序规则集中在 {@link #rank}，替换成自定义推荐算法时只改这一个方法，
 * 控制器、接口契约、前端都不用动。
 */
@Service
public class RecommendService {

    /** 默认每次返回条数 */
    public static final int DEFAULT_LIMIT = 10;
    /** 上限，防止前端传大值 */
    public static final int MAX_LIMIT = 50;

    private static final Duration RECENT_WINDOW = Duration.ofDays(7);

    private final GameRepository games;
    private final PlaySessionRepository sessions;
    private final PlaytimeService playtime;
    private final GameScanService scan;

    public RecommendService(GameRepository games, PlaySessionRepository sessions,
                            PlaytimeService playtime, GameScanService scan) {
        this.games = games;
        this.sessions = sessions;
        this.playtime = playtime;
        this.scan = scan;
    }

    /**
     * @param limit          返回条数，超出 [1, MAX_LIMIT] 会被钳制
     * @param excludeGameId  要排除的游戏（通常是 Hero 位已占用的那款），可为 null
     */
    public List<Game> recommend(int limit, String excludeGameId) {
        // limit <= 0 视为「没传」，走默认值；过大则钳到上限
        int n = limit <= 0 ? DEFAULT_LIMIT : Math.min(MAX_LIMIT, limit);
        List<Game> all = games.findAll();
        if (all.isEmpty()) {
            scan.syncAll();
            all = games.findAll();
        }
        if (all.isEmpty()) {
            return List.of();
        }
        String exclude = excludeGameId == null ? "" : excludeGameId.strip();
        List<Game> candidates = new ArrayList<>();
        for (Game g : all) {
            if (!exclude.isEmpty() && exclude.equals(g.getId())) {
                continue;
            }
            candidates.add(g);
        }
        return rank(candidates).stream().limit(n).toList();
    }

    /** 排序规则集中在这里，替换算法时只改本方法 */
    List<Game> rank(List<Game> candidates) {
        Instant now = Instant.now();
        return candidates.stream()
                .sorted((x, y) -> compare(x, y, now))
                .toList();
    }

    private int compare(Game x, Game y, Instant now) {
        // 1) 最近玩过优先，按最后游玩时间降序
        Instant lx = lastPlayed(x.getId(), now);
        Instant ly = lastPlayed(y.getId(), now);
        if (lx != null || ly != null) {
            if (lx == null) return 1;
            if (ly == null) return -1;
            int c = ly.compareTo(lx);
            if (c != 0) return c;
        }
        // 2) 累计时长降序
        long sx = playtime.summary(x.getId()).totalSeconds();
        long sy = playtime.summary(y.getId()).totalSeconds();
        if (sx != sy) return Long.compare(sy, sx);
        // 3) 加入时间降序
        Instant cx = x.getCreatedAt() == null ? Instant.EPOCH : x.getCreatedAt();
        Instant cy = y.getCreatedAt() == null ? Instant.EPOCH : y.getCreatedAt();
        return cy.compareTo(cx);
    }

    /** 7 天内玩过才参与「最近游玩」排序，否则返回 null */
    private Instant lastPlayed(String gameId, Instant now) {
        return sessions.findFirstByGameIdAndEndedAtIsNotNullOrderByEndedAtDesc(gameId)
                .map(s -> s.getEndedAt())
                .filter(t -> Duration.between(t, now).compareTo(RECENT_WINDOW) <= 0)
                .orElse(null);
    }
}
