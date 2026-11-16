/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * xwininfo -root -children 的解析。
 *
 * 几何信息在每行末尾，形如 {@code 1160x470+338+276  +338+276}；
 * 窗口标题里也可能出现类似尺寸的文字，所以只认行尾带坐标的那一段。
 */
class GameWindowProbeTest {

    @Test
    void picksLargestWindowAmongChildren() {
        String out = """
                xwininfo: Window id: 0x21f (the root window) (has no name)

                  Root window id: 0x21f (the root window) (has no name)
                  Parent window id: 0x0 (none)
                     24 children:
                     0x8003bf (has no name): ()  1x1+0+0  +0+0
                     0xa00005 "game": ("game.exe" "game.exe")  1x1+0+0  +0+0
                     0xe0000c "launcher": ("p.exe" "p.exe")  332x89+338+276  +338+276
                     0xa00009 "main": ("game.exe" "game.exe")  1160x470+40+40  +40+40
                """;
        assertEquals(1160 * 470, GameWindowProbe.maxArea(out));
    }

    @Test
    void ignoresSizesMentionedInWindowTitles() {
        // 标题里出现 1920x1080 字样时不能当成窗口尺寸
        String out = "     0xe0000c \"补丁 1920x1080 说明\": (\"p.exe\" \"p.exe\")  332x89+10+10  +10+10\n";
        assertEquals(332 * 89, GameWindowProbe.maxArea(out));
    }

    @Test
    void returnsZeroWhenNoWindows() {
        assertEquals(0, GameWindowProbe.maxArea("xwininfo: Window id: 0x21f (the root window)\n"));
        assertEquals(0, GameWindowProbe.maxArea(""));
        assertEquals(0, GameWindowProbe.maxArea(null));
    }

    @Test
    void handlesNegativeWindowOrigin() {
        // Wine 把窗口移到负坐标时会这样输出
        String out = "     0xa00005 \"game\": ()  1280x720+-20+-20  +-20+-20\n";
        assertEquals(1280 * 720, GameWindowProbe.maxArea(out));
    }
}
