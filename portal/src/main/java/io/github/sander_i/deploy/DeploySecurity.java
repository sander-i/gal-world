/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 部署接口的访问控制（docs/ONE-CLICK-DEPLOY.md §7.5）。
 *
 * 两道门：请求头令牌 + 来源网段。两道都过才允许进入执行接口。
 */
@Component
public class DeploySecurity {

    private static final Logger log = LoggerFactory.getLogger(DeploySecurity.class);
    public static final String HEADER = "X-GalWorld-Deploy-Token";

    private final String token;
    private final boolean configured;
    private final List<Cidr> allowed = new ArrayList<>();

    public DeploySecurity(@Value("${galworld.deploy.token:}") String token,
                          @Value("${galworld.deploy.allowed-cidrs:127.0.0.1/32,192.168.0.0/16,10.0.0.0/8}")
                          String cidrs) {
        this.configured = token != null && !token.isBlank();
        this.token = configured ? token : randomToken();
        if (!configured) {
            log.warn("galworld.deploy.token 未配置，本次启动使用随机令牌: {}", this.token);
            log.warn("仅本机可用；如需页面调用，请把该令牌写入 application.yml 的 galworld.deploy.token");
        }
        for (String part : cidrs.split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) {
                try {
                    allowed.add(Cidr.parse(v));
                } catch (RuntimeException e) {
                    log.warn("忽略非法 CIDR: {}", v);
                }
            }
        }
    }

    /** 部署者是否显式配置了令牌；未配置时用的是每次启动随机生成的一次性令牌 */
    public boolean configured() {
        return configured;
    }

    /** 校验失败抛 SecurityException，由控制器转 403 */
    public void check(HttpServletRequest req) {
        String given = req.getHeader(HEADER);
        if (given == null || !constantTimeEquals(given, token)) {
            throw new SecurityException("缺少或错误的部署令牌");
        }
        String remote = req.getRemoteAddr();
        // IPv6 回环（::1 / 0:0:0:0:0:0:0:1）等价于 127.0.0.1，CIDR 只解析 IPv4，这里单独放行
        if ("::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote)) {
            return;
        }
        if (allowed.isEmpty()) {
            return;
        }
        for (Cidr c : allowed) {
            if (c.contains(remote)) {
                return;
            }
        }
        throw new SecurityException("来源地址不在允许网段: " + remote);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            // 仍走一遍比较，避免按长度提前返回
            int r = a.length() ^ b.length();
            for (int i = 0; i < Math.min(a.length(), b.length()); i++) {
                r |= a.charAt(i) ^ b.charAt(i);
            }
            return r == 0 && a.length() == b.length();
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }

    private static String randomToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return java.util.HexFormat.of().formatHex(b);
    }

    /** 极简 IPv4 CIDR */
    record Cidr(int base, int mask) {

        static Cidr parse(String text) {
            String[] p = text.split("/");
            int ip = ipv4(p[0]);
            int bits = p.length > 1 ? Integer.parseInt(p[1]) : 32;
            int mask = bits == 0 ? 0 : (0xFFFFFFFF << (32 - bits));
            return new Cidr(ip & mask, mask);
        }

        boolean contains(String addr) {
            try {
                return (ipv4(addr) & mask) == base;
            } catch (RuntimeException e) {
                return false;
            }
        }

        private static int ipv4(String s) {
            String[] parts = s.split("\\.");
            if (parts.length != 4) {
                throw new IllegalArgumentException("不是 IPv4: " + s);
            }
            int v = 0;
            for (String part : parts) {
                v = (v << 8) | (Integer.parseInt(part) & 0xFF);
            }
            return v;
        }
    }
}
