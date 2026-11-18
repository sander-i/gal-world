/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.i18n.I18n;
import io.github.sander_i.i18n.I18nText;
import io.github.sander_i.i18n.LocalizedText;
import io.github.sander_i.service.CompanyService;
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
@RequestMapping("/api/companies")
public class CompanyController {

    private final CompanyService companies;

    public CompanyController(CompanyService companies) {
        this.companies = companies;
    }

    /**
     * 请求体。
     *
     * {@code i18n} 是按语言的覆盖，形如
     * {@code {"en": {"name": "Yuzusoft", "alias": "yuzu-soft"}}}；
     * 主字段是默认值，缺失的语言回退到它。
     */
    public record CompanyRequest(String name, String alias, String website, String description,
                                 Map<String, Map<String, String>> i18n) {
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        Map<Long, Integer> counts = companies.counts();
        String locale = I18n.locale();
        return companies.all().stream()
                .map(c -> {
                    LocalizedText.OrgText text = LocalizedText.of(c, locale);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", c.getId());
                    out.put("name", text.name());
                    out.put("alias", text.alias());
                    out.put("website", text.website());
                    out.put("description", text.description());
                    out.put("count", counts.getOrDefault(c.getId(), 0));
                    // 维护页要用全部语言的原文来编辑，展示用上面那几个已解析的字段
                    out.put("i18n", I18nText.parse(c.getI18nText()));
                    out.put("baseName", c.getName() == null ? "" : c.getName());
                    out.put("baseAlias", c.getAlias() == null ? "" : c.getAlias());
                    out.put("baseDescription", c.getDescription() == null ? "" : c.getDescription());
                    return out;
                })
                .toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CompanyRequest req) {
        var c = companies.create(req.name(), req.alias(), req.website(), req.description());
        applyI18n(c, req);
        return summary(c);
    }

    @PutMapping("/order")
    public Map<String, Object> reorder(@RequestBody OrderRequest req) {
        companies.reorder(req.ids());
        return Map.of("ok", true);
    }

    public record OrderRequest(java.util.List<Long> ids) {
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody CompanyRequest req) {
        var c = companies.update(id, req.name(), req.alias(), req.website(), req.description());
        applyI18n(c, req);
        return summary(c);
    }

    /** 合并多语言覆盖并落库；请求没带 i18n 时不动已有覆盖 */
    private void applyI18n(io.github.sander_i.domain.Company c, CompanyRequest req) {
        if (req.i18n() == null) {
            return;
        }
        c.setI18nText(I18nText.mergeAll(c.getI18nText(), req.i18n()));
        companies.save(c);
    }

    private Map<String, Object> summary(io.github.sander_i.domain.Company c) {
        LocalizedText.OrgText text = LocalizedText.of(c, I18n.locale());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.getId());
        out.put("name", text.name());
        out.put("alias", text.alias());
        out.put("website", text.website());
        out.put("i18n", I18nText.parse(c.getI18nText()));
        return out;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        companies.delete(id);
        return Map.of("deleted", id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> bad(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }
}
