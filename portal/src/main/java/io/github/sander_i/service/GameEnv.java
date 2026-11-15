/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code game.env} 的读写。
 *
 * 该文件被 {@code run-game.sh} 用 {@code set -a; . "$ENVFILE"} 加载，
 * 因此取值必须是合法的 shell 赋值：含空格或特殊字符时用单引号包裹，
 * 解析时再脱去引号。
 */
public final class GameEnv {

    private final Map<String, String> values = new LinkedHashMap<>();

    private GameEnv() {
    }

    public static GameEnv parse(List<String> lines) {
        GameEnv env = new GameEnv();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            env.values.put(key, unquote(value));
        }
        return env;
    }

    public static GameEnv empty() {
        return new GameEnv();
    }

    public String get(String key) {
        return values.get(key);
    }

    public String get(String key, String fallback) {
        String v = values.get(key);
        return v == null || v.isEmpty() ? fallback : v;
    }

    public int getInt(String key, int fallback) {
        String v = values.get(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public GameEnv set(String key, String value) {
        values.put(key, value == null ? "" : value);
        return this;
    }

    /** 当前文件里出现过的键，按出现顺序。用于保留门户不管理的键。 */
    public java.util.Set<String> keys() {
        return java.util.Collections.unmodifiableSet(values.keySet());
    }

    public List<String> render() {
        return values.entrySet().stream()
                .map(e -> e.getKey() + "=" + quoteIfNeeded(e.getValue()))
                .toList();
    }

    private static String quoteIfNeeded(String value) {
        if (value.isEmpty()) {
            return "";
        }
        boolean needsQuote = value.chars().anyMatch(c ->
                !(Character.isLetterOrDigit(c) || c == '/' || c == '.' || c == '_' || c == '-' || c == ':' || c == ',' || c == '+'));
        if (!needsQuote) {
            return value;
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("'\\''", "'");
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
