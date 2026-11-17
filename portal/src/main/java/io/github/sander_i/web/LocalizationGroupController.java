/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.i18n.I18n;
import io.github.sander_i.i18n.I18nText;
import io.github.sander_i.i18n.LocalizedText;
import io.github.sander_i.service.LocalizationGroupService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/localization-groups")
public class LocalizationGroupController {

    private final LocalizationGroupService groups;

    public LocalizationGroupController(LocalizationGroupService groups) {
        this.groups = groups;
    }

    /** {@code i18n} 是按语言的覆盖，主字段是默认值 */
    public record GroupRequest(String name, String alias, String website, String description,
                               Map<String, Map<String, String>> i18n) {
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        Map<Long, Integer> counts = groups.counts();
        String locale = I18n.locale();
        return groups.all().stream()
                .map(g -> {
                    LocalizedText.OrgText text = LocalizedText.of(g, locale);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", g.getId());
                    out.put("name", text.name());
                    out.put("alias", text.alias());
                    out.put("website", text.website());
                    out.put("description", text.description());
                    out.put("count", counts.getOrDefault(g.getId(), 0));
                    out.put("i18n", I18nText.parse(g.getI18nText()));
                    out.put("baseName", g.getName() == null ? "" : g.getName());
                    out.put("baseAlias", g.getAlias() == null ? "" : g.getAlias());
                    out.put("baseDescription", g.getDescription() == null ? "" : g.getDescription());
                    return out;
                })
                .toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody GroupRequest req) {
        var g = groups.create(req.name(), req.alias(), req.website(), req.description());
        applyI18n(g, req);
        return summary(g);
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
        applyI18n(g, req);
        return summary(g);
    }

    /** 合并多语言覆盖并落库；请求没带 i18n 时不动已有覆盖 */
    private void applyI18n(io.github.sander_i.domain.LocalizationGroup g, GroupRequest req) {
        if (req.i18n() == null) {
            return;
        }
        g.setI18nText(I18nText.mergeAll(g.getI18nText(), req.i18n()));
        groups.save(g);
    }

    private Map<String, Object> summary(io.github.sander_i.domain.LocalizationGroup g) {
        LocalizedText.OrgText text = LocalizedText.of(g, I18n.locale());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", g.getId());
        out.put("name", text.name());
        out.put("alias", text.alias());
        out.put("website", text.website());
        out.put("i18n", I18nText.parse(g.getI18nText()));
        return out;
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
