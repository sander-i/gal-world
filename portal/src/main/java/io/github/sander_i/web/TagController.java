/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.service.TagsAndMeta;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 标签字典，供库页筛选栏与详情页选择器使用。 */
@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final TagsAndMeta meta;

    public TagController(TagsAndMeta meta) {
        this.meta = meta;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        Map<Long, Integer> counts = meta.counts();
        return meta.all().stream()
                .map(t -> Map.<String, Object>of(
                        "id", t.getId(),
                        "name", t.getName(),
                        "alias", t.getAlias() == null ? "" : t.getAlias(),
                        "count", counts.getOrDefault(t.getId(), 0)))
                .toList();
    }

    public record TagRequest(String name, String alias) {
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody TagRequest req) {
        var t = meta.create(req.name(), req.alias());
        return Map.of("id", t.getId(), "name", t.getName(),
                "alias", t.getAlias() == null ? "" : t.getAlias());
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody TagRequest req) {
        var t = meta.update(id, req.name(), req.alias());
        return Map.of("id", t.getId(), "name", t.getName(),
                "alias", t.getAlias() == null ? "" : t.getAlias());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        meta.deleteTag(id);
        return Map.of("deleted", id);
    }

    @PutMapping("/order")
    public Map<String, Object> reorder(@RequestBody OrderRequest req) {
        meta.reorder(req.ids());
        return Map.of("ok", true);
    }

    public record OrderRequest(java.util.List<Long> ids) {
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> bad(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }
}
