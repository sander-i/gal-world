/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.domain.Game;
import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.service.CanvasCalibrationService;
import io.github.sander_i.service.GameScanService;
import io.github.sander_i.service.TagsAndMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GameMetaResolutionTest {

    @TempDir
    Path root;

    private GameRepository repo;
    private GameScanService scan;
    private CanvasCalibrationService calibration;
    private GameMetaController controller;

    @BeforeEach
    void setUp() {
        repo = mock(GameRepository.class);
        scan = mock(GameScanService.class);
        calibration = mock(CanvasCalibrationService.class);
        controller = new GameMetaController(repo, mock(TagsAndMeta.class),
                mock(io.github.sander_i.service.CompanyService.class),
                mock(io.github.sander_i.service.LocalizationGroupService.class),
                mock(io.github.sander_i.service.GameMediaService.class),
                mock(io.github.sander_i.service.MediaService.class),
                new PathConfig(root.toString()), scan,
                new io.github.sander_i.service.GameExeScanner(),
                mock(io.github.sander_i.service.FaviconService.class), calibration);

        Game g = new Game();
        g.setId("demo");
        g.setName("演示");
        g.setWidth(1280);
        g.setHeight(720);
        when(repo.findById("demo")).thenReturn(Optional.of(g));
    }

    private static GameMetaController.MetaRequest req(Integer width, Integer height) {
        return new GameMetaController.MetaRequest(
                null, width, height, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void 超出范围的画布被拒绝且不写库() {
        var res = controller.updateMeta("demo", req(4000, 720));

        assertEquals(400, res.getStatusCode().value());
        verify(repo, never()).save(any());
        verifyNoInteractions(scan, calibration);
    }

    @Test
    void 合法画布保存后重置校准状态() throws Exception {
        var res = controller.updateMeta("demo", req(992, 624));

        assertEquals(200, res.getStatusCode().value());
        verify(calibration).reset("demo");
        verify(scan).writeEnv(any(Game.class));
    }

    @Test
    void 分辨率未变化时不重置校准状态() throws Exception {
        var res = controller.updateMeta("demo", req(1280, 720));

        assertEquals(200, res.getStatusCode().value());
        verify(calibration, never()).reset(anyString());
        verify(scan, never()).writeEnv(any(Game.class));
    }
}
