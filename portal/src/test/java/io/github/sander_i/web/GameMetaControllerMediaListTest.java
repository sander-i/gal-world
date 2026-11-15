/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.service.CompanyService;
import io.github.sander_i.service.GameMediaService;
import io.github.sander_i.service.GameScanService;
import io.github.sander_i.service.LocalizationGroupService;
import io.github.sander_i.service.MediaService;
import io.github.sander_i.service.TagsAndMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 媒体列表接口（GET /api/games/{id}/media/{kind}）验收。
 *
 * 该接口是上传即时化之后的「取消后对账」依据：abort 可能发生在服务端已保存
 * 但响应未返回之间，前端必须能以服务端列表为准。
 */
class GameMetaControllerMediaListTest {

    @TempDir
    Path tmp;

    private GameRepository repo;
    private GameMetaController controller;

    @BeforeEach
    void setUp() {
        repo = mock(GameRepository.class);
        GameMediaService gameMedia = mock(GameMediaService.class);
        controller = new GameMetaController(
                repo,
                mock(TagsAndMeta.class),
                mock(CompanyService.class),
                mock(LocalizationGroupService.class),
                gameMedia,
                mock(MediaService.class),
                new PathConfig(tmp.toString()),
                mock(GameScanService.class),
                new io.github.sander_i.service.GameExeScanner(),
                mock(io.github.sander_i.service.FaviconService.class),
                mock(io.github.sander_i.service.CanvasCalibrationService.class));

        when(repo.existsById("g1")).thenReturn(true);
        when(gameMedia.ofGame(eq("g1"), eq("cover"))).thenReturn(List.of(
                new GameMediaService.MediaView(1, "cover", "/media/g1/cover-0.png", 0, "cover-0.png"),
                new GameMediaService.MediaView(2, "cover", "/media/g1/cover-1.png", 1, "cover-1.png")));
        when(gameMedia.ofGame(eq("g1"), eq("cg"))).thenReturn(List.of());
    }

    @Test
    void returnsMediaListOfKind() {
        var res = controller.listMedia("g1", "cover");
        assertEquals(200, res.getStatusCode().value());
        assertEquals(2, ((List<?>) res.getBody()).size());
    }

    @Test
    void returnsEmptyListForOtherKind() {
        var res = controller.listMedia("g1", "cg");
        assertEquals(200, res.getStatusCode().value());
        assertTrue(((List<?>) res.getBody()).isEmpty());
    }

    @Test
    void missingGameReturns404() {
        assertEquals(404, controller.listMedia("missing", "cover").getStatusCode().value());
    }

    @Test
    void invalidKindReturns400() {
        assertEquals(400, controller.listMedia("g1", "icon").getStatusCode().value());
    }
}
