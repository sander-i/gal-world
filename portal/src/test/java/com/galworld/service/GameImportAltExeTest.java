/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import com.galworld.config.PathConfig;
import com.galworld.repo.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameImportAltExeTest {

    @TempDir Path root;
    GameImportValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("games/demo"));
        Files.write(root.resolve("games/demo/game.exe"), new byte[8]);
        Files.write(root.resolve("games/demo/cn.exe"), new byte[8]);
        GameRepository repo = mock(GameRepository.class);
        when(repo.existsById(anyString())).thenReturn(false);
        validator = new GameImportValidator(new PathConfig(root.toString()), repo);
    }

    @Test
    void acceptsOptionalAltExe() {
        var n = validator.validate("demo", "演示", "game.exe", "cn.exe", 1280, 720, "30");
        assertTrue(n.exe().endsWith("game.exe"), n.exe());
        assertTrue(n.exeAlt().endsWith("cn.exe"), n.exeAlt());
    }

    @Test
    void altIsEmptyWhenOmitted() {
        var n = validator.validate("demo", "演示", "game.exe", null, 1280, 720, "30");
        assertEquals("", n.exeAlt());
    }

    @Test
    void rejectsAltEqualToPrimary() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("demo", "演示", "game.exe", "game.exe", 1280, 720, "30"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("demo", "演示", "game.exe", "./game.exe", 1280, 720, "30"));
    }
}
