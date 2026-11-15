/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.service.HeroService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HeroController {

    private final HeroService hero;
    private final io.github.sander_i.service.GameMediaService gameMedia;

    public HeroController(HeroService hero, io.github.sander_i.service.GameMediaService gameMedia) {
        this.hero = hero;
        this.gameMedia = gameMedia;
    }

    @GetMapping("/api/hero")
    public ResponseEntity<?> hero() {
        return hero.pick()
                .map(h -> {
                    var covers = gameMedia.itemsOf(h.gameId(), "cover");
                    return ResponseEntity.ok(Map.of(
                            "gameId", h.gameId(),
                            "name", h.name(),
                            "coverPath", h.coverPath() == null ? "" : h.coverPath(),
                            "covers", covers,
                            "reason", h.reason(),
                            "totalSeconds", h.totalSeconds(),
                            "state", h.state(),
                            "lastPlayed", h.lastPlayed() == null ? "" : h.lastPlayed().toString()));
                })
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
