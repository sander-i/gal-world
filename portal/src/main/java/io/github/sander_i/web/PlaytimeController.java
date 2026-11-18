/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.i18n.I18n;
import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.service.PlaytimeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/games/{id}/playtime")
public class PlaytimeController {

    private final PlaytimeService playtime;
    private final GameRepository repo;
    private final io.github.sander_i.service.GameScanService scan;

    public PlaytimeController(PlaytimeService playtime, GameRepository repo,
                              io.github.sander_i.service.GameScanService scan) {
        this.playtime = playtime;
        this.repo = repo;
        this.scan = scan;
    }

    /** 游玩页每 30 秒调一次；门户详情页打开时也调一次，用于开新记录 */
    @PostMapping("/heartbeat")
    public Map<String, Object> heartbeat(@PathVariable String id) {
        requireGame(id);
        playtime.heartbeat(id);
        PlaytimeService.PlaytimeSummary s = playtime.summary(id);
        return Map.of(
                "totalSeconds", s.totalSeconds(),
                "todaySeconds", s.todaySeconds());
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        if (!repo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        PlaytimeService.PlaytimeSummary s = playtime.summary(id);
        return ResponseEntity.ok(Map.of(
                "totalSeconds", s.totalSeconds(),
                "todaySeconds", s.todaySeconds(),
                "sessions", s.sessions(),
                "lastPlayed", s.lastPlayed() == null ? "" : s.lastPlayed().toString()));
    }

    /**
     * 离开游玩页（返回库、切到别的标签、关页面）时调用：
     * 立刻结算进行中的记录，不用等心跳超时。
     * 会话本身不受影响。
     */
    @PostMapping("/leave")
    public Map<String, Object> leave(@PathVariable String id) {
        if (repo.existsById(id)) {
            playtime.closeOpen(id);
        }
        PlaytimeService.PlaytimeSummary s = playtime.summary(id);
        return Map.of(
                "totalSeconds", s.totalSeconds(),
                "todaySeconds", s.todaySeconds());
    }

    private void requireGame(String id) {
        // 心跳可能先于任何列表请求到达（例如直接打开 /play/<id>），
        // 此时 DB 里还没有这条游戏，先同步一次磁盘再判断
        if (!repo.existsById(id)) {
            scan.syncAll();
        }
        if (!repo.existsById(id)) {
            throw new IllegalArgumentException(I18n.t("没有这个游戏: {0}", id));
        }
    }
}
