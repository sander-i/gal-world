/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameExeScannerTest {

    @TempDir Path dir;

    private Path touch(String rel, int size) throws Exception {
        Path p = dir.resolve(rel);
        Path parent = p.getParent();
        Files.createDirectories(parent == null ? dir : parent);
        Files.write(p, new byte[size]);
        return p;
    }

    @Test
    void findsExeRecursivelyAndSortsBySizeDescending() throws Exception {
        touch("game.EXE", 100);
        touch("sub/player.exe", 300);
        List<GameExeScanner.Exe> exes = new GameExeScanner().scan(dir);
        assertEquals(List.of("sub/player.exe", "game.EXE"),
                exes.stream().map(GameExeScanner.Exe::path).toList());
    }

    @Test
    void filtersInstallerAndUtilityExes() throws Exception {
        touch("setup.exe", 900);
        touch("unins000.exe", 800);
        touch("vcredist_x86.exe", 700);
        touch("dxwebsetup.exe", 600);
        touch("crashreport.exe", 500);
        touch("game.exe", 100);
        assertEquals(List.of("game.exe"),
                new GameExeScanner().scan(dir).stream().map(GameExeScanner.Exe::path).toList());
    }

    @Test
    void ignoresNonExeAndDeepDirectories() throws Exception {
        touch("game.exe", 10);
        touch("a/b/c/d/too-deep.exe", 999);
        touch("readme.txt", 20);
        assertEquals(List.of("game.exe"),
                new GameExeScanner().scan(dir).stream().map(GameExeScanner.Exe::path).toList());
    }

    @Test
    void capsResultsAt200() throws Exception {
        for (int i = 0; i < 205; i++) {
            touch("exe" + i + ".exe", i + 1);
        }
        assertEquals(GameExeScanner.MAX_RESULTS, new GameExeScanner().scan(dir).size());
    }

    @Test
    void skipsSymlinkedDirectories() throws Exception {
        Path outside = Files.createTempDirectory("outside");
        Files.write(outside.resolve("evil.exe"), new byte[50]);
        try {
            Files.createSymbolicLink(dir.resolve("link"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // 文件系统不支持符号链接（Windows 无权限）时跳过
        }
        assertTrue(new GameExeScanner().scan(dir).isEmpty());
    }

    @Test
    void returnsEmptyForMissingDirectory() {
        assertTrue(new GameExeScanner().scan(dir.resolve("nope")).isEmpty());
    }
}
