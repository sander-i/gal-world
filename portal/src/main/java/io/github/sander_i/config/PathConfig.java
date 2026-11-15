/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Gal World 运行时目录，默认 {@code ~/galworld}，可用环境变量 GALWORLD_ROOT 覆盖。 */
@Configuration
public class PathConfig {

    private final Path root;

    public PathConfig(@Value("${galworld.root:${user.home}/galworld}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path gamesDir() {
        return root.resolve("games");
    }

    public Path gameDir(String id) {
        return gamesDir().resolve(id);
    }

    public Path gameEnv(String id) {
        return gameDir(id).resolve("game.env");
    }

    /**
     * Gal World 的附属数据目录（媒体等），**与游戏本体目录同级**、不放在游戏目录里。
     *
     * 理由：游戏本体要保持原样，多塞任何文件都可能触发引擎异常；
     * 同级目录又能保证「删游戏时附属数据跟着一起处理」。
     * 形如 ~/galworld/games/sample-game.meta/media/。
     */
    public Path gameMetaDir(String id) {
        return gamesDir().resolve(id + ".meta");
    }

    public Path gameMediaDir(String id) {
        return gameMetaDir(id).resolve("media");
    }

    public Path dataDir() {
        return root.resolve("data");
    }

    public Path logsDir() {
        return dataDir().resolve("logs");
    }

    /**
     * 浏览器上传的临时目录。必须与 {@link #gamesDir()} 在同一个文件系统上：
     * 解压完成后用 ATOMIC_MOVE 整体搬进 games/，跨盘会退化成递归复制。
     */
    public Path importTmpDir() {
        return dataDir().resolve("import-tmp");
    }

    /**
     * 游戏打包下载的临时产物目录。与 games/ 同盘，避免搬动大文件时跨分区复制。
     * 打包完成的 zip 只保留一段时间，由 {@code GameDownloadService} 定期清理。
     */
    public Path downloadDir() {
        return dataDir().resolve("downloads");
    }
}
