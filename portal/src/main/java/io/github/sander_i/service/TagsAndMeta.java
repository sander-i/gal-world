/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.domain.GameTag;
import io.github.sander_i.domain.Tag;
import io.github.sander_i.i18n.I18n;
import io.github.sander_i.repo.GameTagRepository;
import io.github.sander_i.repo.TagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TagsAndMeta {

    private final TagRepository tags;
    private final GameTagRepository links;

    public TagsAndMeta(TagRepository tags, GameTagRepository links) {
        this.tags = tags;
        this.links = links;
    }

    public List<Tag> all() {
        return tags.findAllByOrderBySortOrderAscNameAsc();
    }

    /** 每个标签下的游戏数，用于筛选栏展示 */
    public Map<Long, Integer> counts() {
        Map<Long, Integer> out = new HashMap<>();
        for (GameTag link : links.findAll()) {
            out.merge(link.getTagId(), 1, Integer::sum);
        }
        return out;
    }

    /** 按 id 顺序重写排序值（1..n） */
    @Transactional
    public void reorder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        Map<Long, Tag> byId = new HashMap<>();
        tags.findAll().forEach(t -> byId.put(t.getId(), t));
        List<Tag> ordered = new java.util.ArrayList<>();
        int order = 1;
        for (Long id : ids) {
            Tag t = byId.get(id);
            if (t == null) continue;
            t.setSortOrder(order++);
            ordered.add(t);
        }
        if (!ordered.isEmpty()) tags.saveAll(ordered);
    }

    /** 新建标签：名称必填；同名标签直接返回已有记录，不覆盖别名 */
    @Transactional
    public Tag create(String name, String alias) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) {
            throw new IllegalArgumentException(I18n.t("标签名不能为空"));
        }
        return tags.findByName(n).orElseGet(() -> {
            Tag fresh = new Tag();
            fresh.setName(n);
            String a = alias == null ? "" : alias.strip();
            fresh.setAlias(a.isEmpty() ? null : a);
            return tags.save(fresh);
        });
    }

    /** 批量取每个游戏的标签，避免列表页 N+1 查询 */
    public Map<String, List<Tag>> tagsByGame(Collection<String> gameIds) {
        Map<String, List<Tag>> out = new HashMap<>();
        if (gameIds.isEmpty()) {
            return out;
        }
        Map<Long, Tag> byId = new HashMap<>();
        Map<Long, Integer> rank = new HashMap<>();
        List<Tag> global = tags.findAllByOrderBySortOrderAscNameAsc();
        for (int i = 0; i < global.size(); i++) {
            byId.put(global.get(i).getId(), global.get(i));
            rank.put(global.get(i).getId(), i);
        }
        Map<String, List<GameTag>> grouped = new HashMap<>();
        for (GameTag link : links.findByGameIdIn(gameIds)) {
            grouped.computeIfAbsent(link.getGameId(), k -> new java.util.ArrayList<>()).add(link);
        }
        grouped.forEach((gameId, ls) -> out.put(gameId, ordered(ls, byId, rank)));
        return out;
    }

    public List<Tag> ofGame(String gameId) {
        Map<Long, Tag> byId = new HashMap<>();
        Map<Long, Integer> rank = new HashMap<>();
        List<Tag> global = tags.findAllByOrderBySortOrderAscNameAsc();
        for (int i = 0; i < global.size(); i++) {
            byId.put(global.get(i).getId(), global.get(i));
            rank.put(global.get(i).getId(), i);
        }
        return ordered(links.findByGameId(gameId), byId, rank);
    }

    /** 单游戏任一关联有排序值时按单游戏顺序，否则跟随全局顺序 */
    private List<Tag> ordered(List<GameTag> links, Map<Long, Tag> byId,
                              Map<Long, Integer> rank) {
        boolean custom = links.stream().anyMatch(l -> l.getSortOrder() > 0);
        return links.stream()
                .filter(l -> byId.containsKey(l.getTagId()))
                .sorted(java.util.Comparator
                        .comparingInt((GameTag l) -> custom && l.getSortOrder() > 0
                                ? l.getSortOrder() : Integer.MAX_VALUE)
                        .thenComparingInt(l -> rank.getOrDefault(l.getTagId(), Integer.MAX_VALUE)))
                .map(l -> byId.get(l.getTagId()))
                .toList();
    }

    /** 按名字设置游戏的标签集合；名字不存在会自动创建 */
    @Transactional
    public List<Tag> setTags(String gameId, Collection<String> names) {
        Map<Long, Integer> oldOrder = new HashMap<>();
        for (GameTag link : links.findByGameId(gameId)) {
            oldOrder.put(link.getTagId(), link.getSortOrder());
        }
        links.deleteByGameId(gameId);
        Set<String> wanted = new LinkedHashSet<>();
        for (String n : names) {
            if (n != null && !n.isBlank()) {
                wanted.add(n.strip());
            }
        }
        for (String name : wanted) {
            Tag t = tags.findByName(name).orElseGet(() -> {
                Tag fresh = new Tag();
                fresh.setName(name);
                return tags.save(fresh);
            });
            GameTag link = new GameTag(gameId, t.getId());
            link.setSortOrder(oldOrder.getOrDefault(t.getId(), 0));
            links.save(link);
        }
        return ofGame(gameId);
    }

    /** 单游戏排序：按 tag id 顺序写 1..n */
    @Transactional
    public void reorderForGame(String gameId, List<Long> ids) {
        List<GameTag> ls = links.findByGameId(gameId);
        Map<Long, GameTag> byId = new HashMap<>();
        ls.forEach(l -> byId.put(l.getTagId(), l));
        List<GameTag> ordered = new java.util.ArrayList<>();
        int order = 1;
        for (Long id : ids) {
            GameTag l = byId.get(id);
            if (l == null) continue;
            l.setSortOrder(order++);
            ordered.add(l);
        }
        if (!ordered.isEmpty()) links.saveAll(ordered);
    }

    /** 重置单游戏排序，回到全局顺序 */
    @Transactional
    public void resetOrderForGame(String gameId) {
        List<GameTag> ls = links.findByGameId(gameId);
        ls.forEach(l -> l.setSortOrder(0));
        links.saveAll(ls);
    }

    /** 编辑标签：名称必填，别名可空 */
    @Transactional
    public Tag update(long id, String name, String alias) {
        Tag t = tags.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(I18n.t("标签不存在: {0}", id)));
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) {
            throw new IllegalArgumentException(I18n.t("标签名不能为空"));
        }
        tags.findByName(n)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new IllegalArgumentException(I18n.t("标签名已存在"));
                });
        t.setName(n);
        String a = alias == null ? "" : alias.strip();
        t.setAlias(a.isEmpty() ? null : a);
        return tags.save(t);
    }

    @Transactional
    public void deleteTag(long id) {
        links.deleteByTagId(id);
        tags.deleteById(id);
    }
}
