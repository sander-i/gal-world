/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.repo.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameImportValidatorTest {

    @TempDir Path root;
    GameRepository repo;
    GameImportValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("games/sample-game/sub"));
        Files.write(root.resolve("games/sample-game/game.exe"), new byte[8]);
        Files.write(root.resolve("games/sample-game/sub/other.exe"), new byte[8]);
        Files.write(root.resolve("games/sample-game/readme.txt"), new byte[4]);
        repo = mock(GameRepository.class);
        when(repo.existsById(anyString())).thenReturn(false);
        validator = new GameImportValidator(new PathConfig(root.toString()), repo);
    }

    @Test
    void acceptsRelativeExeAndNormalizesFramerate() {
        var n = validator.validate("sample-game", " 标题 ", "sub/other.exe", 1280, 720, null);
        assertEquals("标题", n.name());
        assertTrue(n.exe().endsWith("sub" + java.io.File.separator + "other.exe")
                || n.exe().endsWith("sub/other.exe"), n.exe());
        assertEquals("60,30-60", n.framerate());
    }

    @Test
    void acceptsChinesePatchLangAndDefaultsEmpty() {
        var zh = validator.validate("sample-game", "n", "game.exe", null,
                1280, 720, "30", " zh_CN.UTF-8 ");
        assertEquals("zh_CN.UTF-8", zh.gameLang());
        var none = validator.validate("sample-game", "n", "game.exe", null,
                1280, 720, "30", null);
        assertEquals("", none.gameLang());
    }

    @Test
    void acceptsInitialValueWithRangeFramerate() {
        // Selkies 的三段式：初始值 + 范围。Ctrl 快速跳过对话要靠它放开 30fps 天花板。
        for (String ok : new String[]{"60", "30-60", "60,30-60", "30,30-60", "60,8-240"}) {
            var n = validator.validate("sample-game", "n", "game.exe", 1280, 720, ok);
            assertEquals(ok, n.framerate(), ok);
        }
    }

    @Test
    void rejectsMalformedFramerate() {
        for (String bad : new String[]{"abc", "60,", ",60", "60,30-", "60-30,abc", "60,30-60-90"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validate("sample-game", "n", "game.exe", 1280, 720, bad), bad);
        }
    }

    @Test
    void rejectsUnknownLang() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("sample-game", "n", "game.exe", null,
                        1280, 720, "30", "zh_CN.UTF-8; rm -rf /"));
    }

    @Test
    void rejectsBadIds() {
        for (String bad : new String[]{"", "Hamidashi", "has space", "中文", "-lead", "a"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validate(bad, "n", "game.exe", 1280, 720, "30"), bad);
        }
    }

    @Test
    void rejectsRegisteredId() {
        when(repo.existsById("sample-game")).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("sample-game", "n", "game.exe", 1280, 720, "30"));
    }

    @Test
    void rejectsMissingDirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("nope", "n", "game.exe", 1280, 720, "30"));
    }

    @Test
    void rejectsPathTraversalAndAbsolutePaths() {
        for (String bad : new String[]{"../outside.exe", "/etc/passwd", "sub/../../game.exe"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validate("sample-game", "n", bad, 1280, 720, "30"), bad);
        }
    }

    @Test
    void rejectsNonExeTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("sample-game", "n", "readme.txt", 1280, 720, "30"));
    }

    @Test
    void rejectsOutOfRangeResolutionAndBadFramerate() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("sample-game", "n", "game.exe", 100, 720, "30"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("sample-game", "n", "game.exe", 1280, 9999, "30"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("sample-game", "n", "game.exe", 1280, 720, "fast"));
    }
}
