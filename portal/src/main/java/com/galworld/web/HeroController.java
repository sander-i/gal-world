/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.web;

import com.galworld.service.HeroService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HeroController {

    private final HeroService hero;
    private final com.galworld.service.GameMediaService gameMedia;

    public HeroController(HeroService hero, com.galworld.service.GameMediaService gameMedia) {
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
