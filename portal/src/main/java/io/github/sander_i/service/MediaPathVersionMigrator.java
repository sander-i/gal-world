/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.domain.Game;
import io.github.sander_i.domain.GameMedia;
import io.github.sander_i.repo.GameMediaRepository;
import io.github.sander_i.repo.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 存量媒体 URL 版本号补齐。
 *
 * 历史数据的媒体 URL 没有 {@code ?v=} 版本参数（如 {@code /media/sample-game/cover-0.png}），
 * MediaController 对无版本参数的请求只能返回 {@code Cache-Control: no-cache}，
 * 每次访问都要回源校验。这里在启动时扫描数据库里所有媒体路径，
 * 按文件内容算出与上传路径完全一致的版本号并写回，之后即可长缓存。
 *
 * 幂等：已含 {@code ?} 的路径一律跳过；任何异常都只记日志，绝不阻断应用启动。
 * 必须在 {@link ImageVariantBackfill}（@Order(100)）之前执行，保证派生图回填时不重复处理。
 */
@Component
@Order(50)
public class MediaPathVersionMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MediaPathVersionMigrator.class);

    /** 读取文件计算摘要时的缓冲区大小 */
    private static final int BUFFER_SIZE = 64 * 1024;

    private final GameRepository games;
    private final GameMediaRepository mediaRepo;
    private final PathConfig paths;

    public MediaPathVersionMigrator(GameRepository games, GameMediaRepository mediaRepo,
                                    PathConfig paths) {
        this.games = games;
        this.mediaRepo = mediaRepo;
        this.paths = paths;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("存量媒体版本号补齐开始");
            int gameFixed = migrateGames();
            int mediaFixed = migrateMedia();
            log.info("存量媒体版本号补齐结束，游戏字段补齐 {} 条，媒体记录补齐 {} 条",
                    gameFixed, mediaFixed);
        } catch (Exception e) {
            // 迁移失败不影响应用启动，下次启动会再尝试
            log.warn("存量媒体版本号补齐中止: {}", e.toString());
        }
    }

    /** 处理 Game 上的 5 个媒体路径字段，返回补齐条数 */
    private int migrateGames() {
        int fixed = 0;
        for (Game game : games.findAll()) {
            String gameId = game.getId();
            if (gameId == null || gameId.isBlank()) {
                continue;
            }
            List<Consumer<String>> setters = new ArrayList<>();
            List<String> values = List.of(
                    nullToEmpty(game.getCoverPath()),
                    nullToEmpty(game.getIconPath()),
                    nullToEmpty(game.getCgPath()),
                    nullToEmpty(game.getCgHighPath()),
                    nullToEmpty(game.getCgLowPath()));
            setters.add(game::setCoverPath);
            setters.add(game::setIconPath);
            setters.add(game::setCgPath);
            setters.add(game::setCgHighPath);
            setters.add(game::setCgLowPath);

            boolean changed = false;
            for (int i = 0; i < values.size(); i++) {
                String oldUrl = values.get(i);
                String newUrl = withVersion(gameId, oldUrl);
                if (!newUrl.equals(oldUrl)) {
                    setters.get(i).accept(newUrl);
                    changed = true;
                    fixed++;
                }
            }
            if (changed) {
                games.save(game);
            }
        }
        return fixed;
    }

    /** 处理 GameMedia 记录上的路径，返回补齐条数 */
    private int migrateMedia() {
        int fixed = 0;
        for (GameMedia media : mediaRepo.findAll()) {
            String gameId = media.getGameId();
            String oldUrl = media.getPath();
            if (gameId == null || gameId.isBlank() || oldUrl == null || oldUrl.isBlank()) {
                continue;
            }
            String newUrl = withVersion(gameId, oldUrl);
            if (!newUrl.equals(oldUrl)) {
                media.setPath(newUrl);
                mediaRepo.save(media);
                fixed++;
            }
        }
        return fixed;
    }

    /**
     * 给单个媒体 URL 补齐 {@code ?v=} 版本参数。
     *
     * 缺失、已带查询参数（视为已经补过）、无法定位文件时原样返回。
     * URL 形如 {@code /media/<gameId>/<filename>}，目录以实体 id 为准，文件名取最后一段。
     */
    private String withVersion(String gameId, String url) {
        try {
            if (url == null || url.isBlank() || url.indexOf('?') >= 0) {
                return url;
            }
            int slash = url.lastIndexOf('/');
            String filename = slash >= 0 ? url.substring(slash + 1) : url;
            if (filename.isBlank() || filename.contains("..")) {
                return url;
            }
            Path mediaDir = paths.gameMediaDir(gameId);
            Path file = mediaDir.resolve(filename).normalize();
            // 防越界：解析后的文件必须仍在媒体目录内
            if (!mediaDir.equals(file.getParent()) || !Files.isRegularFile(file)) {
                return url;
            }
            return url + "?v=" + versionOf(file);
        } catch (Exception e) {
            log.warn("补齐媒体版本号失败 gameId={} url={}: {}", gameId, url, e.toString());
            return url;
        }
    }

    /** 由文件内容摘要生成 8 位版本号，与 MediaService.versionOf 的算法逐字节一致 */
    private String versionOf(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        byte[] h = md.digest();
        return String.format("%08x", ByteBuffer.wrap(h, 0, 4).getInt() & 0xffffffffL);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
