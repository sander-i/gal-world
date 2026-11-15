/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import com.galworld.domain.GameLocalization;
import com.galworld.domain.LocalizationGroup;
import com.galworld.repo.GameLocalizationRepository;
import com.galworld.repo.LocalizationGroupRepository;
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
 * 汉化组管理。与公司平行的独立维度，多对多。
 */
@Service
public class LocalizationGroupService {

    private final LocalizationGroupRepository groups;
    private final GameLocalizationRepository links;

    public LocalizationGroupService(LocalizationGroupRepository groups,
                                    GameLocalizationRepository links) {
        this.groups = groups;
        this.links = links;
    }

    public List<LocalizationGroup> all() {
        return groups.findAllByOrderBySortOrderAscNameAscAliasAscWebsiteAsc();
    }

    /** 按 id 顺序重写排序值（1..n） */
    @Transactional
    public void reorder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        Map<Long, LocalizationGroup> byId = new HashMap<>();
        groups.findAll().forEach(g -> byId.put(g.getId(), g));
        List<LocalizationGroup> ordered = new ArrayList<>();
        int order = 1;
        for (Long id : ids) {
            LocalizationGroup g = byId.get(id);
            if (g == null) continue;
            g.setSortOrder(order++);
            ordered.add(g);
        }
        if (!ordered.isEmpty()) groups.saveAll(ordered);
    }

    /** 每个汉化组下的游戏数，用于筛选栏展示 */
    public Map<Long, Integer> counts() {
        Map<Long, Integer> out = new HashMap<>();
        for (GameLocalization l : links.findAll()) {
            out.merge(l.getGroupId(), 1, Integer::sum);
        }
        return out;
    }

    /** 批量取每个游戏的汉化组，避免列表页 N+1 查询 */
    public Map<String, List<LocalizationGroup>> byGame(Collection<String> gameIds) {
        Map<String, List<LocalizationGroup>> out = new HashMap<>();
        if (gameIds.isEmpty()) {
            return out;
        }
        Map<Long, LocalizationGroup> byId = new HashMap<>();
        Map<Long, Integer> rank = new HashMap<>();
        List<LocalizationGroup> global =
                groups.findAllByOrderBySortOrderAscNameAscAliasAscWebsiteAsc();
        for (int i = 0; i < global.size(); i++) {
            byId.put(global.get(i).getId(), global.get(i));
            rank.put(global.get(i).getId(), i);
        }
        Map<String, List<GameLocalization>> grouped = new HashMap<>();
        for (GameLocalization l : links.findByGameIdIn(gameIds)) {
            grouped.computeIfAbsent(l.getGameId(), k -> new ArrayList<>()).add(l);
        }
        grouped.forEach((gameId, ls) -> out.put(gameId, ordered(ls, byId, rank)));
        return out;
    }

    public List<LocalizationGroup> ofGame(String gameId) {
        Map<Long, LocalizationGroup> byId = new HashMap<>();
        Map<Long, Integer> rank = new HashMap<>();
        List<LocalizationGroup> global =
                groups.findAllByOrderBySortOrderAscNameAscAliasAscWebsiteAsc();
        for (int i = 0; i < global.size(); i++) {
            byId.put(global.get(i).getId(), global.get(i));
            rank.put(global.get(i).getId(), i);
        }
        return ordered(links.findByGameId(gameId), byId, rank);
    }

    /** 单游戏任一关联有排序值时按单游戏顺序，否则跟随全局顺序 */
    private List<LocalizationGroup> ordered(List<GameLocalization> links,
                                            Map<Long, LocalizationGroup> byId,
                                            Map<Long, Integer> rank) {
        boolean custom = links.stream().anyMatch(l -> l.getSortOrder() > 0);
        return links.stream()
                .filter(l -> byId.containsKey(l.getGroupId()))
                .sorted(java.util.Comparator
                        .comparingInt((GameLocalization l) -> custom && l.getSortOrder() > 0
                                ? l.getSortOrder() : Integer.MAX_VALUE)
                        .thenComparingInt(l -> rank.getOrDefault(l.getGroupId(), Integer.MAX_VALUE)))
                .map(l -> byId.get(l.getGroupId()))
                .toList();
    }

    /** 汉化组引用：id 存在时按 id，否则按 name + alias + website 查找或创建 */
    public record GroupRef(Long id, String name, String alias, String website, String description) {
    }

    /** 按汉化组引用设置游戏关联；不存在会自动创建 */
    @Transactional
    public List<LocalizationGroup> setForGame(String gameId, Collection<GroupRef> refs) {
        Map<Long, Integer> oldOrder = new HashMap<>();
        for (GameLocalization l : links.findByGameId(gameId)) {
            oldOrder.put(l.getGroupId(), l.getSortOrder());
        }
        links.deleteByGameId(gameId);
        Set<Long> seen = new LinkedHashSet<>();
        for (GroupRef ref : refs) {
            if (ref == null) continue;

            LocalizationGroup g = ref.id() == null ? null : groups.findById(ref.id()).orElse(null);
            if (g == null) {
                String name = text(ref.name());
                if (name.isEmpty()) continue;
                String alias = text(ref.alias());
                String website = normalizeWebsite(ref.website());
                String description = text(ref.description());
                g = groups.findByNameAndAliasAndWebsite(name, alias, website).orElseGet(() -> {
                    LocalizationGroup fresh = new LocalizationGroup();
                    fresh.setName(name);
                    fresh.setAlias(alias);
                    fresh.setWebsite(website);
                    fresh.setDescription(description);
                    return groups.save(fresh);
                });
            }
            if (seen.add(g.getId())) {
                GameLocalization link = new GameLocalization(gameId, g.getId());
                link.setSortOrder(oldOrder.getOrDefault(g.getId(), 0));
                links.save(link);
            }
        }
        return ofGame(gameId);
    }

    @Transactional
    public LocalizationGroup create(String name, String alias, String website, String description) {
        String n = text(name);
        if (n.isEmpty()) {
            throw new IllegalArgumentException("汉化组名不能为空");
        }
        String a = text(alias);
        String w = normalizeWebsite(website);
        String d = text(description);
        return groups.findByNameAndAliasAndWebsite(n, a, w).orElseGet(() -> {
            LocalizationGroup g = new LocalizationGroup();
            g.setName(n);
            g.setAlias(a);
            g.setWebsite(w);
            g.setDescription(d);
            return groups.save(g);
        });
    }

    @Transactional
    public LocalizationGroup update(long id, String name, String alias, String website,
                                    String description) {
        LocalizationGroup g = groups.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("汉化组不存在: " + id));
        String n = text(name);
        if (n.isEmpty()) {
            throw new IllegalArgumentException("汉化组名不能为空");
        }
        String a = text(alias);
        String w = normalizeWebsite(website);
        if (groups.findByNameAndAliasAndWebsite(n, a, w)
                .filter(other -> !other.getId().equals(id)).isPresent()) {
            throw new IllegalArgumentException("汉化组名 + 别名 + 网址已存在");
        }
        g.setName(n);
        g.setAlias(a);
        g.setWebsite(w);
        g.setDescription(text(description));
        return groups.save(g);
    }

    /** 网址为空时保留空串；没写协议时补 https:// */
    private String normalizeWebsite(String website) {
        String w = text(website);
        if (w.isEmpty()) {
            return "";
        }
        if (!w.matches("(?i)^[a-z][a-z0-9+.-]*://.*$")) {
            w = "https://" + w;
        }
        return w;
    }

    /** 去首尾空白，null 归一为空串，避免 null 绕过唯一索引 */
    private String text(String value) {
        return value == null ? "" : value.strip();
    }

    /** 单游戏排序：按 id 顺序写 1..n */
    @Transactional
    public void reorderForGame(String gameId, List<Long> ids) {
        List<GameLocalization> ls = links.findByGameId(gameId);
        Map<Long, GameLocalization> byId = new HashMap<>();
        ls.forEach(l -> byId.put(l.getGroupId(), l));
        List<GameLocalization> ordered = new ArrayList<>();
        int order = 1;
        for (Long id : ids) {
            GameLocalization l = byId.get(id);
            if (l == null) continue;
            l.setSortOrder(order++);
            ordered.add(l);
        }
        if (!ordered.isEmpty()) links.saveAll(ordered);
    }

    /** 重置单游戏排序，回到全局顺序 */
    @Transactional
    public void resetOrderForGame(String gameId) {
        List<GameLocalization> ls = links.findByGameId(gameId);
        ls.forEach(l -> l.setSortOrder(0));
        links.saveAll(ls);
    }

    /** 删除汉化组只解除关联，不动游戏 */
    @Transactional
    public void delete(long id) {
        links.findAll().stream()
                .filter(l -> l.getGroupId() == id)
                .forEach(links::delete);
        groups.deleteById(id);
    }

    /** 按汉化组 id 筛选出游戏 id 集合（前端筛选用的是 id） */
    public Set<String> gameIdsOfId(long groupId) {
        return links.findAll().stream()
                .filter(l -> l.getGroupId() != null && l.getGroupId() == groupId)
                .map(GameLocalization::getGameId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** 按汉化组名筛选出游戏 id 集合（同名不同别名/网址的公司合并匹配） */
    public Set<String> gameIdsOf(String groupName) {
        List<LocalizationGroup> matched = groups.findByName(groupName);
        if (matched.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = matched.stream()
                .map(LocalizationGroup::getId)
                .collect(java.util.stream.Collectors.toSet());
        return links.findAll().stream()
                .filter(l -> ids.contains(l.getGroupId()))
                .map(GameLocalization::getGameId)
                .collect(java.util.stream.Collectors.toSet());
    }
}
