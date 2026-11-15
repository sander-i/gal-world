/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

/**
 * 画布校准决策。纯函数，不读配置、不碰进程，方便单测。
 *
 * 经验规则（实测归纳，与 README「画面校准」一致）：
 *  - 游戏窗口由 Wine/游戏自身协商，画布越大窗口不一定越大；
 *  - 画布 ≈ 窗口 + 20 时窗口最大、黑边最少；
 *  - 窗口高顶到 SCREEN_H-20 说明被 Wine 钳制，必须先把画布加高。
 */
public final class CanvasPlanner {

    public record Plan(int width, int height, boolean resize, String reason) {
    }

    /** 每次播放尝试最多校准 4 轮，防止窗口尺寸震荡导致无限重启 */
    public static final int MAX_ROUNDS = 4;

    private static final int PAD = 24;
    private static final int STEP = 8;
    private static final int TOLERANCE = 8;
    private static final int CLAMP_MARGIN = 20;
    private static final int MAX_W = 3840;
    private static final int MAX_H = 2160;

    private CanvasPlanner() {
    }

    public static Plan plan(int canvasW, int canvasH, int winW, int winH,
                            int round, int bestW, int bestH, boolean done) {
        if (winW <= 0 || winH <= 0) {
            return keep(canvasW, canvasH, "no-window");
        }
        // 窗口正好铺满画布：已经是最优，不再加高（否则会被误判成钳制而反复放大）
        if (winW == canvasW && winH == canvasH) {
            return keep(canvasW, canvasH, "converged");
        }
        if (done) {
            return keep(canvasW, canvasH, "done");
        }
        if (round >= MAX_ROUNDS) {
            if (bestW > 0 && bestH > 0 && (bestW != canvasW || bestH != canvasH)) {
                return new Plan(bestW, bestH, true, "restore-best");
            }
            return keep(canvasW, canvasH, "rounds-exhausted");
        }

        // Wine 钳制表现为窗口正好等于画布 - 20；用等号避免把普通“窗口比画布小”
        // 误判成钳制（PAD=24 保证正常拟合后不会再落到这个差值上）。
        boolean clampW = winW == canvasW - CLAMP_MARGIN;
        boolean clampH = winH == canvasH - CLAMP_MARGIN;
        // 窗口已占画布 85% 以上时，为几十像素再重启一次游戏不值得，也避免
        // 游戏启动过程中窗口尺寸变化触发连续重启（实测 localove2 会重启两次）。
        long winArea = (long) winW * winH;
        long canvasArea = (long) canvasW * canvasH;
        if (!clampW && !clampH && winArea * 100 >= canvasArea * 85) {
            return keep(canvasW, canvasH, "converged");
        }
        int targetW = clampW ? grow(canvasW) : round8(winW + PAD);
        int targetH = clampH ? grow(canvasH) : round8(winH + PAD);
        targetW = Math.min(MAX_W, targetW);
        targetH = Math.min(MAX_H, targetH);

        if (Math.abs(targetW - canvasW) <= TOLERANCE
                && Math.abs(targetH - canvasH) <= TOLERANCE) {
            return keep(canvasW, canvasH, "converged");
        }
        return new Plan(targetW, targetH, true,
                clampW || clampH ? "clamp" : "fit-window");
    }

    private static Plan keep(int w, int h, String reason) {
        return new Plan(w, h, false, reason);
    }

    /** 被钳制时按约 1/8 加一档，至少 +64，避免每次只加 20 需要很多轮 */
    private static int grow(int value) {
        return round8(value + Math.max(64, value / STEP));
    }

    private static int round8(int value) {
        return ((value + STEP - 1) / STEP) * STEP;
    }
}
