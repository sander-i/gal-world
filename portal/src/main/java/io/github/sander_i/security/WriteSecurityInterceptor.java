/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 写接口拦截器：把 {@link WriteSecurity} 的校验挂到 Spring MVC 上。
 *
 * 只拦非 GET 的 /api/**；/api/deploy/** 与 /api/admin/** 自带部署令牌，跳过。
 * 用 register 而非 filter，是为了让 Spring 的异常处理与 JSON 错误体保持一致。
 */
@Component
public class WriteSecurityInterceptor implements HandlerInterceptor {

    private final WriteSecurity security;

    public WriteSecurityInterceptor(WriteSecurity security) {
        this.security = security;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler)
            throws Exception {
        if (!security.enabled()) {
            return true;
        }
        String method = req.getMethod();
        // GET / HEAD / OPTIONS 都是只读，放行
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }
        String path = req.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true;
        }
        // 这两组接口有自己的令牌体系，这里不重复校验
        if (path.startsWith("/api/deploy/") || path.startsWith("/api/admin/")) {
            return true;
        }
        try {
            security.check(req);
            return true;
        } catch (SecurityException e) {
            // 与其它控制器的错误体保持一致，前端能直接读 message
            res.setStatus(403);
            res.setContentType("application/json;charset=UTF-8");
            String body = "{\"message\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
            res.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            return false;
        }
    }
}
