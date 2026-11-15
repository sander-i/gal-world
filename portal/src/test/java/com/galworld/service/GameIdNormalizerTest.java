/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GameIdNormalizerTest {

    @Test
    void turnsSpacesAndUppercaseIntoSeparators() {
        assertEquals("9-nine-episode-2", GameIdNormalizer.normalize("9 Nine Episode 2"));
        assertEquals("my_game", GameIdNormalizer.normalize("My_Game"));  // 下划线是合法字符，保留
        assertEquals("tenshi-reboot", GameIdNormalizer.normalize("Tenshi Reboot"));
    }

    @Test
    void nonAsciiOnlyFallsBackToHash() {
        String id = GameIdNormalizer.normalize("游戏");
        assertTrue(id.matches("^[a-z0-9][a-z0-9._-]{1,31}$"), id);
        assertEquals(id, GameIdNormalizer.normalize("游戏"));  // 稳定
        assertNotEquals(id, GameIdNormalizer.normalize("另一个游戏"));
    }

    @Test
    void stripsLeadingSeparatorsAndCollapsesRuns() {
        assertEquals("abc", GameIdNormalizer.normalize("  --..ABC..--  "));
        assertEquals("a-b", GameIdNormalizer.normalize("a///b"));
    }

    @Test
    void neverProducesPathTraversalOrOverlongId() {
        assertFalse(GameIdNormalizer.normalize("..").contains(".."));
        String longId = GameIdNormalizer.normalize("a".repeat(100));
        assertEquals(32, longId.length());
        assertTrue(longId.matches("^[a-z0-9][a-z0-9._-]{1,31}$"), longId);
    }

    @Test
    void singleCharFallsBackToHash() {
        String id = GameIdNormalizer.normalize("9");
        assertTrue(id.length() >= 2, id);
        assertTrue(id.matches("^[a-z0-9][a-z0-9._-]{1,31}$"), id);
    }

    @Test
    void normalizeUniqueAppendsSuffixWhenTaken() {
        Set<String> taken = Set.of("game", "game-2");
        String id = GameIdNormalizer.normalizeUnique("Game", taken::contains);
        assertEquals("game-3", id);
        assertEquals(32, GameIdNormalizer.normalizeUnique("a".repeat(40),
                Set.of("a".repeat(32))::contains).length());
    }
}
