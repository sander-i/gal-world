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

/**
 * 画质参数的保存与校验。
 *
 * 语义核心：字段为 null 表示「门户不管理这个键」，写 game.env 时跳过，
 * 文件里人工写的值原样保留。页面上留空保存不能把它们冲掉。
 */
class GameMetaQualityTest {

    @TempDir
    Path root;

    private GameRepository repo;
    private GameScanService scan;
    private GameMetaController controller;
    private Game game;

    @BeforeEach
    void setUp() {
        repo = mock(GameRepository.class);
        scan = mock(GameScanService.class);
        controller = new GameMetaController(repo, mock(TagsAndMeta.class),
                mock(io.github.sander_i.service.CompanyService.class),
                mock(io.github.sander_i.service.LocalizationGroupService.class),
                mock(io.github.sander_i.service.GameMediaService.class),
                mock(io.github.sander_i.service.MediaService.class),
                new PathConfig(root.toString()), scan,
                new io.github.sander_i.service.GameExeScanner(),
                mock(io.github.sander_i.service.FaviconService.class),
                mock(CanvasCalibrationService.class));

        game = new Game();
        game.setId("demo");
        game.setName("演示");
        game.setWidth(1280);
        game.setHeight(720);
        when(repo.findById("demo")).thenReturn(Optional.of(game));
    }

    /** 只传画质五项，其余留 null */
    private static GameMetaController.MetaRequest qualityReq(
            Integer crf, Integer paintover, String mode, String bitrate, Integer keyframe) {
        return new GameMetaController.MetaRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                crf, paintover, mode, bitrate, keyframe);
    }

    @Test
    void 保存画质参数后写入实体并落盘() throws Exception {
        var res = controller.updateMeta("demo",
                qualityReq(10, 6, "cbr", "8000", 30));

        assertEquals(200, res.getStatusCode().value());
        assertEquals(10, game.getVideoCrf());
        assertEquals(6, game.getVideoPaintoverCrf());
        assertEquals("cbr", game.getRateControlMode());
        assertEquals("8000", game.getVideoBitrate());
        assertEquals(30, game.getKeyframeInterval());
        verify(scan).writeEnv(game);
    }

    @Test
    void 留空的项不下发也不改动实体() throws Exception {
        game.setVideoCrf(9);
        var res = controller.updateMeta("demo", qualityReq(null, null, null, null, null));

        assertEquals(200, res.getStatusCode().value());
        // 现有值保留，没有被 null 覆盖
        assertEquals(9, game.getVideoCrf());
        // 没有改动就不必重写 game.env（避免无谓落盘）
        verify(scan, never()).writeEnv(any());
    }

    @Test
    void CRF越界被拒绝且不写库() {
        var res = controller.updateMeta("demo", qualityReq(60, null, null, null, null));

        assertEquals(400, res.getStatusCode().value());
        verify(repo, never()).save(any());
        verifyNoInteractions(scan);
    }

    @Test
    void 静止画面CRF与关键帧间隔越界都被拒绝() {
        assertEquals(400, controller.updateMeta("demo",
                qualityReq(null, 4, null, null, null)).getStatusCode().value());
        assertEquals(400, controller.updateMeta("demo",
                qualityReq(null, null, null, null, -1)).getStatusCode().value());
        assertEquals(400, controller.updateMeta("demo",
                qualityReq(null, null, null, null, 301)).getStatusCode().value());
        verify(repo, never()).save(any());
    }

    @Test
    void 码率模式只接受crf或cbr() {
        assertEquals(400, controller.updateMeta("demo",
                qualityReq(null, null, "vbr", null, null)).getStatusCode().value());
        assertEquals(200, controller.updateMeta("demo",
                qualityReq(null, null, "crf", null, null)).getStatusCode().value());
    }

    @Test
    void 关键帧间隔允许0表示无限GOP() throws Exception {
        var res = controller.updateMeta("demo", qualityReq(null, null, null, null, 0));

        assertEquals(200, res.getStatusCode().value());
        assertEquals(0, game.getKeyframeInterval());
    }
}
