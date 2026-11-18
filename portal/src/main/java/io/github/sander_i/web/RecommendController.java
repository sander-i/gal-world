/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.domain.Game;
import io.github.sander_i.i18n.I18n;
import io.github.sander_i.service.CompanyService;
import io.github.sander_i.service.GameMediaService;
import io.github.sander_i.service.PlaytimeService;
import io.github.sander_i.service.RecommendService;
import io.github.sander_i.service.SessionService;
import io.github.sander_i.service.TagsAndMeta;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 首页「为你推荐」。返回顺序即展示顺序，前端不排序不补位。
 */
@RestController
public class RecommendController {

    public record RecommendView(
            String gameId,
            String name,
            List<String> tags,
            List<GameView.CompanyView> companies,
            List<GameMediaService.MediaItem> covers,
            String state,
            long totalSeconds,
            String reason) {
    }

    private final RecommendService recommend;
    private final TagsAndMeta meta;
    private final CompanyService companies;
    private final GameMediaService media;
    private final PlaytimeService playtime;
    private final SessionService sessions;

    public RecommendController(RecommendService recommend, TagsAndMeta meta,
                               CompanyService companies, GameMediaService media,
                               PlaytimeService playtime, SessionService sessions) {
        this.recommend = recommend;
        this.meta = meta;
        this.companies = companies;
        this.media = media;
        this.playtime = playtime;
        this.sessions = sessions;
    }

    @GetMapping("/api/recommend")
    public List<RecommendView> recommend(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String exclude) {
        List<Game> picked = recommend.recommend(limit, exclude);
        if (picked.isEmpty()) {
            return List.of();
        }
        List<String> ids = picked.stream().map(Game::getId).toList();
        var tagMap = meta.tagsByGame(ids);
        var companyMap = companies.byGame(ids);
        var coverMap = media.itemsByGames(ids, "cover");
        var playtimes = playtime.summaryByGame(ids);
        var states = sessions.statuses(picked);
        return picked.stream()
                .map(g -> toView(g, tagMap.get(g.getId()), companyMap.get(g.getId()),
                        coverMap.get(g.getId()), playtimes.get(g.getId()), states.get(g.getId())))
                .toList();
    }

    private RecommendView toView(Game g, List<io.github.sander_i.domain.Tag> tags,
                                 List<io.github.sander_i.domain.Company> companies,
                                 List<GameMediaService.MediaItem> covers,
                                 PlaytimeService.PlaytimeSummary playtime,
                                 SessionService.SessionStatus status) {
        var comps = (companies == null ? List.<io.github.sander_i.domain.Company>of() : companies)
                .stream()
                .map(c -> new GameView.CompanyView(c.getId(), c.getName(),
                        c.getAlias() == null ? "" : c.getAlias(),
                        c.getWebsite() == null ? "" : c.getWebsite(),
                        c.getDescription() == null ? "" : c.getDescription()))
                .toList();
        long total = playtime == null ? 0 : playtime.totalSeconds();
        String state = status == null ? "UNKNOWN" : status.state().name();
        return new RecommendView(
                g.getId(), g.getName(),
                tags == null ? List.of() : tags.stream().map(t -> t.getName()).toList(),
                comps, covers == null ? List.of() : covers,
                state, total, reasonOf(total, state));
    }

    private String reasonOf(long totalSeconds, String state) {
        if ("RUNNING".equals(state)) return I18n.t("正在游玩");
        if (totalSeconds > 0) return I18n.t("继续游玩");
        return I18n.t("新加入");
    }
}
