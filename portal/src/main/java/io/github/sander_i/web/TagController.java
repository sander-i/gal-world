/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.i18n.I18n;
import io.github.sander_i.i18n.I18nText;
import io.github.sander_i.i18n.LocalizedText;
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

import java.util.LinkedHashMap;
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
        String locale = I18n.locale();
        return meta.all().stream()
                .map(t -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", t.getId());
                    out.put("name", LocalizedText.of(t, locale));
                    out.put("alias", LocalizedText.aliasOf(t, locale));
                    out.put("count", counts.getOrDefault(t.getId(), 0));
                    out.put("i18n", I18nText.parse(t.getI18nText()));
                    out.put("baseName", t.getName() == null ? "" : t.getName());
                    out.put("baseAlias", t.getAlias() == null ? "" : t.getAlias());
                    return out;
                })
                .toList();
    }

    /** {@code i18n} 是按语言的覆盖，主字段是默认值 */
    public record TagRequest(String name, String alias,
                             Map<String, Map<String, String>> i18n) {
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody TagRequest req) {
        var t = meta.create(req.name(), req.alias());
        applyI18n(t, req);
        return summary(t);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody TagRequest req) {
        var t = meta.update(id, req.name(), req.alias());
        applyI18n(t, req);
        return summary(t);
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

    /** 合并多语言覆盖并落库；请求没带 i18n 时不动已有覆盖 */
    private void applyI18n(io.github.sander_i.domain.Tag t, TagRequest req) {
        if (req.i18n() == null) {
            return;
        }
        t.setI18nText(I18nText.mergeAll(t.getI18nText(), req.i18n()));
        meta.save(t);
    }

    private Map<String, Object> summary(io.github.sander_i.domain.Tag t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("name", LocalizedText.of(t, I18n.locale()));
        out.put("alias", LocalizedText.aliasOf(t, I18n.locale()));
        out.put("i18n", I18nText.parse(t.getI18nText()));
        return out;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> bad(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }
}
