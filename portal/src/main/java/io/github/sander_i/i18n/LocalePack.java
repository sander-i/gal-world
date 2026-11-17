/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.i18n;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 一个语言包。
 *
 * 语言包是纯数据（JSON），内置包随 jar 分发，外部包放在
 * {@code <root>/data/i18n/} 下即可生效——加一门语言不用改代码。
 *
 * @param id          语言 id，如 {@code en} / {@code zh-CN} / {@code ja}
 * @param name        该语言的自称，如 {@code 简体中文}、{@code English}
 * @param nativeName  与 {@code name} 相同，保留字段名是为了 JSON 可读性
 * @param builtin     是否随 jar 分发
 * @param source      来源目录，供后台页面展示（{@code classpath} 或磁盘路径）
 * @param messages    键值表
 */
public record LocalePack(String id, String name, String nativeName, boolean builtin,
                         String source, Map<String, String> messages) {

    /** 按语言 id 归一化：{@code zh-hant} → {@code zh-TW}，{@code en-US} → {@code en} */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.trim().replace('_', '-');
        if (v.isEmpty()) {
            return "";
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.equals("zh") || lower.startsWith("zh-cn") || lower.startsWith("zh-hans")
                || lower.equals("zh-sg")) {
            return "zh-CN";
        }
        if (lower.startsWith("zh-tw") || lower.startsWith("zh-hk") || lower.startsWith("zh-mo")
                || lower.startsWith("zh-hant")) {
            return "zh-TW";
        }
        if (lower.startsWith("ja")) {
            return "ja";
        }
        if (lower.startsWith("en")) {
            return "en";
        }
        // 其它语言原样保留主语言标签，大小写统一成 xx-YY
        String[] parts = v.split("-");
        if (parts.length == 1) {
            return parts[0].toLowerCase(Locale.ROOT);
        }
        return parts[0].toLowerCase(Locale.ROOT) + "-" + parts[1].toUpperCase(Locale.ROOT);
    }

    /** 语言包 id 只允许字母数字与连字符，避免被当成文件名时越界 */
    public static boolean isValidId(String id) {
        return id != null && id.matches("[A-Za-z][A-Za-z0-9-]{1,15}");
    }

    public Map<String, String> messagesOrEmpty() {
        return messages == null ? Map.of() : messages;
    }

    /** 供后台页面展示的摘要，不含全部消息 */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("nativeName", nativeName == null || nativeName.isBlank() ? name : nativeName);
        out.put("builtin", builtin);
        out.put("source", source);
        out.put("keyCount", messagesOrEmpty().size());
        return out;
    }
}
