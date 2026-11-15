/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 递归枚举游戏目录里的 exe 候选。
 *
 * 只做「找出来并排序」，不做主程序判断：体积最大的不一定是游戏本体
 * （可能是汉化补丁或 CG 播放器），最终由用户在向导里选。
 */
@Component
public class GameExeScanner {

    public record Exe(String path, long sizeBytes) {
    }

    /** 单次扫描返回上限，防止大型目录把响应撑爆 */
    public static final int MAX_RESULTS = 200;
    /** 相对游戏目录的最大搜索深度 */
    public static final int MAX_DEPTH = 3;

    private static final List<String> DENY = List.of(
            "setup", "install", "unins", "uninst", "vcredist",
            "dxsetup", "dxwebsetup", "crash", "report");

    public List<Exe> scan(Path gameDir) {
        if (gameDir == null || !Files.isDirectory(gameDir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Exe> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(gameDir, MAX_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !Files.isSymbolicLink(p))
                    .filter(GameExeScanner::isExe)
                    .filter(p -> !denied(p.getFileName().toString()))
                    .forEach(p -> out.add(new Exe(
                            gameDir.relativize(p).toString().replace('\\', '/'),
                            size(p))));
        } catch (IOException e) {
            return List.of();
        }
        out.sort(Comparator.comparingLong(Exe::sizeBytes).reversed()
                .thenComparing(Exe::path));
        return out.size() > MAX_RESULTS ? List.copyOf(out.subList(0, MAX_RESULTS)) : out;
    }

    private static boolean isExe(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe");
    }

    private static boolean denied(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return DENY.stream().anyMatch(n::contains);
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }
}
