/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import io.github.sander_i.config.PathConfig;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
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
    private final Path tokenFile;
    private final List<Cidr> allowed = new ArrayList<>();

    public DeploySecurity(PathConfig paths,
                          @Value("${galworld.deploy.token:}") String token,
                          @Value("${galworld.deploy.allowed-cidrs:127.0.0.1/32,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}")
                          String cidrs) {
        this.tokenFile = paths.dataDir().resolve("deploy-token");
        this.configured = token != null && !token.isBlank();
        if (configured) {
            this.token = token;
        } else {
            String persisted = loadPersisted();
            if (persisted != null) {
                this.token = persisted;
                log.info("galworld.deploy.token 未配置，复用已保存的令牌（{}）", tokenFile);
            } else {
                this.token = randomToken();
                persist(this.token);
                log.warn("galworld.deploy.token 未配置，已生成随机令牌并保存到 {}", tokenFile);
                log.warn("令牌跨重启保持不变；删除该文件会重新生成，或在 application.yml 配置固定值");
            }
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

    /** 读取上次生成的令牌；文件缺失或内容不合法都当作没有 */
    private String loadPersisted() {
        try {
            String v = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            return isValidToken(v) ? v : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 令牌落盘，权限 0600（Windows 无 POSIX 权限位时跳过，靠目录权限保护） */
    private void persist(String value) {
        try {
            if (tokenFile.getParent() != null) {
                Files.createDirectories(tokenFile.getParent());
            }
            Files.writeString(tokenFile, value + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows 无 POSIX 权限位
            }
        } catch (IOException e) {
            log.warn("部署令牌写入失败 {}: {}", tokenFile, e.toString());
        }
    }

    /** 只接受长度合理、字符受限的令牌，避免把意外内容当令牌用 */
    private static boolean isValidToken(String v) {
        if (v.length() < 8 || v.length() > 256) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F') || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
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
