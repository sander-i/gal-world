/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 路由回退：前端是单页应用，直接访问 /game/xxx 或 /play/xxx 时
 * 服务器上没有对应文件，交回 index.html 由前端路由处理。
 * /api/** 不在此列。
 *
 * index.html 必须禁缓存：它引用带内容哈希的 JS/CSS，哈希变了文件名就变，
 * 但浏览器若缓存了 index.html 本身，就会一直指向上一次的旧 JS。
 * 用户在游戏页里按 Ctrl+F5 会被游戏吞掉（Selkies 绑了这些快捷键），
 * 没法手动硬刷新——只能靠这里保证入口页永远最新。
 */
@Controller
public class SpaFallbackController {

    @GetMapping({"/", "/game/**", "/play/**", "/games", "/settings", "/admin", "/companies"})
    public String index(HttpServletResponse res) {
        res.setHeader("Cache-Control", "no-store, must-revalidate");
        res.setHeader("Pragma", "no-cache");
        res.setDateHeader("Expires", 0);
        return "forward:/index.html";
    }
}
