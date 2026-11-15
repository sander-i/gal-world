/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 启动程序路径校验：接入与编辑两条链路共用，规则只有一份。
 *
 * 用 toRealPath() 解析后再比对前缀，才能同时拦住 `../` 与符号链接逃逸。
 */
public final class GameExePaths {

    private GameExePaths() {
    }

    /** @return 绝对真实路径；非法输入抛 IllegalArgumentException */
    public static String resolve(Path gameDir, String given) {
        String value = given == null ? "" : given.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("必须选择可执行文件");
        }
        Path target = Path.of(value);
        if (!target.isAbsolute()) {
            target = gameDir.resolve(value);
        }
        Path real;
        Path realDir;
        try {
            real = target.toRealPath();
            realDir = gameDir.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("可执行文件不存在: " + value);
        }
        if (!real.startsWith(realDir) || !Files.isRegularFile(real)) {
            throw new IllegalArgumentException("可执行文件必须位于游戏目录内");
        }
        if (!real.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            throw new IllegalArgumentException("可执行文件必须以 .exe 结尾");
        }
        return real.toString();
    }
}
