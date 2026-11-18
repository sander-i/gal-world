/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.i18n.I18n;
import io.github.sander_i.domain.Game;
import io.github.sander_i.repo.GameRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 库页 Hero 横幅的选片逻辑。
 *
 * 优先级：
 *  1. 最近玩过的（7 天内），但只在「继续游玩」有意义时才推荐 —— 即会话没在跑
 *  2. 否则取累计时长最高的
 *  3. 都没有就取最近添加的
 */
@Service
public class HeroService {

    private static final Duration RECENT_WINDOW = Duration.ofDays(7);

    private final GameRepository games;
    private final PlaytimeService playtime;
    private final GameScanService scan;
    private final SessionService runtime;

    public HeroService(GameRepository games, PlaytimeService playtime,
                       GameScanService scan, SessionService runtime) {
        this.games = games;
        this.playtime = playtime;
        this.scan = scan;
        this.runtime = runtime;
    }

    public record Hero(String gameId, String name, String coverPath, String reason,
                       long totalSeconds, Instant lastPlayed, String state) {
    }

    public Optional<Hero> pick() {
        List<Game> all = games.findAll();
        if (all.isEmpty()) {
            // 门户刚起、还没人访问过列表时 DB 是空的，先同步一次磁盘
            scan.syncAll();
            all = games.findAll();
        }
        if (all.isEmpty()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        // 一次批量取全部游戏的时长/最后游玩，避免每游戏各查几次库
        var stats = playtime.summaryByGame(all.stream().map(Game::getId).toList());

        // 1) 最近玩过（7 天内）
        record Recent(Game game, Instant at) {
        }
        Optional<Recent> recent = all.stream()
                .map(g -> {
                    Instant at = lastPlayed(g.getId(), stats);
                    if (at == null
                            || Duration.between(at, now).compareTo(RECENT_WINDOW) > 0) {
                        return null;
                    }
                    return new Recent(g, at);
                })
                .filter(java.util.Objects::nonNull)
                .max(Comparator.comparing(Recent::at));

        if (recent.isPresent()) {
            Recent r = recent.get();
            return Optional.of(hero(r.game(), I18n.t("最近游玩"), r.at(), stats));
        }

        // 2) 时长最高
        Optional<Game> most = all.stream()
                .filter(g -> totalSeconds(g.getId(), stats) > 0)
                .max(Comparator.comparingLong(g -> totalSeconds(g.getId(), stats)));
        if (most.isPresent()) {
            return Optional.of(hero(most.get(), I18n.t("游玩时间最长"), null, stats));
        }

        // 3) 最近添加
        Optional<Game> newest = all.stream()
                .max(Comparator.comparing(g -> g.getCreatedAt() == null ? Instant.EPOCH : g.getCreatedAt()));
        return newest.map(g -> hero(g, I18n.t("新加入"), null, stats));
    }

    private long totalSeconds(String gameId,
                              java.util.Map<String, PlaytimeService.PlaytimeSummary> stats) {
        var s = stats.get(gameId);
        return s == null ? 0 : s.totalSeconds();
    }

    private Instant lastPlayed(String gameId,
                               java.util.Map<String, PlaytimeService.PlaytimeSummary> stats) {
        var s = stats.get(gameId);
        return s == null ? null : s.lastPlayed();
    }

    private Hero hero(Game g, String reason, Instant lastPlayed,
                      java.util.Map<String, PlaytimeService.PlaytimeSummary> stats) {
        long total = totalSeconds(g.getId(), stats);
        String state = runtime.status(g.getId(), g.getPort()).state().name();
        return new Hero(g.getId(), g.getName(), g.getCoverPath(), reason, total, lastPlayed, state);
    }
}
