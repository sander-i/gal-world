/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.domain.Game;
import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.service.CompanyService;
import io.github.sander_i.service.FaviconService;
import io.github.sander_i.service.GameExeScanner;
import io.github.sander_i.service.GameMediaService;
import io.github.sander_i.service.GameScanService;
import io.github.sander_i.service.LocalizationGroupService;
import io.github.sander_i.service.MediaService;
import io.github.sander_i.service.TagsAndMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GameMetaExeTest {

    @TempDir Path root;
    GameRepository repo;
    GameScanService scan;
    GameMetaController controller;

    /** MetaRequest 的前 15 个参数与本次改动无关，统一留 null */
    private static GameMetaController.MetaRequest metaReq(String exe, String exeAlt) {
        return new GameMetaController.MetaRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, exe, exeAlt);
    }

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("games/demo"));
        Files.write(root.resolve("games/demo/game.exe"), new byte[8]);
        Files.write(root.resolve("games/demo/中文启动器.exe"), new byte[16]);
        repo = mock(GameRepository.class);
        scan = mock(GameScanService.class);
        controller = new GameMetaController(repo, mock(TagsAndMeta.class),
                mock(CompanyService.class), mock(LocalizationGroupService.class),
                mock(GameMediaService.class), mock(MediaService.class),
                new PathConfig(root.toString()), scan, new GameExeScanner(), mock(FaviconService.class),
                mock(io.github.sander_i.service.CanvasCalibrationService.class));
    }

    private Game existing() {
        Game g = new Game();
        g.setId("demo");
        g.setName("演示");
        g.setExe("/old/game.exe");
        when(repo.findById("demo")).thenReturn(Optional.of(g));
        return g;
    }

    @Test
    void exesEndpointListsCandidatesWithCurrentAndAlt() {
        Game g = existing();
        g.setExe(root.resolve("games/demo/game.exe").toString());
        g.setExeAlt(root.resolve("games/demo/中文启动器.exe").toString());

        var res = controller.exes("demo");

        assertNotNull(res.getBody());
        var body = (java.util.Map<?, ?>) res.getBody();
        assertEquals(2, ((java.util.List<?>) body.get("exes")).size());
        assertTrue(((String) body.get("current")).endsWith("game.exe"));
        assertTrue(((String) body.get("alt")).endsWith("中文启动器.exe"));
    }

    @Test
    void exesEndpointReturns404ForUnknownGame() {
        when(repo.findById("nope")).thenReturn(Optional.empty());
        assertEquals(404, controller.exes("nope").getStatusCode().value());
    }

    @Test
    void updateMetaWritesAltExeAndReportsRestartRequired() throws Exception {
        Game g = existing();

        var res = controller.updateMeta("demo", metaReq("game.exe", "中文启动器.exe"));

        assertEquals(200, res.getStatusCode().value());
        assertTrue(g.getExe().endsWith("game.exe"), g.getExe());
        assertTrue(g.getExeAlt().endsWith("中文启动器.exe"), g.getExeAlt());
        verify(scan).writeEnv(g);
        var body = (java.util.Map<?, ?>) res.getBody();
        assertEquals(Boolean.TRUE, body.get("restartRequired"));
    }

    @Test
    void clearingAltExeIsAllowed() throws Exception {
        Game g = existing();
        g.setExeAlt("/abs/cn.exe");

        controller.updateMeta("demo", metaReq(null, ""));

        assertNull(g.getExeAlt());
        verify(scan).writeEnv(g);
    }

    @Test
    void rejectsAltEqualToCurrentAndMissingFile() {
        existing();
        assertThrows(IllegalArgumentException.class,
                () -> controller.updateMeta("demo", metaReq("game.exe", "game.exe")));
        assertThrows(IllegalArgumentException.class,
                () -> controller.updateMeta("demo", metaReq("missing.exe", null)));
    }
}
