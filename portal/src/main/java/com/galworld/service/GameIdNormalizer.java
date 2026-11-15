/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * 目录名 → 合法游戏 id。规则与 {@link GameImportValidator} 的 ID 正则一致：
 * 小写字母、数字、点、下划线、连字符，2–32 位，首字符为字母或数字。
 *
 * 用户从 Windows 解压上来的目录常带空格、大写、中文，直接报错体验太差；
 * 这里统一做「修复而不是拒绝」，服务端仍是唯一权威。
 */
public final class GameIdNormalizer {

    static final int MIN_LEN = 2;
    static final int MAX_LEN = 32;

    private GameIdNormalizer() {
    }

    public static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean legal = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            b.append(legal ? c : '-');
        }
        String out = b.toString()
                .replaceAll("-{2,}", "-")
                .replaceAll("\\.{2,}", ".")
                .replaceAll("^[^a-z0-9]+", "")
                .replaceAll("[^a-z0-9]+$", "");
        if (out.length() > MAX_LEN) {
            out = out.substring(0, MAX_LEN).replaceAll("[^a-z0-9]+$", "");
        }
        if (out.length() < MIN_LEN) {
            out = "game-" + hash(raw);
        }
        return out;
    }

    /** 与 taken 冲突时追加 -2、-3…，保证结果始终合法且不超长 */
    public static String normalizeUnique(String raw, Predicate<String> taken) {
        String base = normalize(raw);
        String candidate = base;
        for (int i = 2; taken.test(candidate) && i < 100; i++) {
            String suffix = "-" + i;
            String head = base.length() + suffix.length() > MAX_LEN
                    ? base.substring(0, MAX_LEN - suffix.length()).replaceAll("[^a-z0-9]+$", "")
                    : base;
            candidate = head + suffix;
        }
        return candidate;
    }

    private static String hash(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1")
                    .digest((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                h.append(String.format("%02x", d[i]));
            }
            return h.toString();
        } catch (Exception e) {
            return "00000000";
        }
    }
}
