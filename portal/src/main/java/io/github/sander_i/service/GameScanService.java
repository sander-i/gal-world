/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.domain.Game;
import io.github.sander_i.repo.GameRepository;
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
import java.util.Map;
import java.util.Set;
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
     * 由门户管理的 game.env 键。其余键写回时原样保留，否则在门户里改一次
     * 分辨率/启动程序就会被冲掉。
     *
     * 画质参数（{@code VIDEO_CRF} 等）不在这个集合里，而是走「实体非空才覆盖」的
     * 路径——见 {@link #writeEnv}。理由是老游戏的 game.env 里有人工调好的值，
     * 一律以 DB 为准会把它们清零。
     */
    private static final Set<String> MANAGED_KEYS = Set.of(
            "GAME_ID", "GAME_NAME", "EXE", "EXE_ALT", "WORKDIR", "WINEPREFIX", "GAME_LANG",
            "WIDTH", "HEIGHT", "DISPLAY_NUM", "PORT", "LOOPBACK_SLOT", "LOOPBACK_CARD",
            "FRAMERATE", "EXTRA_ENV");

    private final PathConfig paths;
    private final GameRepository repo;
    private final GameMediaService mediaSync;

    /** 列表页扫描节流：5 秒内的重复请求直接读库 */
    private static final long SCAN_TTL_MS = 5_000;
    private volatile long lastScanAt;

    public GameScanService(PathConfig paths, GameRepository repo, GameMediaService mediaSync) {
        this.paths = paths;
        this.repo = repo;
        this.mediaSync = mediaSync;
    }

    /**
     * 带节流的同步：列表页翻页、改筛选时用。
     *
     * 全量扫描要遍历 games 目录、每游戏读 game.env 写库、还要扫媒体目录；
     * 每翻一页做一次纯属浪费。取舍：命令行新加的游戏最多延迟 5 秒出现，
     * 页面上的「重新扫描」按钮走 {@code POST /api/games/scan}，仍然强制全量。
     */
    @Transactional
    public synchronized List<Game> syncIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_TTL_MS) {
            return repo.findAllByOrderByNameAsc();
        }
        lastScanAt = now;
        return syncAll();
    }

    @Transactional
    public List<Game> syncAll() {
        List<Game> found = new ArrayList<>();
        Path dir = paths.gamesDir();
        if (!Files.isDirectory(dir)) {
            log.warn("games 目录不存在: {}", dir);
            return found;
        }
        // 一次取全库：既用于「文件没变就跳过」的判断，也用于清理已删除的游戏
        Map<String, Game> existing = new java.util.HashMap<>();
        for (Game g : repo.findAll()) {
            existing.put(g.getId(), g);
        }
        Set<String> onDisk = new java.util.HashSet<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory)
                    // <id>.meta 是 Gal World 的附属数据目录，不是游戏
                    .filter(d -> !d.getFileName().toString().endsWith(".meta"))
                    .map(d -> d.resolve("game.env"))
                    .filter(Files::isRegularFile)
                    .forEach(envFile -> {
                        String stamp = stampOf(envFile);
                        EnvSnapshot prev = envSnapshots.get(envFile);
                        // game.env 的修改时间与大小都没变、且库里还有这行：整段跳过
                        // （不重新解析、不写库）。否则每 5 秒一次全量写回，
                        // 40 个游戏就是几百条 SQL，节流等于没节流。
                        if (stamp != null && prev != null && stamp.equals(prev.stamp())
                                && existing.containsKey(prev.id())) {
                            onDisk.add(prev.id());
                            reconcileMediaIfChanged(prev.id());
                            return;
                        }
                        Game g = syncOne(envFile);
                        if (g != null) {
                            found.add(g);
                            onDisk.add(g.getId());
                            if (stamp != null) {
                                envSnapshots.put(envFile, new EnvSnapshot(g.getId(), stamp));
                            }
                            // 磁盘上有但库里没有的媒体，顺手补上
                            reconcileMediaIfChanged(g.getId());
                        }
                    });
        } catch (IOException e) {
            log.error("扫描 games 目录失败: {}", dir, e);
        }

        // 目录已被删除的游戏，从库里摘掉，避免列表里留着已不存在的条目
        // （压测实例、手工删掉的游戏都走这条）
        List<Game> stale = existing.values().stream()
                .filter(g -> !onDisk.contains(g.getId()))
                .toList();
        for (Game g : stale) {
            log.info("游戏目录已不存在，从注册表移除: {}", g.getId());
            repo.delete(g);
            envSnapshots.values().removeIf(s -> s.id().equals(g.getId()));
            mediaDirStamps.remove(g.getId());
        }
        return found;
    }

    /** game.env 的「修改时间 + 大小」，用来判断是否真的变了 */
    private record EnvSnapshot(String id, String stamp) {
    }

    private final Map<Path, EnvSnapshot> envSnapshots = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> mediaDirStamps = new java.util.concurrent.ConcurrentHashMap<>();

    private String stampOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis() + ":" + Files.size(file);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 媒体目录没变就不重扫。
     *
     * 上传/删除媒体都会改目录的修改时间，所以这里不会漏掉变化；
     * 派生图由 GameMediaService 自己的短 TTL 缓存处理。
     */
    private void reconcileMediaIfChanged(String gameId) {
        Path mediaDir = paths.gameMediaDir(gameId);
        Long stamp;
        try {
            stamp = Files.isDirectory(mediaDir)
                    ? Files.getLastModifiedTime(mediaDir).toMillis() : -1L;
        } catch (IOException e) {
            stamp = null;
        }
        if (stamp != null && stamp.equals(mediaDirStamps.get(gameId))) {
            return;
        }
        mediaSync.reconcile(gameId);
        if (stamp != null) {
            mediaDirStamps.put(gameId, stamp);
        }
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
            // 老 game.env 没有这个键：用与 run-game.sh / Game 实体一致的默认值
            game.setFramerate(env.get("FRAMERATE", "60,30-60"));
            // 画质参数：game.env 里有就记进实体（页面上能看到），没有就留 null，
            // 表示「门户不管理这个键」。writeEnv 只写非 null 的项。
            game.setVideoCrf(parseOptionalInt(env.get("VIDEO_CRF")));
            game.setVideoPaintoverCrf(parseOptionalInt(env.get("VIDEO_PAINTOVER_CRF")));
            game.setRateControlMode(blankToNull(env.get("RATE_CONTROL_MODE")));
            game.setVideoBitrate(blankToNull(env.get("VIDEO_BITRATE")));
            game.setKeyframeInterval(parseOptionalInt(env.get("KEYFRAME_INTERVAL")));
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
        // 画质参数：实体里有值才写回（页面填过的），没有就保留 game.env 原值。
        // 放到保留循环之前，这样 entities 的值优先于文件里的旧值。
        writeOptional(env, "VIDEO_CRF", g.getVideoCrf());
        writeOptional(env, "VIDEO_PAINTOVER_CRF", g.getVideoPaintoverCrf());
        writeOptional(env, "RATE_CONTROL_MODE", g.getRateControlMode());
        writeOptional(env, "VIDEO_BITRATE", g.getVideoBitrate());
        writeOptional(env, "KEYFRAME_INTERVAL", g.getKeyframeInterval());
        // 保留门户不管理的键：人工写在 game.env 里的其它变量，重写时丢了就得重新调
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

    /** 实体非空才写进 env；null 交给保留循环用文件里的原值。 */
    private static void writeOptional(GameEnv env, String key, Object value) {
        if (value != null) {
            env.set(key, String.valueOf(value));
        }
    }

    private static Integer parseOptionalInt(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
