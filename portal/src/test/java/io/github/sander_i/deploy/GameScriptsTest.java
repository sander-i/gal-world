/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code run-game.sh} 里可独立验证的判定逻辑。
 *
 * <p>用 {@code --source-only} 只加载函数、不启动会话，避免真的拉 wine / Xvfb。
 * 目前只覆盖 {@code resize_target}：窗口放大是 2026-11-20 加的，
 * 阈值（画布一半）和边距（24px）都是经验值，改错了没有别的地方能发现。
 */
class GameScriptsTest {

    private static final Path SCRIPT = Path.of("src/main/resources/deploy/linux/bin/run-game.sh");

    private static boolean bashUsable() {
        try {
            Process p = new ProcessBuilder("bash", "-c", "true").redirectErrorStream(true).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** 在 bash 里 source 脚本并调用 resize_target，返回 stdout（去掉首尾空白）。 */
    private static String resizeTarget(int ww, int wh, int cw, int ch) throws Exception {
        assumeTrue(Files.isRegularFile(SCRIPT), "找不到 " + SCRIPT.toAbsolutePath());
        assumeTrue(bashUsable(), "需要 bash，Windows 请把 Git Bash 加入 PATH");
        // Windows 的绝对路径带反斜杠，塞进 bash -c 会被当转义吃掉；
        // 统一转成正斜杠（Git Bash 也认 C:/... 形式）。
        String path = SCRIPT.toAbsolutePath().toString().replace('\\', '/');
        String cmd = "source '" + path + "' --source-only; "
                + "resize_target " + ww + " " + wh + " " + cw + " " + ch;
        Process p = new ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "脚本超时");
        return out;
    }

    @Test
    void 窗口不足画布一半时放大到画布减边距() throws Exception {
        // 实测 tenshi-reboot：画布 1008x616、窗口 496x328（占 26%）
        assertEquals("984 592", resizeTarget(496, 328, 1008, 616));
    }

    @Test
    void 窗口略小于画布时不放大() throws Exception {
        // 窗口占 85% 以上：调大反而可能触发布局变化，保持原样
        assertTrue(resizeTarget(984, 554, 1008, 616).isEmpty(),
                "窗口已占画布 87.8%，不该再放大");
    }

    @Test
    void 窗口正好占画布一半面积时不放大() throws Exception {
        // 判据是「面积不足画布一半」。画布 1008x616 = 620928，一半 = 310464。
        // 713x435 = 310155 略低于一半 -> 命中；这里取 714x435 = 310590 略高于一半 -> 不命中。
        assertTrue(resizeTarget(714, 435, 1008, 616).isEmpty(),
                "窗口面积略高于画布一半，不应触发放大");
    }

    @Test
    void 窗口面积略低于一半时放大() throws Exception {
        // 713x435 = 310155，*2 = 620310 < 620928，命中
        assertEquals("984 592", resizeTarget(713, 435, 1008, 616));
    }

    @Test
    void 窗口刚好过一半时不放大() throws Exception {
        // 510x310 = 158100，画布 1008x616 = 620928；*2 = 316200 < 620928 仍命中
        // 这里取 60% 的窗口确认不触发
        assertTrue(resizeTarget(780, 480, 1008, 616).isEmpty(),
                "窗口占画布 60%，高于一半阈值，不应放大");
    }

    @Test
    void 参数非数字时不放大() throws Exception {
        assertTrue(resizeTarget(-1, 328, 1008, 616).isEmpty());
        assertTrue(resizeTarget(496, 328, 0, 616).isEmpty());
    }

    @Test
    void 画布小于窗口时不放大() throws Exception {
        // 目标比当前窗口小：没有放大的意义
        assertTrue(resizeTarget(900, 600, 800, 500).isEmpty());
    }
}
