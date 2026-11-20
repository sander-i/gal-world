/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.domain.Game;
import io.github.sander_i.repo.GameRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 门户重写 game.env 时，人工写的画质参数不能被冲掉。
 *
 * run-game.sh 从 game.env 读取 VIDEO_CRF / KEYFRAME_INTERVAL / VIDEO_BITRATE 等，
 * 而 writeEnv 只写固定字段；不保留的话，在门户里改一次分辨率就会把调参结果丢掉。
 */
class GameEnvPreserveTest {

    @TempDir Path root;

    private GameScanService service() {
        return new GameScanService(new PathConfig(root.toString()), repo(),
                mock(GameMediaService.class));
    }

    /** syncOne 会调 repo.save，返回 null 会让测试拿不到实体；save 原样返回入参即可 */
    private static GameRepository repo() {
        GameRepository r = mock(GameRepository.class);
        when(r.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        when(r.findById(anyString())).thenReturn(java.util.Optional.empty());
        return r;
    }

    private Game game(int width, int height) {
        Game g = new Game();
        g.setId("demo");
        g.setName("演示");
        g.setExe("/root/games/demo/game.exe");
        g.setWorkDir("/root/games/demo");
        g.setWinePrefix("/root/games/demo/wineprefix");
        g.setWidth(width);
        g.setHeight(height);
        g.setDisplayNum(2);
        g.setPort(9002);
        g.setLoopbackSlot(0);
        g.setFramerate("30");
        return g;
    }

    @Test
    void writeEnvKeepsManualVideoTuning() throws Exception {
        GameScanService scan = service();
        Game g = game(992, 600);
        scan.writeEnv(g);

        Path env = root.resolve("games/demo/game.env");
        Files.writeString(env, "VIDEO_CRF=8\nKEYFRAME_INTERVAL=120\n",
                StandardOpenOption.APPEND);

        // 模拟在门户里改分辨率
        g.setWidth(1280);
        g.setHeight(720);
        scan.writeEnv(g);

        String text = Files.readString(env);
        assertTrue(text.contains("WIDTH=1280"), text);
        assertTrue(text.contains("HEIGHT=720"), text);
        assertTrue(text.contains("VIDEO_CRF=8"), text);
        assertTrue(text.contains("KEYFRAME_INTERVAL=120"), text);
    }

    @Test
    void writeEnvOverridesManagedKeysFromFile() throws Exception {
        GameScanService scan = service();
        Game g = game(992, 600);
        scan.writeEnv(g);

        Path env = root.resolve("games/demo/game.env");
        // 手工改掉门户管理的键，重写时应以 DB 为准
        Files.writeString(env, "WIDTH=640\nVIDEO_CRF=8\n", StandardOpenOption.APPEND);

        scan.writeEnv(g);

        String text = Files.readString(env);
        assertTrue(text.contains("WIDTH=992"), text);
        assertEquals(1, text.lines().filter(l -> l.startsWith("WIDTH=")).count(), text);
        assertTrue(text.contains("VIDEO_CRF=8"), text);
    }

    @Test
    void 实体上填了画质参数时覆盖文件里的手写值() throws Exception {
        GameScanService scan = service();
        Game g = game(992, 600);
        scan.writeEnv(g);

        Path env = root.resolve("games/demo/game.env");
        Files.writeString(env, "VIDEO_CRF=8\nKEYFRAME_INTERVAL=120\n",
                StandardOpenOption.APPEND);

        // 页面上把两项都填了：门户接管，覆盖手写值
        g.setVideoCrf(11);
        g.setKeyframeInterval(30);
        scan.writeEnv(g);

        String text = Files.readString(env);
        assertTrue(text.contains("VIDEO_CRF=11"), text);
        assertTrue(text.contains("KEYFRAME_INTERVAL=30"), text);
        assertFalse(text.contains("VIDEO_CRF=8"), text);
        assertEquals(1, text.lines().filter(l -> l.startsWith("VIDEO_CRF=")).count(), text);
        // 没填的项仍由文件提供
        assertTrue(text.contains("VIDEO_PAINTOVER_CRF") == false, text);
    }

    @Test
    void 实体为null时保留文件里的画质参数() throws Exception {
        GameScanService scan = service();
        Game g = game(992, 600);
        scan.writeEnv(g);

        Path env = root.resolve("games/demo/game.env");
        Files.writeString(env,
                "VIDEO_CRF=9\nVIDEO_PAINTOVER_CRF=6\nRATE_CONTROL_MODE=cbr\n"
                        + "VIDEO_BITRATE=8000\nKEYFRAME_INTERVAL=45\n",
                StandardOpenOption.APPEND);

        // 只改分辨率，画质字段全为 null -> 应当原样保留
        g.setWidth(1280);
        scan.writeEnv(g);

        String text = Files.readString(env);
        assertTrue(text.contains("WIDTH=1280"), text);
        assertTrue(text.contains("VIDEO_CRF=9"), text);
        assertTrue(text.contains("VIDEO_PAINTOVER_CRF=6"), text);
        assertTrue(text.contains("RATE_CONTROL_MODE=cbr"), text);
        assertTrue(text.contains("VIDEO_BITRATE=8000"), text);
        assertTrue(text.contains("KEYFRAME_INTERVAL=45"), text);
    }

    @Test
    void 扫描时把gameenv里的画质参数读进实体() throws Exception {
        GameScanService scan = service();
        Path dir = root.resolve("games/demo");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("game.env"), String.join("\n",
                "GAME_ID=demo", "GAME_NAME=演示",
                "EXE=" + dir.resolve("game.exe"),
                "WORKDIR=" + dir,
                "WINEPREFIX=" + dir.resolve("wineprefix"),
                "WIDTH=992", "HEIGHT=600",
                "VIDEO_CRF=10", "VIDEO_PAINTOVER_CRF=7",
                "RATE_CONTROL_MODE=cbr", "VIDEO_BITRATE=9000",
                "KEYFRAME_INTERVAL=25", ""));

        Game g = scan.syncOne(dir.resolve("game.env"));

        assertNotNull(g, "syncOne 应返回实体");
        assertEquals(10, g.getVideoCrf());
        assertEquals(7, g.getVideoPaintoverCrf());
        assertEquals("cbr", g.getRateControlMode());
        assertEquals("9000", g.getVideoBitrate());
        assertEquals(25, g.getKeyframeInterval());
    }
}
