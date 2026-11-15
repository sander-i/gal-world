/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.repo.PlaySessionRepository;
import io.github.sander_i.repo.SettingRepository;
import io.github.sander_i.repo.TagRepository;
import io.github.sander_i.service.SettingsService;
import io.github.sander_i.service.TagsAndMeta;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 门户数据的 JSON 导出，用于整站备份。
 *
 * 直接拷 H2 的 mv.db 有风险（运行中文件可能正在写），
 * 导出成 JSON 既稳定又能人工查看/迁移。
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final GameRepository games;
    private final TagRepository tags;
    private final TagsAndMeta meta;
    private final PlaySessionRepository sessions;
    private final SettingRepository settings;
    private final SettingsService settingsService;
    private final io.github.sander_i.service.GameScanService scan;

    public ExportController(GameRepository games, TagRepository tags, TagsAndMeta meta,
                            PlaySessionRepository sessions, SettingRepository settings,
                            SettingsService settingsService,
                            io.github.sander_i.service.GameScanService scan) {
        this.games = games;
        this.tags = tags;
        this.meta = meta;
        this.sessions = sessions;
        this.settings = settings;
        this.settingsService = settingsService;
        this.scan = scan;
    }

    @GetMapping
    public Map<String, Object> dump() {
        // 导出前先同步，确保命令行新加的游戏也进备份
        scan.syncAll();
        var allGames = games.findAll();
        var tagMap = meta.tagsByGame(allGames.stream().map(g -> g.getId()).toList());
        return Map.of(
                "exportedAt", java.time.Instant.now().toString(),
                "games", allGames.stream().map(g -> Map.of(
                        "id", g.getId(),
                        "name", g.getName(),
                        "description", g.getDescription() == null ? "" : g.getDescription(),
                        "coverPath", g.getCoverPath() == null ? "" : g.getCoverPath(),
                        "iconPath", g.getIconPath() == null ? "" : g.getIconPath(),
                        "cgPath", g.getCgPath() == null ? "" : g.getCgPath(),
                        "cgHighPath", g.getCgHighPath() == null ? "" : g.getCgHighPath(),
                        "cgLowPath", g.getCgLowPath() == null ? "" : g.getCgLowPath(),
                        "tags", tagMap.getOrDefault(g.getId(), List.of()).stream()
                                .map(t -> t.getName()).toList())).toList(),
                "tags", tags.findAll().stream()
                        .map(t -> Map.of("name", t.getName())).toList(),
                "settings", settingsService.all(),
                "sessionCount", sessions.count());
    }
}
