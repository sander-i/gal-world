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

    /**
     * 游戏按自身固定分辨率渲染时，窗口大小与画布无关。
     *
     * <p>实测 tenshi-reboot：画布 1008×616 时窗口是 496×328（占 26%），
     * 画布改成 520×352 后窗口还是 496×328。旧逻辑走 fit-window 把画布缩到
     * 窗口+24，于是 1008×616 → 520×352，画布越小画质越差，越标定越糊
     * （2026-11-20 用户报告「分辨率被缩小、画质变差」）。
     */
    @Test
    void 窗口远小于画布时保持大画布不缩() {
        var p = CanvasPlanner.plan(1008, 616, 496, 328, 0, 0, 0, false);
        assertFalse(p.resize(), "窗口只占画布 26%，缩小画布只会降低画质");
        assertEquals("keep-large", p.reason());
    }

    @Test
    void 窗口占画布不到一半时都保持大画布() {
        // 1280x720 画布、600x400 窗口 = 26%
        var p = CanvasPlanner.plan(1280, 720, 600, 400, 0, 0, 0, false);
        assertFalse(p.resize());
        assertEquals("keep-large", p.reason());
    }

    @Test
    void 窗口占画布一半以上时仍按窗口微调() {
        // 800x500 / 1280x720 = 43%：低于一半，保持大画布
        assertFalse(CanvasPlanner.plan(1280, 720, 800, 500, 0, 0, 0, false).resize());
        // 900x600 / 1280x720 = 58%：高于一半，仍走 fit-window
        var p = CanvasPlanner.plan(1280, 720, 900, 600, 0, 0, 0, false);
        assertTrue(p.resize());
        assertEquals("fit-window", p.reason());
    }

    /**
     * 固定分辨率判定要排在 done/round 之前：否则第一次校准把 done 置位后，
     * 后续调用落到 done 分支，界面文案又变回「已匹配窗口」，与事实不符。
     */
    @Test
    void 已收敛后仍报告固定分辨率而不是done() {
        var p = CanvasPlanner.plan(1008, 616, 496, 279, 0, 0, 0, true);
        assertFalse(p.resize());
        assertEquals("keep-large", p.reason());
        // 轮次用尽同理
        var p2 = CanvasPlanner.plan(1008, 616, 496, 279, 4, 1008, 616, false);
        assertFalse(p2.resize());
        assertEquals("keep-large", p2.reason());
    }
}
