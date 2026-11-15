/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import com.galworld.domain.Game;
import com.galworld.domain.GameMedia;
import com.galworld.repo.GameMediaRepository;
import com.galworld.repo.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 封面 / 宣传 CG 的多文件管理与排序。
 *
 * 真相分两处：
 *  - 文件在 games/<id>.meta/media/ 下，文件名带序号（cover-1.png、cg-2.mp4）
 *  - 顺序在 game_media.sort_order 里，删除后重排并重命名文件，保持序号与顺序一致
 *
 * Game.coverPath 保留为「置顶封面」缓存（sort_order=0 那项），
 * 用于列表页首屏立即加载，避免等所有图都加载完。
 */
@Service
public class GameMediaService {

    private static final Logger log = LoggerFactory.getLogger(GameMediaService.class);

    /** 派生图文件名后缀（cover-1.jpg.s640 / cover-1.jpg.s1280），不是独立媒体；临时写盘残留一并排除 */
    private static final java.util.regex.Pattern VARIANT =
            java.util.regex.Pattern.compile("\\.s\\d+$|\\.tmp-\\d+$");

    private final GameMediaRepository repo;
    private final GameRepository games;
    private final MediaService media;
    private final SettingsService settings;
    /** 上传并发锁：前端会 2 路并发上传，避免同时分配同一个序号 / 写同一个文件 */
    private final Object uploadLock = new Object();

    public GameMediaService(GameMediaRepository repo, GameRepository games, MediaService media,
                            SettingsService settings) {
        this.repo = repo;
        this.games = games;
        this.media = media;
        this.settings = settings;
    }

    public record MediaView(long id, String kind, String path, int sortOrder, String filename) {
    }

    /** 一档派生图：宽 w，url 复用原图的 ?v= 版本参数 */
    public record Variant(int w, String url) {
    }

    /** 前端可用的媒体项：原图 URL + 已生成的派生图（按 640/1280 升序） */
    public record MediaItem(String original, List<Variant> variants) {
    }

    public List<MediaView> ofGame(String gameId) {
        return repo.findByGameIdOrderByKindAscSortOrderAsc(gameId).stream()
                .map(m -> new MediaView(m.getId(), m.getKind(), m.getPath(), m.getSortOrder(),
                        filenameOf(m)))
                .toList();
    }

    public List<MediaView> ofGame(String gameId, String kind) {
        return repo.findByGameIdAndKindOrderBySortOrderAsc(gameId, kind).stream()
                .map(m -> new MediaView(m.getId(), m.getKind(), m.getPath(), m.getSortOrder(),
                        filenameOf(m)))
                .toList();
    }

    /** 列表页/详情页用：原图 URL + 已存在的派生图 URL（按宽度升序）；开关关闭时 variants 恒为空 */
    public List<MediaItem> itemsOf(String gameId, String kind) {
        boolean enabled = settings.imageVariantEnabled();
        List<MediaItem> out = new ArrayList<>();
        java.nio.file.Path dir = media.mediaDir(gameId);
        Map<String, List<Integer>> variantWidths = enabled ? scanVariantWidths(dir) : Map.of();
        for (MediaView m : ofGame(gameId, kind)) {
            String path = m.path();
            int q = path.indexOf('?');
            String file = q < 0 ? path : path.substring(0, q);
            String query = q < 0 ? "" : path.substring(q);
            List<Variant> variants = new ArrayList<>();
            for (int w : variantWidths.getOrDefault(m.filename(), List.of())) {
                variants.add(new Variant(w, file + ".s" + w + query));
            }
            out.add(new MediaItem(path, variants));
        }
        return out;
    }

