/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.deploy;

import com.galworld.config.PathConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** jar 内 Linux 资产落盘（脚本 + systemd 单元）的回归测试。 */
class DeployAssetsTest {

    @TempDir
    Path root;

    @TempDir
    Path units;

    @Test
    void installsPulseScriptsAndUnit() throws Exception {
        System.setProperty("galworld.deploy.unit-dir", units.toString());
        try {
            DeployAssets assets = new DeployAssets(new PathConfig(root.toString()));
            int n = assets.install("install-pulse-scripts");
            assertEquals(4, n, "3 个脚本 + 1 个 unit");
            for (String name : DeployAssets.PULSE_BIN) {
                Path f = root.resolve("bin").resolve(name);
                assertTrue(Files.isRegularFile(f), "缺少 " + name);
                assertTrue(Files.size(f) > 100, name + " 内容为空");
            }
            String daemon = Files.readString(root.resolve("bin/pulse-daemon.sh"));
            assertTrue(daemon.startsWith("#!/usr/bin/env bash"), "脚本 shebang 不对");
            assertTrue(Files.isRegularFile(units.resolve("galworld-pulse.service")));
        } finally {
            System.clearProperty("galworld.deploy.unit-dir");
        }
    }

    @Test
    void installsGameScriptsAndUnit() throws Exception {
        System.setProperty("galworld.deploy.unit-dir", units.toString());
        try {
            DeployAssets assets = new DeployAssets(new PathConfig(root.toString()));
            assertEquals(6, assets.install("install-game-units"));
            assertTrue(Files.isRegularFile(root.resolve("bin/run-game.sh")));
            assertTrue(Files.isRegularFile(root.resolve("bin/stop-game.sh")));
            // 字体兜底脚本必须随游戏脚本一起落盘，否则 run-game.sh 只剩 WARN
            assertTrue(Files.isRegularFile(root.resolve("bin/wine-fonts.sh")));
            // 子目录 exe 的资源补齐：run-game.sh 启动前调用，缺它补丁游戏会秒退
            assertTrue(Files.isRegularFile(root.resolve("bin/game-assets.sh")));
            // wow64 wine 的构建脚本：一键部署的「构建 wow64 Wine」步骤要用
            assertTrue(Files.isRegularFile(root.resolve("bin/build-wow64-wine.sh")));
            assertTrue(Files.isRegularFile(units.resolve("galworld-game@.service")));
        } finally {
            System.clearProperty("galworld.deploy.unit-dir");
        }
    }

    @Test
    void installsPortalScripts() throws Exception {
        DeployAssets assets = new DeployAssets(new PathConfig(root.toString()));
        assertEquals(4, assets.install("install-portal-scripts"), "4 个运维脚本");
        // 缺 updater.sh 时「检查更新」会抛“更新器不存在”，其余三个同理
        assertTrue(Files.isRegularFile(root.resolve("bin/updater.sh")));
        assertTrue(Files.isRegularFile(root.resolve("bin/backup.sh")));
        assertTrue(Files.isRegularFile(root.resolve("bin/galworld")));
        assertTrue(Files.isRegularFile(root.resolve("bin/gen-stream-cert.sh")));
    }

    @Test
    void unknownTokenInstallsNothing() throws Exception {
        DeployAssets assets = new DeployAssets(new PathConfig(root.toString()));
        assertEquals(0, assets.install("apt-packages"));
    }
}
