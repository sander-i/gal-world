/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.deploy.DeployRun;
import io.github.sander_i.domain.Game;
import io.github.sander_i.platform.CommandRunner;
import io.github.sander_i.repo.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameImportLifecycleTest {

    @TempDir Path root;
    GameRepository repo;
    GameScanService scan;
    SessionService sessions;
    CommandRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        Path game = root.resolve("games/sample-game");
        Files.createDirectories(game);
        Files.write(game.resolve("game.exe"), new byte[64]);

        repo = mock(GameRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.existsById(anyString())).thenReturn(false);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scan = mock(GameScanService.class);
        doAnswer(inv -> {
            Game g = inv.getArgument(0);
            Path env = root.resolve("games/" + g.getId() + "/game.env");
            Files.createDirectories(env.getParent());
            Files.writeString(env, "GAME_ID=" + g.getId() + "\n");
            return null;
        }).when(scan).writeEnv(any());

        sessions = mock(SessionService.class);
        when(sessions.unitName(anyString())).thenReturn("galworld-game@sample-game");
        when(sessions.start(anyString(), anyInt())).thenReturn(new SessionService.SessionStatus(
                "sample-game", SessionService.State.RUNNING, "running", null, false));
        when(sessions.status(anyString(), anyInt())).thenReturn(new SessionService.SessionStatus(
                "sample-game", SessionService.State.RUNNING, "running", null, false));

        runner = mock(CommandRunner.class);
        when(runner.portOpen(anyInt())).thenReturn(true);
    }

    private GameImportService buildService(GameImportService.Exec exec) {
        return new GameImportService(new PathConfig(root.toString()), repo, scan, sessions,
                runner, new GameExeScanner(), new GameSlotAllocator(),
                new GameImportValidator(new PathConfig(root.toString()), repo),
                new SaveService(new PathConfig(root.toString()), null),
                root.resolve("missing-font.ttc").toString(),
                root.resolve("wine-fonts.sh").toString(), exec);
    }

    private static void await(DeployRun run) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !run.terminal()) {
            Thread.sleep(20);
        }
    }

    @Test
    void rollbackRemovesEnvAndDatabaseRow() throws Exception {
        GameImportService service = buildService((run, argv, env2, timeout) -> 0);
        String runId = service.start("sample-game", "标题", "game.exe", 1280, 720, "30").runId();
        await(service.find(runId));

        Game saved = new Game();
        saved.setId("sample-game");
        saved.setPort(9002);
        when(repo.findById("sample-game")).thenReturn(Optional.of(saved));

        service.rollback(runId);

        verify(repo).delete(saved);
        assertFalse(Files.exists(root.resolve("games/sample-game/game.env")));
        // 游戏目录与可执行文件必须保留
        assertTrue(Files.exists(root.resolve("games/sample-game/game.exe")));
    }

    @Test
    void retryRejectedForSuccessfulRun() throws Exception {
        GameImportService service = buildService((run, argv, env2, timeout) -> 0);
        String runId = service.start("sample-game", "标题", "game.exe", 1280, 720, "30").runId();
        await(service.find(runId));
        assertThrows(IllegalStateException.class, () -> service.retry(runId));
    }

    @Test
    void cancelSetsCancelledAndRollsBack() throws Exception {
        GameImportService service = buildService((run, argv, env2, timeout) -> {
            Thread.sleep(300);
            return 0;
        });
        String runId = service.start("sample-game", "标题", "game.exe", 1280, 720, "30").runId();
        service.cancel(runId);
        DeployRun run = service.find(runId);
        await(run);
        assertEquals(DeployRun.State.CANCELLED, run.state());
        assertFalse(Files.exists(root.resolve("games/sample-game/game.env")));
    }
}
