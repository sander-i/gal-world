/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.i18n.I18n;
import io.github.sander_i.service.SettingsService;
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
    public ResponseEntity<Map<String, String>> get(
            @org.springframework.web.bind.annotation.RequestHeader(
                    name = "If-None-Match", required = false) String ifNoneMatch) {
        Map<String, String> all = settings.all();
        String etag = ETags.of(all);
        var cache = org.springframework.http.CacheControl
                .maxAge(java.time.Duration.ofSeconds(30)).cachePrivate();
        // 低频变化：允许浏览器私有缓存 30 秒，命中时只回 304 不传 body
        if (ETags.matches(etag, ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(etag).cacheControl(cache).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(cache).body(all);
    }

    @PostMapping
    public ResponseEntity<?> update(@RequestBody Map<String, String> patch) {
        if (patch != null) {
            for (String k : patch.keySet()) {
                if (AdminSettingsController.isAdminKey(k)) {
                    return ResponseEntity.badRequest().body(
                            Map.of("message", I18n.t("该设置在后台管理中修改: {0}", k)));
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
