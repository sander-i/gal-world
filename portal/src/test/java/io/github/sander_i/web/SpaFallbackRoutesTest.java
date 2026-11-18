/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 SPA 回退路径与前端路由一致。
 *
 * 漏一条的后果是「直接访问 / 刷新那一页 → Spring 404 白页」，
 * 而从首页点进去是正常的，所以手工点一遍发现不了。这里直接从
 * router.js 抽路径来比对，新增路由忘了同步会在这里失败。
 */
class SpaFallbackRoutesTest {

    /** 前端路由路径，形如 { path: '/games/import', ... } */
    private static final Pattern ROUTE = Pattern.compile("\\{\\s*path:\\s*'([^']+)'");

    /** 回退控制器里 @GetMapping 的路径清单 */
    private static final Pattern MAPPING = Pattern.compile("\"([^\"]*)\"");

    @Test
    void everyFrontendRouteHasSpaFallback() throws IOException {
        Path router = Path.of("frontend", "src", "router.js");
        assertTrue(Files.isRegularFile(router), "找不到 router.js: " + router.toAbsolutePath());

        List<String> routes = new ArrayList<>();
        Matcher m = ROUTE.matcher(Files.readString(router, StandardCharsets.UTF_8));
        while (m.find()) {
            routes.add(m.group(1));
        }
        assertFalse(routes.isEmpty(), "router.js 里没解析到路由");

        List<String> mapped = mappedPaths();
        for (String route : routes) {
            assertTrue(covers(mapped, route),
                    "前端路由 " + route + " 没有对应的 SPA 回退，直接访问会 404。"
                            + "请在 SpaFallbackController 的 @GetMapping 里补上。");
        }
    }

    /** 静态资源不能被回退接管，否则样式与脚本会 404 */
    @Test
    void catchAllDoesNotSwallowAssets() throws IOException {
        List<String> mapped = mappedPaths();
        assertFalse(mapped.contains("/**"), "不要用 /** 兜底：会把 /assets/* 与 /favicon.svg 一起接管");
        for (String p : mapped) {
            assertFalse(p.startsWith("/assets"), "不该映射静态资源路径: " + p);
            assertFalse(p.startsWith("/api"), "不该映射 API 路径: " + p);
        }
    }

    private static List<String> mappedPaths() throws IOException {
        Path ctrl = Path.of("src", "main", "java", "io", "github", "sander_i", "web",
                "SpaFallbackController.java");
        assertTrue(Files.isRegularFile(ctrl), "找不到 SpaFallbackController.java");

        String src = Files.readString(ctrl, StandardCharsets.UTF_8);
        int at = src.indexOf("@GetMapping");
        assertTrue(at >= 0, "@GetMapping 不见了");
        int open = src.indexOf('{', at);
        int close = src.indexOf('}', open);
        assertTrue(open > 0 && close > open, "@GetMapping 的路径清单解析失败");

        List<String> out = new ArrayList<>();
        Matcher m = MAPPING.matcher(src.substring(open + 1, close));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** 路由是否被某条映射覆盖：精确匹配，或映射是 path/** 形式的前缀 */
    private static boolean covers(List<String> mapped, String route) {
        for (String p : mapped) {
            if (p.equals(route)) {
                return true;
            }
            if (p.endsWith("/**")) {
                String prefix = p.substring(0, p.length() - 3);
                if (route.equals(prefix) || route.startsWith(prefix + "/")) {
                    return true;
                }
            }
        }
        return false;
    }
}
