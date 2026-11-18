/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

/**
 * 把 jar 内打包的 Linux 部署资产（bin 脚本、systemd 单元）落盘。
 *
 * 为什么不把脚本写成命令字符串：脚本有几百行，集中放在
 * {@code resources/deploy/linux/} 下便于审阅和随仓库版本管理；
 * 引擎只负责复制 + 赋权，命令表里只保留 chmod / daemon-reload 这类短命令。
 */
@Component
public class DeployAssets {

    private static final Logger log = LoggerFactory.getLogger(DeployAssets.class);

    private final PathConfig paths;

    public DeployAssets(PathConfig paths) {
        this.paths = paths;
    }

    /** 需要安装到 ~/galworld/bin 的脚本 */
    public static final List<String> PULSE_BIN = List.of(
            "pulse-daemon.sh", "pulse-setup.sh", "selfcheck.sh");
    public static final List<String> GAME_BIN = List.of(
            "run-game.sh", "stop-game.sh", "wine-fonts.sh", "game-assets.sh",
            "build-wow64-wine.sh");
    /**
     * 门户自身的运维脚本：远程更新、备份恢复、游戏 CLI、串流证书。
     *
     * 这几个不参与音视频链路，但缺一个就会让对应功能在页面上直接报错——
     * 例如没有 updater.sh 时「检查更新」抛“更新器不存在”，
     * 没有 backup.sh 时 README 里的备份命令跑不起来。
     */
    public static final List<String> PORTAL_BIN = List.of(
            "updater.sh", "backup.sh", "galworld", "gen-stream-cert.sh");
    /** 需要安装到 ~/.config/systemd/user 的单元 */
    public static final List<String> PULSE_UNITS = List.of("galworld-pulse.service");
    public static final List<String> GAME_UNITS = List.of("galworld-game@.service");
    public static final List<String> PORTAL_UNITS = List.of("galworld-portal.service");

    /** 按 token 安装对应资产，返回写入的文件数 */
    public int install(String token) throws IOException {
        return switch (token) {
            case "install-pulse-scripts" -> bin(PULSE_BIN) + units(PULSE_UNITS);
            case "install-game-units" -> bin(GAME_BIN) + units(GAME_UNITS);
            case "install-portal-scripts" -> bin(PORTAL_BIN);
            case "install-portal-unit" -> units(PORTAL_UNITS);
            default -> 0;
        };
    }

    private int bin(List<String> names) throws IOException {
        Path dir = paths.root().resolve("bin");
        Files.createDirectories(dir);
        int n = 0;
        for (String name : names) {
            Path target = dir.resolve(name);
            copy("deploy/linux/bin/" + name, target);
            chmodX(target);
            n++;
        }
        log.info("已安装 {} 个脚本到 {}", n, dir);
        return n;
    }

    private int units(List<String> names) throws IOException {
        Path dir = Path.of(System.getProperty("galworld.deploy.unit-dir",
                Path.of(System.getProperty("user.home"), ".config", "systemd", "user").toString()));
        Files.createDirectories(dir);
        int n = 0;
        for (String name : names) {
            copy("deploy/linux/systemd/" + name, dir.resolve(name));
            n++;
        }
        log.info("已安装 {} 个 systemd 单元到 {}", n, dir);
        return n;
    }

    private void copy(String resource, Path target) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException(I18n.t("jar 内缺少资源: {0}", resource));
            }
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void chmodX(Path file) {
        try {
            Set<PosixFilePermission> perms = new java.util.HashSet<>(
                    Files.getPosixFilePermissions(file));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException e) {
            // 非 POSIX 文件系统（例如开发机的 Windows）：忽略，目标平台是 Linux
            log.debug("跳过 chmod +x {}: {}", file, e.toString());
        }
    }
}
