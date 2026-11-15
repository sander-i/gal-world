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
import static org.mockito.Mockito.mock;

/**
 * 门户重写 game.env 时，人工写的画质参数不能被冲掉。
 *
 * run-game.sh 从 game.env 读取 VIDEO_CRF / KEYFRAME_INTERVAL / VIDEO_BITRATE 等，
 * 而 writeEnv 只写固定字段；不保留的话，在门户里改一次分辨率就会把调参结果丢掉。
 */
class GameEnvPreserveTest {

    @TempDir Path root;

    private GameScanService service() {
        return new GameScanService(new PathConfig(root.toString()), mock(GameRepository.class),
                mock(GameMediaService.class));
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
}
