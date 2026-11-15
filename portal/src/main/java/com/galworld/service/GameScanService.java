/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import com.galworld.config.PathConfig;
import com.galworld.domain.Game;
import com.galworld.repo.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 从 {@code ~/galworld/games/<id>/game.env} 同步游戏注册表到数据库。
 *
 * game.env 是运行时参数的唯一真相；本服务负责把它投影进 DB，
 * 并保留 DB 独有的元数据（简介、封面），不会用文件覆盖它们。
 */
@Service
public class GameScanService {

    private static final Logger log = LoggerFactory.getLogger(GameScanService.class);

    /**
     * 由门户管理的 game.env 键。其余键（画质参数如 {@code VIDEO_CRF}、
     * {@code KEYFRAME_INTERVAL}、{@code VIDEO_BITRATE} 等）写回时原样保留，
     * 否则在门户里改一次分辨率/启动程序就会被冲掉。
     */
    private static final Set<String> MANAGED_KEYS = Set.of(
            "GAME_ID", "GAME_NAME", "EXE", "EXE_ALT", "WORKDIR", "WINEPREFIX", "GAME_LANG",
            "WIDTH", "HEIGHT", "DISPLAY_NUM", "PORT", "LOOPBACK_SLOT", "LOOPBACK_CARD",
            "FRAMERATE", "EXTRA_ENV");

    private final PathConfig paths;
    private final GameRepository repo;
    private final GameMediaService mediaSync;

    public GameScanService(PathConfig paths, GameRepository repo, GameMediaService mediaSync) {
        this.paths = paths;
        this.repo = repo;
        this.mediaSync = mediaSync;
    }

    @Transactional
    public List<Game> syncAll() {
        List<Game> found = new ArrayList<>();
        Path dir = paths.gamesDir();
        if (!Files.isDirectory(dir)) {
            log.warn("games 目录不存在: {}", dir);
            return found;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory)
                    // <id>.meta 是 Gal World 的附属数据目录，不是游戏
                    .filter(d -> !d.getFileName().toString().endsWith(".meta"))
                    .map(d -> d.resolve("game.env"))
                    .filter(Files::isRegularFile)
                    .forEach(envFile -> {
                        Game g = syncOne(envFile);
                        if (g != null) {
                            found.add(g);
                            // 磁盘上有但库里没有的媒体，顺手补上
                            mediaSync.reconcile(g.getId());
                        }
                    });
        } catch (IOException e) {
            log.error("扫描 games 目录失败: {}", dir, e);
        }

        // 目录已被删除的游戏，从库里摘掉，避免列表里留着已不存在的条目
        // （压测实例、手工删掉的游戏都走这条）
        Set<String> onDisk = found.stream().map(Game::getId).collect(Collectors.toSet());
        List<Game> stale = repo.findAll().stream()
                .filter(g -> !onDisk.contains(g.getId()))
                .toList();
        for (Game g : stale) {
            log.info("游戏目录已不存在，从注册表移除: {}", g.getId());
            repo.delete(g);
        }
        return found;
    }

    @Transactional
    public Game syncOne(Path envFile) {
        try {
            GameEnv env = GameEnv.parse(Files.readAllLines(envFile));
            String id = env.get("GAME_ID", envFile.getParent().getFileName().toString());
            Game game = repo.findById(id).orElseGet(Game::new);
            boolean isNew = game.getId() == null;
            game.setId(id);
            // 名称只在首次注册时读 GAME_NAME：之后用户在门户改过的名称以 DB 为准，
            // 否则每次列表扫描都会把改名冲掉
            if (isNew) {
                game.setName(env.get("GAME_NAME", id));
            } else if (game.getName() == null || game.getName().isBlank()) {
                game.setName(env.get("GAME_NAME", id));
            }
            game.setExe(env.get("EXE", ""));
            game.setExeAlt(env.get("EXE_ALT", ""));
            game.setWorkDir(env.get("WORKDIR", envFile.getParent().toString()));
            game.setWinePrefix(env.get("WINEPREFIX", ""));
            game.setGameLang(env.get("GAME_LANG", ""));
            game.setWidth(env.getInt("WIDTH", 1280));
            game.setHeight(env.getInt("HEIGHT", 720));
            game.setDisplayNum(env.getInt("DISPLAY_NUM", 2));
            game.setPort(env.getInt("PORT", 9000 + env.getInt("DISPLAY_NUM", 2)));
            game.setLoopbackSlot(env.getInt("LOOPBACK_SLOT", 0));
            // 老 game.env 没有这个键：默认卡 0，行为与扩容前完全一致
            game.setLoopbackCard(env.getInt("LOOPBACK_CARD", 0));
            game.setFramerate(env.get("FRAMERATE", "30-30"));
            game.setOnDisk(true);
            Instant now = Instant.now();
            if (isNew) {
                game.setCreatedAt(now);
            }
            game.setUpdatedAt(now);
            return repo.save(game);
        } catch (IOException e) {
            log.error("读取 game.env 失败: {}", envFile, e);
            return null;
        }
    }

    /** 写入 game.env（保持 run-game.sh 可直接 source） */
    public void writeEnv(Game g) throws IOException {
        Path envFile = paths.gameEnv(g.getId());
        Files.createDirectories(envFile.getParent());
        GameEnv env = GameEnv.empty()
                .set("GAME_ID", g.getId())
                .set("GAME_NAME", g.getName())
                .set("EXE", g.getExe())
                .set("EXE_ALT", g.getExeAlt() == null ? "" : g.getExeAlt())
                .set("WORKDIR", g.getWorkDir())
                .set("WINEPREFIX", g.getWinePrefix())
                // 空值等价于「跟随默认 C.UTF-8」，run-game.sh 用 ${GAME_LANG:-C.UTF-8} 取值
                .set("GAME_LANG", g.getGameLang() == null ? "" : g.getGameLang())
                .set("WIDTH", String.valueOf(g.getWidth()))
                .set("HEIGHT", String.valueOf(g.getHeight()))
                .set("DISPLAY_NUM", String.valueOf(g.getDisplayNum()))
                .set("PORT", String.valueOf(g.getPort()))
                .set("LOOPBACK_SLOT", String.valueOf(g.getLoopbackSlot()))
                .set("LOOPBACK_CARD", String.valueOf(g.getLoopbackCard()))
                .set("FRAMERATE", g.getFramerate())
                // run-game.sh 用 set -u，缺这个键会直接退出（status=127）
                .set("EXTRA_ENV", "");
        // 保留门户不管理的键：画质参数（VIDEO_CRF / KEYFRAME_INTERVAL / VIDEO_BITRATE …）
        // 由人工写在 game.env 里，重写时丢了就得重新调参
        if (Files.isRegularFile(envFile)) {
            GameEnv existing = GameEnv.parse(Files.readAllLines(envFile));
            for (String key : existing.keys()) {
                if (!MANAGED_KEYS.contains(key) && env.get(key) == null) {
                    env.set(key, existing.get(key));
                }
            }
        }
        Files.write(envFile, env.render());
    }
}
