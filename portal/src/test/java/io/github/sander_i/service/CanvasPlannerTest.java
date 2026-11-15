/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CanvasPlannerTest {

    @Test
    void 没有窗口信息时保持不变() {
        var p = CanvasPlanner.plan(1280, 720, 0, 0, 0, 0, 0, false);
        assertFalse(p.resize());
        assertEquals("no-window", p.reason());
    }

    @Test
    void 画布大于窗口时缩到窗口加二十四() {
        // 968+24=992；593+24=617 -> 624
        var p = CanvasPlanner.plan(1280, 720, 968, 593, 0, 0, 0, false);
        assertTrue(p.resize());
        assertEquals(992, p.width());
        assertEquals(624, p.height());
        assertEquals("fit-window", p.reason());
    }

    @Test
    void 窗口顶到画布高时先加高避免裁切() {
        // 宽 1028 与 1280 差 252，未钳 -> 1028+24=1052 -> 1056；
        // 高 700 正好等于 720-20，判定被钳 -> 720+90=810 -> 816
        var p = CanvasPlanner.plan(1280, 720, 1028, 700, 0, 0, 0, false);
        assertTrue(p.resize());
        assertEquals(1056, p.width());
        assertEquals(816, p.height());
        assertEquals("clamp", p.reason());
    }

    @Test
    void 窗口正好铺满画布时不放大() {
        var p = CanvasPlanner.plan(1280, 720, 1280, 720, 0, 0, 0, false);
        assertFalse(p.resize());
        assertEquals("converged", p.reason());
    }

    @Test
    void 已收敛时不再重启() {
        var p = CanvasPlanner.plan(992, 624, 968, 593, 0, 0, 0, false);
        assertFalse(p.resize());
        assertEquals("converged", p.reason());
    }

    @Test
    void 窗口已占画布八成半以上时不再为几个像素重启() {
        // 990x575 / 1016x656 ≈ 85.4%：游戏启动过程中窗口变小，不应再触发第二次重启
        var p = CanvasPlanner.plan(1016, 656, 990, 575, 0, 0, 0, false);
        assertFalse(p.resize());
        assertEquals("converged", p.reason());
    }

    @Test
    void 轮次用尽后回退到最佳画布() {
        var p = CanvasPlanner.plan(1280, 720, 900, 600, 4, 992, 616, false);
        assertTrue(p.resize());
        assertEquals(992, p.width());
        assertEquals(616, p.height());
        assertEquals("restore-best", p.reason());
    }

    @Test
    void 已完成时保持不动() {
        var p = CanvasPlanner.plan(1280, 720, 900, 600, 4, 992, 616, true);
        assertFalse(p.resize());
        assertEquals("done", p.reason());
    }
}
