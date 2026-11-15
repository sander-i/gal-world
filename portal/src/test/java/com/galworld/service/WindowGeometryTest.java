/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WindowGeometryTest {

    private static final String TREE = """
            xwininfo: Window id: 0x21f (the root window) (has no name)

              Root window id: 0x21f (the root window) (has no name)
              Parent window id: 0x0 (none)
                 24 children:
                 0x1800000 (has no name): ()  1x1+0+0  +0+0
                 0xa00005 "天使☆纷扰 RE-BOOT!": ("game.exe" "game.exe")  968x593+156+63  +156+63
                    1 child:
                    0x801d61 (has no name): ()  968x593+0+0  +156+63
                 0xa0000b (has no name): ("game.exe" "game.exe")  332x128+474+309  +474+309
            """;

    @Test
    void 取面积最大的实体窗口并用绝对坐标() {
        WindowGeometry g = WindowGeometry.largest(TREE);
        assertEquals(968, g.width());
        assertEquals(593, g.height());
        assertEquals(156, g.x());
        assertEquals(63, g.y());
    }

    @Test
    void 只有隐藏小窗时返回null() {
        String tree = """
                 0x1800000 (has no name): ()  1x1+0+0  +0+0
                 0xa0000b (has no name): ("game.exe" "game.exe")  332x128+474+309  +474+309
                """;
        assertNull(WindowGeometry.largest(tree));
    }

    @Test
    void 负坐标不会报错() {
        String tree = """
                 0xa00005 "T": ("g.exe" "g.exe")  800x600+-20+-10  +-20+-10
                """;
        WindowGeometry g = WindowGeometry.largest(tree);
        assertEquals(-20, g.x());
        assertEquals(-10, g.y());
    }
}
