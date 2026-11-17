/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.i18n;

import java.util.Map;

/**
 * 语言代码 → 该语言自己的写法。
 *
 * 界面上的语言按钮必须用这门语言自己的文字，否则看不懂当前界面语言的人
 * 就找不到自己的语言：界面已经是日文时，中文用户得能认出「简体中文」。
 * 所以这里存的是自称，不是「按当前语言翻译后的名字」。
 *
 * 内置四套之外再列一批常见语言：.po / CSV 这类只带译文、不带元数据的格式
 * 导入后没有 name 字段，靠这张表兜底，按钮才不会显示裸的 {@code ko}。
 * 表里没有的语言回退到 id——总比空白好。
 */
final class LocaleNames {

    /** 常用语言的自称；新增语言时在这里补一行 */
    private static final Map<String, String> NATIVE = Map.ofEntries(
            Map.entry("en", "English"),
            Map.entry("zh-CN", "简体中文"),
            Map.entry("zh-TW", "繁體中文"),
            Map.entry("ja", "日本語"),
            Map.entry("ko", "한국어"),
            Map.entry("ru", "Русский"),
            Map.entry("de", "Deutsch"),
            Map.entry("fr", "Français"),
            Map.entry("es", "Español"),
            Map.entry("pt", "Português"),
            Map.entry("pt-BR", "Português (Brasil)"),
            Map.entry("it", "Italiano"),
            Map.entry("pl", "Polski"),
            Map.entry("tr", "Türkçe"),
            Map.entry("vi", "Tiếng Việt"),
            Map.entry("th", "ไทย"),
            Map.entry("id", "Bahasa Indonesia"),
            Map.entry("uk", "Українська"),
            Map.entry("cs", "Čeština"),
            Map.entry("nl", "Nederlands"),
            Map.entry("sv", "Svenska"),
            Map.entry("ar", "العربية"));

    private LocaleNames() {
    }

    /** 该语言的自称；没有对照表时返回归一化后的 id */
    static String of(String id) {
        String norm = LocalePack.normalize(id);
        String hit = NATIVE.get(norm);
        return hit != null ? hit : norm;
    }

    /** 是否有对照表 */
    static boolean knows(String id) {
        return NATIVE.containsKey(LocalePack.normalize(id));
    }
}
