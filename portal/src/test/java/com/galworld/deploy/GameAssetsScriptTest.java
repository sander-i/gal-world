/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.deploy;

import com.galworld.config.PathConfig;
import com.galworld.platform.OsKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * game-assets.sh 的行为回归。
 *
 * 回归点：汉化补丁把 exe 放进 补丁/<补丁名>/，资源留在游戏根目录时，
 * Kirikiri 按 exe 目录找 plugin/extrans.dll 失败，进程直接退出
 * （日志只有 Exit code: 0）。脚本要把缺失资源以符号链接补进 exe 目录。
 */
class GameAssetsScriptTest {

    @TempDir
    Path root;

    /** 从 jar 资产里取出脚本，模拟部署后的 ~/galworld/bin */
    private Path installScript() throws IOException {
        Path bin = root.resolve("bin");
        Files.createDirectories(bin);
        Path target = bin.resolve("game-assets.sh");
        new DeployAssets(new PathConfig(root.toString())).install("install-game-units");
        assertTrue(Files.isRegularFile(target), "game-assets.sh 未随 install-game-units 落盘");
        return target;
    }

    /** 脚本是 bash：Windows 上没装 Git Bash（或没进 PATH）时跳过，而不是当成失败 */
    private static boolean bashUsable() {
        try {
            Process p = new ProcessBuilder("bash", "-c", "true").redirectErrorStream(true).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private void run(Path script, Path workDir, Path exeDir) throws Exception {
        assumeTrue(bashUsable(), "需要 bash，Windows 请把 Git Bash 加入 PATH");
        Process p = new ProcessBuilder("bash", script.toString(),
                workDir.toString(), exeDir.toString())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "脚本超时");
        assertEquals(0, p.exitValue(), "脚本退出码非 0: " + out);
    }

    private Path gameDir() throws IOException {
        Path game = root.resolve("games/demo");
        Files.createDirectories(game);
        Files.writeString(game.resolve("data.xp3"), "xp3");
        Files.createDirectories(game.resolve("plugin"));
        Files.writeString(game.resolve("plugin/extrans.dll"), "dll");
        return game;
    }

    @Test
    void 子目录exe补齐工作目录顶层资源() throws Exception {
        assumeTrue(OsKind.current() == OsKind.LINUX, "符号链接语义只在 Linux 上验证");
        Path script = installScript();
        Path game = gameDir();
        Path patch = game.resolve("补丁/共通线补丁");
        Files.createDirectories(patch);
        Files.writeString(patch.resolve("start.exe"), "exe");

        run(script, game, patch);

        assertTrue(Files.isSymbolicLink(patch.resolve("data.xp3")), "data.xp3 应补为链接");
        assertEquals(game.resolve("data.xp3"), Files.readSymbolicLink(patch.resolve("data.xp3")));
        assertTrue(Files.isSymbolicLink(patch.resolve("plugin")), "plugin 应补为链接");
        // 祖先目录 补丁 不能链进来，否则形成自引用
        assertFalse(Files.exists(patch.resolve("补丁"), java.nio.file.LinkOption.NOFOLLOW_LINKS),
                "不应把祖先目录补进 exe 目录");
        // 补丁自带的 exe 不能被覆盖
        assertFalse(Files.isSymbolicLink(patch.resolve("start.exe")));
    }

    @Test
    void 重复执行幂等且不覆盖已有条目() throws Exception {
        assumeTrue(OsKind.current() == OsKind.LINUX, "符号链接语义只在 Linux 上验证");
        Path script = installScript();
        Path game = gameDir();
        Path patch = game.resolve("补丁/p");
        Files.createDirectories(patch);
        // 补丁自带的同名资源：内容不同，不能被链接覆盖
        Files.writeString(patch.resolve("data.xp3"), "patched");

        run(script, game, patch);
        run(script, game, patch);

        assertFalse(Files.isSymbolicLink(patch.resolve("data.xp3")), "已有实体文件不应被替换");
        assertEquals("patched", Files.readString(patch.resolve("data.xp3")));
        assertTrue(Files.isSymbolicLink(patch.resolve("plugin")));
    }

    @Test
    void 门户配置与前缀不补进exe目录() throws Exception {
        assumeTrue(OsKind.current() == OsKind.LINUX, "符号链接语义只在 Linux 上验证");
        Path script = installScript();
        Path game = gameDir();
        Files.writeString(game.resolve("game.env"), "GAME_ID=demo");
        Files.createDirectories(game.resolve("wineprefix"));
        Files.writeString(game.resolve(".hidden"), "x");
        Path patch = game.resolve("补丁/p");
        Files.createDirectories(patch);

        run(script, game, patch);

        assertTrue(Files.isSymbolicLink(patch.resolve("data.xp3")));
        for (String skip : List.of("game.env", "wineprefix", ".hidden")) {
            assertFalse(Files.exists(patch.resolve(skip), java.nio.file.LinkOption.NOFOLLOW_LINKS),
                    skip + " 不应被补进 exe 目录");
        }
    }

    @Test
    void exe就在工作目录时什么都不做() throws Exception {
        Path script = installScript();
        Path game = gameDir();
        int before = Files.list(game).toList().size();

        run(script, game, game);

        assertEquals(before, Files.list(game).toList().size(), "同目录不应新增条目");
    }

    @Test
    void exe不在工作目录之下时跳过() throws Exception {
        Path script = installScript();
        Path game = gameDir();
        Path outside = root.resolve("elsewhere");
        Files.createDirectories(outside);

        run(script, game, outside);

        assertEquals(0, Files.list(outside).toList().size(), "越界目录不应被写入");
    }

    @Test
    void 脚本随jar资产分发且两份run_game保持一致() throws Exception {
        Path jarCopy = Path.of("src/main/resources/deploy/linux/bin/game-assets.sh");
        Path repoCopy = Path.of("../deploy/bin/game-assets.sh");
        assumeTrue(Files.isRegularFile(jarCopy) && Files.isRegularFile(repoCopy),
                "需要在 portal/ 目录下运行");
        assertEquals(Files.readString(repoCopy), Files.readString(jarCopy),
                "deploy/bin 与 jar 内副本必须逐字节一致");

        Path jarRun = Path.of("src/main/resources/deploy/linux/bin/run-game.sh");
        Path repoRun = Path.of("../deploy/bin/run-game.sh");
        assertEquals(Files.readString(repoRun), Files.readString(jarRun),
                "run-game.sh 两份副本必须逐字节一致");
        assertTrue(Files.readString(repoRun).contains("game-assets.sh"),
                "run-game.sh 必须在启动前调用 game-assets.sh");
    }

    @Test
    void 目标机脚本在仓库与jar内逐字节一致() throws Exception {
        // 两份副本分别给 deploy/portal-deploy.sh（scp 到目标机）和 DeployAssets（从 jar 落盘）用，
        // 一旦漂移就会出现“页面装的脚本和仓库里的不一样”这种很难查的问题。
        List<String> shared = List.of(
                "run-game.sh", "stop-game.sh", "wine-fonts.sh", "game-assets.sh",
                "build-wow64-wine.sh", "pulse-daemon.sh", "pulse-setup.sh",
                "updater.sh", "backup.sh", "galworld", "gen-stream-cert.sh");
        for (String name : shared) {
            Path repoCopy = Path.of("../deploy/bin", name);
            Path jarCopy = Path.of("src/main/resources/deploy/linux/bin", name);
            assumeTrue(Files.isRegularFile(repoCopy), "需要在 portal/ 目录下运行，缺 " + name);
            assertTrue(Files.isRegularFile(jarCopy), "jar 内缺少脚本: " + name);
            assertEquals(Files.readString(repoCopy), Files.readString(jarCopy),
                    name + " 在 deploy/bin 与 jar 内不一致");
        }
    }

    @Test
    void run_game在wine启动前调用资源补齐() throws Exception {
        Path repoRun = Path.of("../deploy/bin/run-game.sh");
        assumeTrue(Files.isRegularFile(repoRun), "需要在 portal/ 目录下运行");
        List<String> lines = Files.readAllLines(repoRun);
        int assetsAt = -1;
        int wineAt = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (assetsAt < 0 && line.contains("ASSETS_FIX")) {
                assetsAt = i;
            }
            if (wineAt < 0 && line.contains("wine \"$EXE\"")) {
                wineAt = i;
            }
        }
        assertTrue(assetsAt > 0, "run-game.sh 未调用 game-assets.sh");
        assertTrue(wineAt > assetsAt, "资源补齐必须在 wine 启动之前");
    }

    @Test
    void run_game开启msgbox通道并检测阻塞对话框() throws Exception {
        Path repoRun = Path.of("../deploy/bin/run-game.sh");
        assumeTrue(Files.isRegularFile(repoRun), "需要在 portal/ 目录下运行");
        String text = Files.readString(repoRun);

        // 默认 -all 会把 msgbox 一起关掉，必须显式打开这条通道
        assertTrue(text.contains("+msgbox"), "run-game.sh 未开启 +msgbox 通道");
        // 抓到 MSGBOX_OnInit 要落一条可读的 WARN，而不是只留在 trace 里
        assertTrue(text.contains("MSGBOX_OnInit"), "未检测 MSGBOX_OnInit");
        assertTrue(text.contains("阻塞对话框"), "缺少阻塞对话框告警文案");
        assertTrue(text.contains("KrkrUniversalPatch.dll"), "告警应给出常见原因示例");
    }
}
