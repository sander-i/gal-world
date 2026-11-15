/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.web;

import com.galworld.service.LocalizationGroupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/localization-groups")
public class LocalizationGroupController {

    private final LocalizationGroupService groups;

    public LocalizationGroupController(LocalizationGroupService groups) {
        this.groups = groups;
    }

    public record GroupRequest(String name, String alias, String website, String description) {
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        Map<Long, Integer> counts = groups.counts();
        return groups.all().stream()
                .map(g -> Map.<String, Object>of(
                        "id", g.getId(),
                        "name", g.getName(),
                        "alias", g.getAlias() == null ? "" : g.getAlias(),
                        "website", g.getWebsite() == null ? "" : g.getWebsite(),
                        "description", g.getDescription() == null ? "" : g.getDescription(),
                        "count", counts.getOrDefault(g.getId(), 0)))
                .toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody GroupRequest req) {
        var g = groups.create(req.name(), req.alias(), req.website(), req.description());
        return Map.of("id", g.getId(), "name", g.getName(),
                "alias", g.getAlias() == null ? "" : g.getAlias(),
                "website", g.getWebsite() == null ? "" : g.getWebsite());
    }

    @PutMapping("/order")
    public Map<String, Object> reorder(@RequestBody OrderRequest req) {
        groups.reorder(req.ids());
        return Map.of("ok", true);
    }

    public record OrderRequest(java.util.List<Long> ids) {
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody GroupRequest req) {
        var g = groups.update(id, req.name(), req.alias(), req.website(), req.description());
        return Map.of("id", g.getId(), "name", g.getName(),
                "alias", g.getAlias() == null ? "" : g.getAlias(),
                "website", g.getWebsite() == null ? "" : g.getWebsite());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        groups.delete(id);
        return Map.of("deleted", id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> bad(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }
}
