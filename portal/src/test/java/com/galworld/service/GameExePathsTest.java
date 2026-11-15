/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GameExePathsTest {

    @TempDir Path dir;

    private Path touch(String rel) throws Exception {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[4]);
        return p;
    }

    @Test
    void resolvesRelativePathToAbsolute() throws Exception {
        touch("sub/game.exe");
        String abs = GameExePaths.resolve(dir, "sub/game.exe");
        assertTrue(abs.endsWith("game.exe"));
        assertTrue(Path.of(abs).isAbsolute());
    }

    @Test
    void acceptsAbsolutePathInsideGameDir() throws Exception {
        Path p = touch("game.exe");
        assertEquals(p.toRealPath().toString(), GameExePaths.resolve(dir, p.toString()));
    }

    @Test
    void rejectsTraversalAbsoluteOutsideAndMissing() throws Exception {
        touch("game.exe");
        for (String bad : new String[]{"../evil.exe", "/etc/passwd", "nope.exe", ""}) {
            assertThrows(IllegalArgumentException.class,
                    () -> GameExePaths.resolve(dir, bad), bad);
        }
    }

    @Test
    void rejectsNonExeFile() throws Exception {
        touch("readme.txt");
        assertThrows(IllegalArgumentException.class,
                () -> GameExePaths.resolve(dir, "readme.txt"));
    }
}