    /** 扫描目录里的 `<文件名>.s<宽度>` 派生图，按原文件名分组并按宽度升序 */
    private Map<String, List<Integer>> scanVariantWidths(java.nio.file.Path dir) {
        Map<String, List<Integer>> out = new HashMap<>();
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) {
            return out;
        }
        try (var files = java.nio.file.Files.list(dir)) {
            files.forEach(p -> {
                String name = p.getFileName().toString();
                java.util.regex.Matcher m = VARIANT_NAME.matcher(name);
                if (!m.matches()) return;
                out.computeIfAbsent(m.group(1), k -> new ArrayList<>())
                        .add(Integer.parseInt(m.group(2)));
            });
        } catch (java.io.IOException e) {
            return out;
        }
        out.values().forEach(java.util.Collections::sort);
        return out;
    }

    private static final java.util.regex.Pattern VARIANT_NAME =
            java.util.regex.Pattern.compile("^(.+)\\.s(\\d+)$");

    /** 批量取，避免列表页 N+1 */
    public Map<String, List<MediaView>> byGames(List<String> gameIds, String kind) {
        Map<String, List<MediaView>> out = new HashMap<>();
        for (GameMedia m : repo.findByGameIdInOrderByKindAscSortOrderAsc(gameIds)) {
            if (!m.getKind().equals(kind)) {
                continue;
            }
            out.computeIfAbsent(m.getGameId(), k -> new ArrayList<>())
                    .add(new MediaView(m.getId(), m.getKind(), m.getPath(), m.getSortOrder(),
                            filenameOf(m)));
        }
        return out;
    }

    /**
     * 上传并追加到列表末尾。
     *
     * 前端批量上传是 2 路并发，这里串行化「计数 → 分配序号 → 落盘 → 入库」，
     * 避免两个请求拿到同一个序号、互相覆盖文件。
     *
     * @param kind cover | cg | icon
     */
    public MediaView upload(String gameId, String kind, String originalName, InputStream in)
            throws IOException {
        synchronized (uploadLock) {
            return uploadLocked(gameId, kind, originalName, in);
        }
    }

    @Transactional
    MediaView uploadLocked(String gameId, String kind, String originalName, InputStream in)
            throws IOException {
        Game g = games.findById(gameId).orElseThrow(
                () -> new IllegalArgumentException("没有这个游戏: " + gameId));

        if (kind.equals("icon")) {
            // 图标单张：直接覆盖
            MediaService.Saved saved = media.save(gameId, kind, 0, originalName, in);
            g.setIconPath(saved.url());
            games.save(g);
            return new MediaView(-1, kind, saved.url(), 0, "icon");
        }

        synchronized (uploadLock) {
            int limit = kind.equals("cover") ? media.coverMaxCount() : media.cgMaxCount();
            long count = repo.countByGameIdAndKind(gameId, kind);
            if (count >= limit) {
                throw new IllegalArgumentException(
                        (kind.equals("cover") ? "封面" : "宣传 CG") + "最多 " + limit + " 个");
            }

            int seq = (int) count;
            MediaService.Saved saved = media.save(gameId, kind, seq, originalName, in);

            GameMedia m = new GameMedia();
            m.setGameId(gameId);
            m.setKind(kind);
            m.setPath(saved.url());
            m.setSortOrder(seq);
            m.setCreatedAt(Instant.now());
            repo.save(m);

            syncCoverCache(gameId, g);
            log.info("媒体已加入 {} {} 序号 {}（共 {}）", gameId, kind, seq, seq + 1);
            return new MediaView(m.getId(), kind, m.getPath(), m.getSortOrder(), filenameOf(m));
        }
    }

    /** 删除一个媒体：删记录、删文件、把后续序号前移并重命名 */
    @Transactional
    public void delete(String gameId, long mediaId) throws IOException {
        GameMedia m = repo.findById(mediaId).orElse(null);
        if (m == null || !m.getGameId().equals(gameId)) {
            throw new IllegalArgumentException("媒体不存在: " + mediaId);
        }
        if (m.getKind().equals("icon")) {
            media.deleteFile(gameId, "icon", 0);
            Game g = games.findById(gameId).orElse(null);
            if (g != null) {
                g.setIconPath(null);
                games.save(g);
            }
            return;
        }

        int removedSeq = m.getSortOrder();
        repo.delete(m);
        // 先删掉对应文件再重排，否则重排会把它挤到别的序号上，
        // 下次扫描又会被 reconcile 当成新文件补回数据库
        media.deleteFile(gameId, m.getKind(), removedSeq);

        // 后续项前移：先算映射再批量重命名，避免链式改名覆盖文件
        List<GameMedia> rest = repo.findByGameIdAndKindOrderBySortOrderAsc(gameId, m.getKind());
        Map<Integer, Integer> seqMap = new java.util.LinkedHashMap<>();
        for (int i = 0; i < rest.size(); i++) {
            int oldSeq = rest.get(i).getSortOrder();
            if (oldSeq > removedSeq && oldSeq != i) {
                seqMap.put(oldSeq, i);
            }
        }
        media.renumberBatch(gameId, m.getKind(), seqMap);

        for (int i = 0; i < rest.size(); i++) {
            GameMedia x = rest.get(i);
            if (x.getSortOrder() != i) {
                x.setPath(rewriteSeq(x.getPath(), m.getKind(), i));
                x.setSortOrder(i);
                repo.save(x);
            }
        }

        Game g = games.findById(gameId).orElse(null);
        if (g != null) {
            syncCoverCache(gameId, g);
        }
    }

    /** 按给定 id 顺序重排；未列出的项排在后面保持原相对顺序 */
    @Transactional
    public void reorder(String gameId, String kind, List<Long> ids) throws IOException {
        List<GameMedia> all = repo.findByGameIdAndKindOrderBySortOrderAsc(gameId, kind);
        List<GameMedia> ordered = new ArrayList<>();
        for (Long id : ids) {
            all.stream().filter(m -> m.getId().equals(id)).findFirst().ifPresent(ordered::add);
        }
        for (GameMedia m : all) {
            if (!ordered.contains(m)) {
                ordered.add(m);
            }
        }

        // 先算好所有序号映射，一次性批量重命名（避免链式改名互相覆盖）
        Map<Integer, Integer> seqMap = new java.util.LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            int oldSeq = ordered.get(i).getSortOrder();
            if (oldSeq != i) {
                seqMap.put(oldSeq, i);
            }
        }
        media.renumberBatch(gameId, kind, seqMap);

        for (int i = 0; i < ordered.size(); i++) {
            GameMedia m = ordered.get(i);
            if (m.getSortOrder() != i) {
                m.setPath(rewriteSeq(m.getPath(), kind, i));
                m.setSortOrder(i);
                repo.save(m);
            }
        }

        Game g = games.findById(gameId).orElse(null);
        if (g != null) {
            syncCoverCache(gameId, g);
        }
    }

    /** 把 sort_order=0 的封面写进 Game.coverPath，作为置顶封面缓存 */
    private void syncCoverCache(String gameId, Game g) {
        List<GameMedia> covers = repo.findByGameIdAndKindOrderBySortOrderAsc(
                gameId, GameMedia.KIND_COVER);
        g.setCoverPath(covers.isEmpty() ? null : covers.get(0).getPath());
        games.save(g);
    }

    /**
     * 磁盘上存在但 DB 里没有的媒体，补建记录。
     *
     * 用于两种场景：从旧命名迁移过来的文件、手工往 media 目录里放的文件。
     * 只补缺失项，不删多余记录，避免误删用户刚上传还没入库的条目。
     */
    @Transactional
    public void reconcile(String gameId) {
        try {
            for (String kind : List.of(GameMedia.KIND_COVER, GameMedia.KIND_CG)) {
                List<java.nio.file.Path> files = media.listFiles(gameId, kind);
                if (files.isEmpty()) {
                    continue;
                }
                List<GameMedia> existing = repo.findByGameIdAndKindOrderBySortOrderAsc(gameId, kind);
                java.util.Set<String> known = new java.util.HashSet<>();
                existing.forEach(m -> known.add(filenameOf(m)));

                int next = existing.size();
                for (java.nio.file.Path f : files) {
                    String name = f.getFileName().toString();
                    // 派生图不是独立媒体，否则每次 reconcile 都会多建记录
                    if (VARIANT.matcher(name).find()) {
                        continue;
                    }
                    if (known.contains(name)) {
                        continue;
                    }
                    // 文件名里的序号决定顺序；解析失败就排到末尾
                    int seq = parseSeq(name, kind, next);
                    GameMedia m = new GameMedia();
                    m.setGameId(gameId);
                    m.setKind(kind);
                    m.setPath("/media/" + gameId + "/" + name);
                    m.setSortOrder(seq);
                    m.setCreatedAt(Instant.now());
                    repo.save(m);
                    log.info("补建媒体记录 {} {} {}", gameId, kind, name);
                    next++;
                }
                // 重排成连续编号
                renumberRecords(gameId, kind);
            }
            Game g = games.findById(gameId).orElse(null);
            if (g != null) {
                syncCoverCache(gameId, g);
            }
        } catch (IOException e) {
            log.warn("同步媒体失败 {}: {}", gameId, e.toString());
        }
    }

    /** 把某类媒体的 sortOrder 重排为 0..n-1，并同步文件名 */
    private void renumberRecords(String gameId, String kind) throws IOException {
        List<GameMedia> list = repo.findByGameIdAndKindOrderBySortOrderAsc(gameId, kind);
        Map<Integer, Integer> seqMap = new java.util.LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getSortOrder() != i) {
                seqMap.put(list.get(i).getSortOrder(), i);
            }
        }
        media.renumberBatch(gameId, kind, seqMap);
        for (int i = 0; i < list.size(); i++) {
            GameMedia m = list.get(i);
            if (m.getSortOrder() != i) {
                m.setPath(rewriteSeq(m.getPath(), kind, i));
                m.setSortOrder(i);
                repo.save(m);
            }
        }
    }

    /** 从 cover-3.png 解析出 3；解析不出则用 fallback */
    private int parseSeq(String filename, String kind, int fallback) {
        String prefix = kind + "-";
        if (!filename.startsWith(prefix)) {
            return fallback;
        }
        String rest = filename.substring(prefix.length());
        int dot = rest.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? rest : rest.substring(0, dot));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String filenameOf(GameMedia m) {
        String p = m.getPath();
        int q = p.indexOf('?');
        String clean = q < 0 ? p : p.substring(0, q);
        return clean.substring(clean.lastIndexOf('/') + 1);
    }

    /** 把 URL 里的序号换成新的（cover-3.png → cover-1.png），保留版本参数 */
    private String rewriteSeq(String path, String kind, int newSeq) {
        int q = path.indexOf('?');
        String base = q < 0 ? path : path.substring(0, q);
        String query = q < 0 ? "" : path.substring(q);
        int slash = base.lastIndexOf('/');
        String file = base.substring(slash + 1);
        String prefix = kind + "-";
        if (!file.startsWith(prefix)) {
            return path;
        }
        String rest = file.substring(prefix.length());
        int dot = rest.indexOf('.');
        String ext = dot < 0 ? "" : rest.substring(dot);
        return base.substring(0, slash + 1) + prefix + newSeq + ext + query;
    }
}
