/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.domain.GameMedia;
import io.github.sander_i.repo.GameMediaRepository;
import io.github.sander_i.repo.GameRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 列表页批量媒体：一次查询 + 目录扫描缓存 */
class GameMediaBatchTest {

    @TempDir
    Path root;

    private long nextId = 1;

    private GameMedia mediaOf(String gameId, String kind, int seq, String file) {
        GameMedia m = new GameMedia();
        m.setId(nextId++);
        m.setGameId(gameId);
        m.setKind(kind);
        m.setSortOrder(seq);
        m.setPath("/media/" + gameId + "/" + file);
        return m;
    }

    @Test
    void batchesRecordsAndCachesVariantScan() throws Exception {
        GameMediaRepository repo = mock(GameMediaRepository.class);
        MediaService media = mock(MediaService.class);
        SettingsService settings = mock(SettingsService.class);
        when(settings.imageVariantEnabled()).thenReturn(true);

        Path dir = root.resolve("g1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("cover-1.png"), "x");
        Files.writeString(dir.resolve("cover-1.png.s640"), "x");
        Files.writeString(dir.resolve("cg-1.png"), "x");
        when(media.mediaDir("g1")).thenReturn(dir);
        when(media.mediaDir("g2")).thenReturn(root.resolve("g2"));

        when(repo.findByGameIdInOrderByKindAscSortOrderAsc(anyCollection()))
                .thenReturn(List.of(
                        mediaOf("g1", "cover", 0, "cover-1.png"),
                        mediaOf("g1", "cg", 0, "cg-1.png")));

        GameMediaService service = new GameMediaService(repo, mock(GameRepository.class),
                media, settings);

        var out = service.itemsByGames(List.of("g1", "g2"), "cover");
        assertEquals(1, out.size(), "没有媒体记录的游戏不出现在结果里");
        var item = out.get("g1").get(0);
        assertEquals(1, item.variants().size());
        assertEquals(640, item.variants().get(0).w());
        assertTrue(item.variants().get(0).url().endsWith(".s640"));

        // 目录变了但缓存未过期：仍返回上一次的扫描结果
        Files.delete(dir.resolve("cover-1.png.s640"));
        var cached = service.itemsByGames(List.of("g1"), "cover");
        assertEquals(1, cached.get("g1").get(0).variants().size());

        // 显式失效后重新扫描
        service.invalidateVariantCache("g1");
        var fresh = service.itemsByGames(List.of("g1"), "cover");
        assertTrue(fresh.get("g1").get(0).variants().isEmpty());
    }

    @Test
    void variantsSkippedWhenSettingDisabled() {
        GameMediaRepository repo = mock(GameMediaRepository.class);
        MediaService media = mock(MediaService.class);
        SettingsService settings = mock(SettingsService.class);
        when(settings.imageVariantEnabled()).thenReturn(false);
        when(media.mediaDir(anyString())).thenReturn(root);
        when(repo.findByGameIdInOrderByKindAscSortOrderAsc(anyCollection()))
                .thenReturn(List.of(mediaOf("g1", "cover", 0, "cover-1.png")));

        var out = new GameMediaService(repo, mock(GameRepository.class), media, settings)
                .itemsByGames(List.of("g1"), "cover");
        assertTrue(out.get("g1").get(0).variants().isEmpty());
        verify(media, never()).mediaDir(anyString());
    }
}
