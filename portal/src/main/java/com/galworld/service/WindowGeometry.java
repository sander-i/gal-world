/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 游戏主窗口在串流画布上的矩形（绝对坐标）。
 *
 * Xvfb 画布 = game.env 的 WIDTH×HEIGHT，游戏窗口按自身固定尺寸由
 * run-game.sh 居中；窗口外的部分在串流里是黑边。游玩页据此裁掉黑边，
 * 否则画布设得越大，游戏在画面里的占比越小。
 */
public record WindowGeometry(int x, int y, int width, int height) {

    /** 一行里会出现两次几何：先相对父窗口，再绝对坐标。取最后一个。 */
    private static final Pattern GEOM =
            Pattern.compile("(\\d+)x(\\d+)\\+(-?\\d+)\\+(-?\\d+)");

    /** 取面积最大的实体窗口；1x1 是 Wine 的隐藏辅助窗口，忽略。 */
    public static WindowGeometry largest(String tree) {
        int bestArea = 0;
        WindowGeometry best = null;
        for (String line : tree.split("\n")) {
            // 只认带 class 名的实体窗口；对话框也会命中，但面积更小，被下面筛掉
            if (!line.contains("(\"") || line.contains(" 1x1+")) {
                continue;
            }
            Matcher m = GEOM.matcher(line);
            int[] last = null;
            while (m.find()) {
                last = new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))};
            }
            if (last == null) {
                continue;
            }
            int area = last[0] * last[1];
            if (area <= 100_000) {
                continue;
            }
            if (area > bestArea) {
                bestArea = area;
                best = new WindowGeometry(last[2], last[3], last[0], last[1]);
            }
        }
        return best;
    }
}
