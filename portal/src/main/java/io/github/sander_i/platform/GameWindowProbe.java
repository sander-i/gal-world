/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.platform;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 探测「游戏主窗口是否还在」。
 *
 * 用途：判断游戏是否已经彻底关闭。
 *
 * 为什么不用串流端口：Selkies 在游戏退出后还要收尾，端口会继续存在一段时间，
 * 用它判断「还在运行」会拖住关闭流程。而画面是从游戏窗口采集的 ——
 * 窗口没了就没有画面可传，等价于「游戏已经关了」，这是最贴近真实的状态信号。
 *
 * 实现：{@code xwininfo -root -children} 一次列出 Xvfb 上所有子窗口，
 * 几何信息就在输出里，不用逐个二次查询。面积超过阈值才算游戏窗口，
 * 用来排除 1x1 的光标窗口、隐藏的消息窗口。
 */
@Component
public class GameWindowProbe {

    /** 游戏窗口面积下限；1x1 光标窗口、隐藏窗口远小于此值 */
    static final int MIN_GAME_AREA = 100_000;

    /**
     * xwininfo 子窗口行里的几何：`1160x470+338+276`（行尾还会跟一段 `+338+276`）。
     * 要求带坐标，避免窗口标题里出现类似 `1920x1080` 的字样被误当成尺寸。
     */
    private static final Pattern GEOMETRY =
            Pattern.compile("(\\d+)x(\\d+)\\+(-?\\d+)\\+(-?\\d+)");

    private final CommandRunner runner;

    public GameWindowProbe(CommandRunner runner) {
        this.runner = runner;
    }

    /**
     * @return true 有游戏窗口；false 没有；null 当前平台探不了（Windows / 无显示号）
     */
    public Boolean present(int displayNum) {
        if (OsKind.current() != OsKind.LINUX || displayNum <= 0) {
            return null;
        }
        // 2 秒够用：xwininfo 只是读一次 X 属性；超时说明 X 已经没了，按「无窗口」之外的
        // 情况处理会更危险，这里直接返回 null 让调用方退回 systemd 状态判断
        CommandRunner.Result r = runner.exec(
                List.of("xwininfo", "-root", "-children", "-display", ":" + displayNum),
                Duration.ofSeconds(2));
        if (!r.ok()) {
            return null;
        }
        return maxArea(r.output()) >= MIN_GAME_AREA;
    }

    /** 解析 xwininfo 输出里最大的窗口面积 */
    static int maxArea(String output) {
        if (output == null || output.isEmpty()) {
            return 0;
        }
        int best = 0;
        for (String line : output.split("\\R")) {
            Matcher m = GEOMETRY.matcher(line);
            if (!m.find()) {
                continue;
            }
            int w = Integer.parseInt(m.group(1));
            int h = Integer.parseInt(m.group(2));
            int area = w * h;
            if (area > best) {
                best = area;
            }
        }
        return best;
    }
}
