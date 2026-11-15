/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import com.galworld.config.PathConfig;
import com.galworld.domain.Game;
import com.galworld.repo.GameRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameEnvAltExeTest {

    @TempDir Path root;

    private GameScanService service(GameRepository repo) {
        return new GameScanService(new PathConfig(root.toString()), repo,
                mock(GameMediaService.class));
    }

    @Test
    void writeEnvPersistsAltExeAndExtraEnv() throws Exception {
        Game g = new Game();
        g.setId("demo");
        g.setName("演示");
        g.setExe("/root/games/demo/game.exe");
        g.setExeAlt("/root/games/demo/中文启动器.exe");
        g.setWorkDir("/root/games/demo");
        g.setWinePrefix("/root/games/demo/wineprefix");
        g.setDisplayNum(2);
        g.setPort(9002);
        g.setLoopbackSlot(0);
        g.setFramerate("30-30");

        service(mock(GameRepository.class)).writeEnv(g);

        String text = Files.readString(root.resolve("games/demo/game.env"));
        assertTrue(text.contains("EXE_ALT=/root/games/demo/中文启动器.exe"), text);
        assertTrue(text.contains("EXTRA_ENV="), text);
        // 未设置时也要落空键：run-game.sh 用 ${GAME_LANG:-C.UTF-8} 兜底
        assertTrue(text.contains("GAME_LANG="), text);
    }

    @Test
    void writeEnvPersistsGameLang() throws Exception {
        Game g = new Game();
        g.setId("demo");
        g.setName("演示");
        g.setExe("/root/games/demo/game.exe");
        g.setWorkDir("/root/games/demo");
        g.setWinePrefix("/root/games/demo/wineprefix");
        g.setGameLang("zh_CN.UTF-8");

        service(mock(GameRepository.class)).writeEnv(g);

        String text = Files.readString(root.resolve("games/demo/game.env"));
        assertTrue(text.contains("GAME_LANG=zh_CN.UTF-8"), text);
    }

    @Test
    void scanReadsGameLangBack() throws Exception {
        Path dir = root.resolve("games/demo");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("game.env"), String.join("\n",
                "GAME_ID=demo", "GAME_NAME=演示",
                "EXE=/root/games/demo/game.exe",
                "WORKDIR=/root/games/demo",
                "WINEPREFIX=/root/games/demo/wineprefix",
                "GAME_LANG=zh_CN.UTF-8",
                "DISPLAY_NUM=2", "PORT=9002", "LOOPBACK_SLOT=0", "FRAMERATE=30-30", ""));

        GameRepository repo = mock(GameRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Game g = service(repo).syncOne(dir.resolve("game.env"));
        assertEquals("zh_CN.UTF-8", g.getGameLang());
    }

    @Test
    void scanReadsAltExeBack() throws Exception {
        Path dir = root.resolve("games/demo");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("game.env"), String.join("\n",
                "GAME_ID=demo",
                "GAME_NAME=演示",
                "EXE=/root/games/demo/game.exe",
                "EXE_ALT=/root/games/demo/cn.exe",
                "WORKDIR=/root/games/demo",
                "WINEPREFIX=/root/games/demo/wineprefix",
                "DISPLAY_NUM=2", "PORT=9002", "LOOPBACK_SLOT=0", "FRAMERATE=30-30", ""));

        GameRepository repo = mock(GameRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Game g = service(repo).syncOne(dir.resolve("game.env"));
        assertEquals("/root/games/demo/cn.exe", g.getExeAlt());
    }
}
