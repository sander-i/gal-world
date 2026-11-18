/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ETagsTest {

    @Test
    void sameContentSameTag() {
        Map<String, String> a = Map.of("switchMode", "tab", "rotationEnabled", "true");
        Map<String, String> b = Map.of("rotationEnabled", "true", "switchMode", "tab");
        assertEquals(ETags.of(a), ETags.of(b), "ETag 不能依赖 map 遍历顺序");
    }

    @Test
    void changedContentChangesTag() {
        assertNotEquals(ETags.of(Map.of("a", "1")), ETags.of(Map.of("a", "2")));
    }

    @Test
    void matchesSingleAndListed() {
        String tag = ETags.of(Map.of("a", "1"));
        assertTrue(ETags.matches(tag, tag));
        assertTrue(ETags.matches(tag, "\"other\", " + tag));
        assertFalse(ETags.matches(tag, "\"other\""));
        assertFalse(ETags.matches(tag, null));
        assertFalse(ETags.matches(tag, "*"), "不支持通配，避免误判为未变");
    }

    @Test
    void weakTagFormat() {
        // 弱标签是硬要求：强 ETag 会让 Tomcat 跳过 gzip
        String tag = ETags.of(List.of("x"));
        assertTrue(tag.startsWith("W/\"") && tag.endsWith("\""));
    }
}
