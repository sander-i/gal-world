/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.web;

import com.galworld.service.CompanyService;
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
@RequestMapping("/api/companies")
public class CompanyController {

    private final CompanyService companies;

    public CompanyController(CompanyService companies) {
        this.companies = companies;
    }

    public record CompanyRequest(String name, String alias, String website, String description) {
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        Map<Long, Integer> counts = companies.counts();
        return companies.all().stream()
                .map(c -> Map.<String, Object>of(
                        "id", c.getId(),
                        "name", c.getName(),
                        "alias", c.getAlias() == null ? "" : c.getAlias(),
                        "website", c.getWebsite() == null ? "" : c.getWebsite(),
                        "description", c.getDescription() == null ? "" : c.getDescription(),
                        "count", counts.getOrDefault(c.getId(), 0)))
                .toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CompanyRequest req) {
        var c = companies.create(req.name(), req.alias(), req.website(), req.description());
        return Map.of("id", c.getId(), "name", c.getName(),
                "alias", c.getAlias() == null ? "" : c.getAlias(),
                "website", c.getWebsite() == null ? "" : c.getWebsite());
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
        return Map.of("id", c.getId(), "name", c.getName(),
                "alias", c.getAlias() == null ? "" : c.getAlias(),
                "website", c.getWebsite() == null ? "" : c.getWebsite());
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
