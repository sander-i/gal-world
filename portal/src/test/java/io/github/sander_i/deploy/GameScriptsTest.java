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
 * 阈值（画布 85%，与 {@code CanvasPlanner} 的收敛阈值一致）和边距（24px）
 * 都是经验值，改错了没有别的地方能发现。
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
    void 窗口占画布百分之二十六时放大到画布减边距() throws Exception {
        // 实测 tenshi-reboot：画布 1008x616、窗口 496x328（占 26%）
        assertEquals("984 592", resizeTarget(496, 328, 1008, 616));
    }

    @Test
    void 窗口占画布百分之八十七时不放大() throws Exception {
        // 窗口占 85% 以上：调大反而可能触发布局变化，保持原样
        assertTrue(resizeTarget(984, 554, 1008, 616).isEmpty(),
                "窗口已占画布 87.8%，不该再放大");
    }

    @Test
    void 窗口占画布百分之六十时也放大() throws Exception {
        // 780x480 = 374400，占画布 620928 的 60.3%。
        // 旧阈值（不足一半）不会动，新阈值（不足 85%）会放大。
        assertEquals("984 592", resizeTarget(780, 480, 1008, 616));
    }

    @Test
    void 窗口占画布约一半时放大() throws Exception {
        // 713x435 = 310155，占画布约 50%，远低于 85% 阈值，命中
        assertEquals("984 592", resizeTarget(713, 435, 1008, 616));
    }

    @Test
    void 窗口刚好百分之八十五边界() throws Exception {
        // 画布 1008x616 = 620928，85% = 527788.8，判据是 ww*wh*100 < 620928*85 = 52778880。
        // 984x536 = 527424（84.94%）略低于 85% -> 放大；
        // 984x537 = 528408（85.10%）略高于 85% -> 不放大。
        assertEquals("984 592", resizeTarget(984, 536, 1008, 616));
        assertTrue(resizeTarget(984, 537, 1008, 616).isEmpty(),
                "窗口面积已到画布 85% 以上，不应触发放大");
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
