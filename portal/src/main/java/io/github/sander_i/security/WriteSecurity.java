/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.security;

import io.github.sander_i.i18n.I18n;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * 写接口保护（可选启用）。
 *
 * 门户默认没有登录，局域网内任何人都能启动游戏、改设置、删媒体。
 * 这一层让部署者可以选择加一道门槛：配置 {@code galworld.write.token}
 * 后，所有非 GET 的 /api/** 请求都必须带 {@link #HEADER} 头。
 *
 * **未配置时一律放行**——这是向后兼容的关键：升级上来的实例行为不变，
 * 只有显式配置了令牌的部署才进入受保护模式。
 *
 * 例外：
 *  - /api/deploy/**：自带部署令牌，重复校验会让页面拿不到日志流
 *  - /api/admin/**：同样自带部署令牌
 *  - SSE 日志流用 GET，天然不在此列
 *
 * 启动时会打印已启用的令牌，供运维在服务器上用 journalctl 查询；
 * 日志与 systemd 环境变量同属本机凭据面，不通过接口下发。
 */
@Component
public class WriteSecurity {

    private static final Logger log = LoggerFactory.getLogger(WriteSecurity.class);
    public static final String HEADER = "X-GalWorld-Write-Token";

    /** 空字符串 = 未启用保护 */
    private final String token;

    public WriteSecurity(@Value("${galworld.write.token:}") String token) {
        this.token = token == null ? "" : token.trim();
        if (enabled()) {
            log.info("写接口保护已启用：非 GET 的 /api/** 需要 {} 头", HEADER);
            // 令牌必须能被运维在服务器上查到；journalctl 是本项目的唯一启动日志出口。
            // 能读该用户 journal 的进程同样能读配置环境变量，此处不额外扩大暴露面。
            log.info("写接口令牌：{}", this.token);
        } else {
            log.info("写接口保护未启用（galworld.write.token 为空）");
        }
    }

    public boolean enabled() {
        return !token.isEmpty();
    }

    /** 校验失败抛 SecurityException，由拦截器转 403 */
    public void check(HttpServletRequest req) {
        if (!enabled()) {
            return;
        }
        String given = req.getHeader(HEADER);
        if (given == null || !constantTimeEquals(given.trim(), token)) {
            throw new SecurityException(I18n.t("缺少或错误的写接口令牌"));
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        // 长度不等也要走完整比较，避免按长度提前返回
        return MessageDigest.isEqual(x, y);
    }
}
