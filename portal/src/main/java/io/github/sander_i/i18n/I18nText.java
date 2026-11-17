/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实体字段的多语言覆盖。
 *
 * 公司 / 标签 / 汉化组的名称、别名、简介需要按界面语言显示不同文字
 * （例如「ゆずソフト」在英文界面显示 Yuzusoft）。每个字段旁存一列 JSON：
 *
 * <pre>
 * {"en": {"name": "Yuzusoft", "alias": "yuzu-soft", "description": "..."},
 *  "zh-CN": {"name": "柚子社"}}
 * </pre>
 *
 * 缺失的语言或字段回退到主列的值，所以老数据不迁移也能正常显示。
 * 解析失败按「没有覆盖」处理：坏 JSON 不能让整个列表 500。
 */
public final class I18nText {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private I18nText() {
    }

    /** 解析成 locale → {字段: 值}；空串或非法 JSON 返回空表 */
    public static Map<String, Map<String, String>> parse(String json) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isObject()) {
                return out;
            }
            root.fields().forEachRemaining(localeEntry -> {
                String locale = LocalePack.normalize(localeEntry.getKey());
                if (locale.isEmpty() || !localeEntry.getValue().isObject()) {
                    return;
                }
                Map<String, String> fields = new LinkedHashMap<>();
                localeEntry.getValue().fields().forEachRemaining(f -> {
                    if (f.getValue().isTextual()) {
                        fields.put(f.getKey(), f.getValue().asText());
                    }
                });
                if (!fields.isEmpty()) {
                    out.put(locale, fields);
                }
            });
        } catch (Exception ignored) {
            // 坏数据按「没有覆盖」处理
        }
        return out;
    }

    /**
     * 取某语言下某字段的值；没有覆盖或覆盖为空串时返回 fallback。
     *
     * 空串视为「没填」而不是「故意留空」：维护页留空表示不覆盖，
     * 这样删掉译文就自动回退原文，不会在界面上留一片空白。
     */
    public static String pick(String json, String locale, String field, String fallback) {
        if (locale == null || locale.isBlank()) {
            return fallback;
        }
        Map<String, String> fields = parse(json).get(LocalePack.normalize(locale));
        if (fields == null) {
            return fallback;
        }
        String value = fields.get(field);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 合并一次覆盖，返回新的 JSON 串；value 为空表示删除该语言的该字段 */
    public static String merge(String json, String locale, String field, String value) {
        String norm = LocalePack.normalize(locale == null ? "" : locale);
        if (norm.isEmpty() || field == null || field.isBlank()) {
            return json;
        }
        Map<String, Map<String, String>> all = parse(json);
        Map<String, String> fields = all.computeIfAbsent(norm, k -> new LinkedHashMap<>());
        if (value == null || value.isBlank()) {
            fields.remove(field);
        } else {
            fields.put(field, value);
        }
        if (fields.isEmpty()) {
            all.remove(norm);
        }
        return write(all);
    }

    /** 序列化为 JSON；空表返回空串，避免库里存 "{}" */
    public static String write(Map<String, Map<String, String>> all) {
        if (all == null || all.isEmpty()) {
            return "";
        }
        ObjectNode root = MAPPER.createObjectNode();
        all.forEach((locale, fields) -> {
            if (fields == null || fields.isEmpty()) {
                return;
            }
            ObjectNode node = root.putObject(locale);
            fields.forEach(node::put);
        });
        if (root.isEmpty()) {
            return "";
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "";
        }
    }

    /** 把一次请求里的多语言覆盖合并进已有 JSON */
    public static String mergeAll(String json, Map<String, Map<String, String>> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return json == null ? "" : json;
        }
        Map<String, Map<String, String>> all = parse(json);
        incoming.forEach((locale, fields) -> {
            String norm = LocalePack.normalize(locale == null ? "" : locale);
            if (norm.isEmpty() || fields == null || fields.isEmpty()) {
                return;
            }
            Map<String, String> target = all.computeIfAbsent(norm, k -> new LinkedHashMap<>());
            fields.forEach((field, value) -> {
                if (value == null || value.isBlank()) {
                    target.remove(field);
                } else {
                    target.put(field, value);
                }
            });
            if (target.isEmpty()) {
                all.remove(norm);
            }
        });
        return write(all);
    }
}
