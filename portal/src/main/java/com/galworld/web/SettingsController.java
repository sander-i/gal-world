/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.web;

import com.galworld.service.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public Map<String, String> get() {
        return settings.all();
    }

    @PostMapping
    public ResponseEntity<?> update(@RequestBody Map<String, String> patch) {
        if (patch != null) {
            for (String k : patch.keySet()) {
                if (AdminSettingsController.isAdminKey(k)) {
                    return ResponseEntity.badRequest().body(
                            Map.of("message", "该设置在后台管理中修改: " + k));
                }
            }
        }
        try {
            return ResponseEntity.ok(settings.update(patch));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
