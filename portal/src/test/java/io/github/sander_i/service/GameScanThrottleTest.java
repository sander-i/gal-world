/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.domain.Game;
import io.github.sander_i.repo.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 列表页扫描节流：5 秒内不重复扫盘；文件没变时连写库都跳过 */
class GameScanThrottleTest {

    @TempDir
    Path root;

    private final List<Game> db = new ArrayList<>();
    private GameRepository repo;

    @BeforeEach
    void setUp() {
        repo = mock(GameRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        when(repo.save(any(Game.class))).thenAnswer(inv -> {
            Game g = inv.getArgument(0);
            db.add(g);
            return g;
        });
        // 模拟库里的内容随 save 变化
        when(repo.findAll()).thenAnswer(inv -> List.copyOf(db));
        when(repo.findAllByOrderByNameAsc()).thenReturn(List.of());
    }

    private GameScanService service() throws Exception {
        Path gameDir = root.resolve("games").resolve("demo");
        Files.createDirectories(gameDir);
        Files.writeString(gameDir.resolve("game.env"), String.join("\n",
                "GAME_ID=demo",
                "GAME_NAME=Demo",
                "EXE=game.exe",
                "WORKDIR=" + gameDir));
        PathConfig paths = new PathConfig(root.toString());
        return new GameScanService(paths, repo, mock(GameMediaService.class));
    }

    @Test
    void secondCallWithinTtlReusesDatabase() throws Exception {
        GameScanService scan = service();

        List<Game> first = scan.syncIfStale();
        assertEquals(1, first.size());
        verify(repo, times(1)).save(any(Game.class));

        scan.syncIfStale();
        verify(repo, times(1)).save(any(Game.class));
    }

    @Test
    void unchangedFilesSkipDatabaseWritesEvenOnForceScan() throws Exception {
        GameScanService scan = service();
        scan.syncIfStale();
        scan.syncAll();

        verify(repo, times(1)).save(any(Game.class));
    }

    @Test
    void forceSyncRescansChangedFiles() throws Exception {
        GameScanService scan = service();
        scan.syncIfStale();
        verify(repo, times(1)).save(any(Game.class));

        // game.env 变了（大小 + 修改时间）：强制扫描必须重新解析并写库
        Path env = root.resolve("games").resolve("demo").resolve("game.env");
        Files.writeString(env, Files.readString(env) + "\nFRAMERATE=60-60\n");
        Files.setLastModifiedTime(env, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        scan.syncAll();

        verify(repo, times(2)).save(any(Game.class));
    }
}
