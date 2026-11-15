/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import com.galworld.domain.Company;
import com.galworld.domain.GameCompany;
import com.galworld.repo.CompanyRepository;
import com.galworld.repo.GameCompanyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 公司（出品方）管理。与标签平行的独立维度，多对多。
 */
@Service
public class CompanyService {

    private final CompanyRepository companies;
    private final GameCompanyRepository links;

    public CompanyService(CompanyRepository companies, GameCompanyRepository links) {
        this.companies = companies;
        this.links = links;
    }

    public List<Company> all() {
        return companies.findAllByOrderBySortOrderAscNameAscAliasAscWebsiteAsc();
    }

    /** 按 id 顺序重写排序值（1..n） */
    @Transactional
    public void reorder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        Map<Long, Company> byId = new HashMap<>();
        companies.findAll().forEach(c -> byId.put(c.getId(), c));
        List<Company> ordered = new ArrayList<>();
        int order = 1;
        for (Long id : ids) {
            Company c = byId.get(id);
            if (c == null) continue;
            c.setSortOrder(order++);
            ordered.add(c);
        }
        if (!ordered.isEmpty()) companies.saveAll(ordered);
    }

    /** 每个公司下的游戏数，用于筛选栏展示 */
    public Map<Long, Integer> counts() {
        Map<Long, Integer> out = new HashMap<>();
        for (GameCompany l : links.findAll()) {
            out.merge(l.getCompanyId(), 1, Integer::sum);
        }
        return out;
    }

    /** 批量取每个游戏的公司，避免列表页 N+1 查询 */
    public Map<String, List<Company>> byGame(Collection<String> gameIds) {
        Map<String, List<Company>> out = new HashMap<>();
        if (gameIds.isEmpty()) {
            return out;
        }
        Map<Long, Company> byId = new HashMap<>();
        Map<Long, Integer> rank = new HashMap<>();
        List<Company> global = companies.findAllByOrderBySortOrderAscNameAscAliasAscWebsiteAsc();
        for (int i = 0; i < global.size(); i++) {
            byId.put(global.get(i).getId(), global.get(i));
            rank.put(global.get(i).getId(), i);
        }
        Map<String, List<GameCompany>> grouped = new HashMap<>();
        for (GameCompany l : links.findByGameIdIn(gameIds)) {
            grouped.computeIfAbsent(l.getGameId(), k -> new ArrayList<>()).add(l);
        }
        grouped.forEach((gameId, ls) -> out.put(gameId, ordered(ls, byId, rank)));
        return out;
    }

    public List<Company> ofGame(String gameId) {
        Map<Long, Company> byId = new HashMap<>();
        Map<Long, Integer> rank = new HashMap<>();
        List<Company> global = companies.findAllByOrderBySortOrderAscNameAscAliasAscWebsiteAsc();
        for (int i = 0; i < global.size(); i++) {
            byId.put(global.get(i).getId(), global.get(i));
            rank.put(global.get(i).getId(), i);
        }
        return ordered(links.findByGameId(gameId), byId, rank);
    }

    /** 单游戏任一关联有排序值时按单游戏顺序，否则跟随全局顺序 */
    private List<Company> ordered(List<GameCompany> links, Map<Long, Company> byId,
                                  Map<Long, Integer> rank) {
        boolean custom = links.stream().anyMatch(l -> l.getSortOrder() > 0);
        return links.stream()
                .filter(l -> byId.containsKey(l.getCompanyId()))
                .sorted(java.util.Comparator
                        .comparingInt((GameCompany l) -> custom && l.getSortOrder() > 0
                                ? l.getSortOrder() : Integer.MAX_VALUE)
                        .thenComparingInt(l -> rank.getOrDefault(l.getCompanyId(), Integer.MAX_VALUE)))
                .map(l -> byId.get(l.getCompanyId()))
                .toList();
    }

    /** 公司引用：id 存在时按 id，否则按 name + alias + website 查找或创建 */
    public record CompanyRef(Long id, String name, String alias, String website, String description) {
    }

    /** 按公司引用设置游戏的公司集合；不存在会自动创建 */
    @Transactional
    public List<Company> setForGame(String gameId, Collection<CompanyRef> refs) {
        Map<Long, Integer> oldOrder = new HashMap<>();
        for (GameCompany l : links.findByGameId(gameId)) {
            oldOrder.put(l.getCompanyId(), l.getSortOrder());
        }
        links.deleteByGameId(gameId);
        Set<Long> seen = new LinkedHashSet<>();
        for (CompanyRef ref : refs) {
            if (ref == null) continue;

            // 有 id 直接沿用；没有 id 才按 名称 + 别名 + 网址 查找或创建
            Company c = ref.id() == null ? null : companies.findById(ref.id()).orElse(null);
            if (c == null) {
                String name = text(ref.name());
                if (name.isEmpty()) continue;
                String alias = text(ref.alias());
                String website = normalizeWebsite(ref.website());
                String description = text(ref.description());
                c = companies.findByNameAndAliasAndWebsite(name, alias, website).orElseGet(() -> {
                    Company fresh = new Company();
                    fresh.setName(name);
                    fresh.setAlias(alias);
                    fresh.setWebsite(website);
                    fresh.setDescription(description);
                    return companies.save(fresh);
                });
            }
            if (seen.add(c.getId())) {
                GameCompany link = new GameCompany(gameId, c.getId());
                link.setSortOrder(oldOrder.getOrDefault(c.getId(), 0));
                links.save(link);
            }
        }
        return ofGame(gameId);
    }

    @Transactional
    public Company create(String name, String alias, String website, String description) {
        String n = text(name);
        if (n.isEmpty()) {
            throw new IllegalArgumentException("公司名不能为空");
        }
        String a = text(alias);
        String w = normalizeWebsite(website);
        String d = text(description);
        return companies.findByNameAndAliasAndWebsite(n, a, w).orElseGet(() -> {
            Company c = new Company();
            c.setName(n);
            c.setAlias(a);
            c.setWebsite(w);
            c.setDescription(d);
            return companies.save(c);
        });
    }

    @Transactional
    public Company update(long id, String name, String alias, String website, String description) {
        Company c = companies.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("公司不存在: " + id));
        String n = text(name);
        if (n.isEmpty()) {
            throw new IllegalArgumentException("公司名不能为空");
        }
        String a = text(alias);
        String w = normalizeWebsite(website);
        if (companies.findByNameAndAliasAndWebsite(n, a, w)
                .filter(other -> !other.getId().equals(id)).isPresent()) {
            throw new IllegalArgumentException("公司名 + 别名 + 网址已存在");
        }
        c.setName(n);
        c.setAlias(a);
        c.setWebsite(w);
        c.setDescription(text(description));
        return companies.save(c);
    }

    /** 去首尾空白，null 归一为空串，避免 null 绕过唯一索引 */
    private String text(String value) {
        return value == null ? "" : value.strip();
    }

    /** 网址为空时保留空串；没写协议时补 https://，避免详情页链接点不开 */
    private String normalizeWebsite(String website) {
        String w = website == null ? "" : website.strip();
        if (w.isEmpty()) {
            return "";
        }
        if (!w.matches("(?i)^[a-z][a-z0-9+.-]*://.*$")) {
            w = "https://" + w;
        }
        return w;
    }

    /** 单游戏排序：按 id 顺序写 1..n */
    @Transactional
    public void reorderForGame(String gameId, List<Long> ids) {
        List<GameCompany> ls = links.findByGameId(gameId);
        Map<Long, GameCompany> byId = new HashMap<>();
        ls.forEach(l -> byId.put(l.getCompanyId(), l));
        List<GameCompany> ordered = new ArrayList<>();
        int order = 1;
        for (Long id : ids) {
            GameCompany l = byId.get(id);
            if (l == null) continue;
            l.setSortOrder(order++);
            ordered.add(l);
        }
        if (!ordered.isEmpty()) links.saveAll(ordered);
    }

    /** 重置单游戏排序，回到全局顺序 */
    @Transactional
    public void resetOrderForGame(String gameId) {
        List<GameCompany> ls = links.findByGameId(gameId);
        ls.forEach(l -> l.setSortOrder(0));
        links.saveAll(ls);
    }

    /** 删除公司只解除关联，不动游戏 */
    @Transactional
    public void delete(long id) {
        links.findAll().stream()
                .filter(l -> l.getCompanyId() == id)
                .forEach(links::delete);
        companies.deleteById(id);
    }

    /** 按公司 id 筛选出游戏 id 集合（前端筛选用的是 id） */
    public Set<String> gameIdsOfId(long companyId) {
        return links.findAll().stream()
                .filter(l -> l.getCompanyId() != null && l.getCompanyId() == companyId)
                .map(GameCompany::getGameId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** 按公司名筛选出游戏 id 集合（同名不同网址的公司合并匹配） */
    public Set<String> gameIdsOf(String companyName) {
        List<Company> matched = companies.findByName(companyName);
        if (matched.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = matched.stream().map(Company::getId).collect(java.util.stream.Collectors.toSet());
        return links.findAll().stream()
                .filter(l -> ids.contains(l.getCompanyId()))
                .map(GameCompany::getGameId)
                .collect(java.util.stream.Collectors.toSet());
    }
}
