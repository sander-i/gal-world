/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Linux 更新器脚本在仓库里直接分发，测试读文件断言覆盖更新分支。 */
class UpdaterScriptOverrideTest {

    private static String script() throws Exception {
        Path p = Path.of("..", "deploy", "bin", "updater.sh");
        assertTrue(Files.isRegularFile(p), "找不到 " + p.toAbsolutePath());
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void readsOverrideFlagFromRequest() throws Exception {
        assertTrue(script().contains("override // false"),
                "必须从 update-request 读取 override，缺失时按 false 处理");
    }

    @Test
    void overrideSkipsVersionAndMinFromGuards() throws Exception {
        String s = script();
        assertTrue(s.contains("[[ \"$OVERRIDE\" == \"true\" ]]"),
                "覆盖路径必须跳过版本方向检查");
        assertTrue(s.contains("[[ \"$OVERRIDE\" != \"true\""),
                "minFromVersion 检查必须只在普通升级路径执行");
    }

    @Test
    void requiresUpdaterApiBeforeStopping() throws Exception {
        String s = script();
        assertTrue(s.contains("has_updater_api"));
        assertTrue(s.contains("BOOT-INF/classes/io/github/sander_i/update/UpdateService.class"));
        assertTrue(s.contains("目标版本不含更新器"));
    }

    @Test
    void updaterCheckRunsBeforeStopping() throws Exception {
        String s = script();
        int check = s.indexOf("has_updater_api \"$TMP/new.jar\"");
        int stop = s.indexOf("phase stopping");
        assertTrue(check > 0 && stop > 0 && check < stop,
                "更新器检查必须在停服之前，避免无谓停机");
    }
}
