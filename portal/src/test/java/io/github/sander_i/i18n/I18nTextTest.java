/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.i18n;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I18nTextTest {

    @Test
    void pickFallsBackWhenLocaleOrFieldMissing() {
        String json = I18nText.merge("", "en", "name", "Yuzusoft");
        assertEquals("Yuzusoft", I18nText.pick(json, "en", "name", "ゆずソフト"));
        // 该语言没有这个字段
        assertEquals("ゆずソフト", I18nText.pick(json, "en", "alias", "ゆずソフト"));
        // 该语言没有覆盖
        assertEquals("柚子社", I18nText.pick(json, "zh-CN", "name", "柚子社"));
    }

    @Test
    void blankValueIsTreatedAsMissing() {
        String json = I18nText.merge("", "en", "name", "Yuzusoft");
        String cleared = I18nText.merge(json, "en", "name", "   ");
        assertEquals("", cleared, "删掉唯一字段后整张表应为空");
        assertEquals("ゆずソフト", I18nText.pick(cleared, "en", "name", "ゆずソフト"));
    }

    @Test
    void mergeKeepsOtherLocalesAndFields() {
        String json = I18nText.merge("", "en", "name", "Yuzusoft");
        json = I18nText.merge(json, "zh-CN", "name", "柚子社");
        json = I18nText.merge(json, "en", "alias", "yuzu-soft");
        assertEquals("Yuzusoft", I18nText.pick(json, "en", "name", ""));
        assertEquals("yuzu-soft", I18nText.pick(json, "en", "alias", ""));
        assertEquals("柚子社", I18nText.pick(json, "zh-CN", "name", ""));
    }

    @Test
    void localeAliasesAreNormalized() {
        String json = I18nText.merge("", "en-US", "name", "Yuzusoft");
        assertEquals("Yuzusoft", I18nText.pick(json, "en", "name", ""));
        String hant = I18nText.merge("", "zh-Hant-TW", "name", "柚子社");
        assertEquals("柚子社", I18nText.pick(hant, "zh-TW", "name", ""));
    }

    @Test
    void brokenJsonDegradesToNoOverrides() {
        assertEquals("原文", I18nText.pick("{not json", "en", "name", "原文"));
        assertEquals("原文", I18nText.pick("[]", "en", "name", "原文"));
        assertTrue(I18nText.parse("{\"en\": \"not-an-object\"}").isEmpty());
    }

    @Test
    void mergeAllAppliesWholeRequestAtOnce() {
        Map<String, Map<String, String>> incoming = new LinkedHashMap<>();
        incoming.put("en", Map.of("name", "Yuzusoft", "alias", "yuzu-soft"));
        incoming.put("ja", Map.of("name", "ゆずソフト"));
        String json = I18nText.mergeAll("", incoming);
        assertEquals("Yuzusoft", I18nText.pick(json, "en", "name", ""));
        assertEquals("yuzu-soft", I18nText.pick(json, "en", "alias", ""));
        assertEquals("ゆずソフト", I18nText.pick(json, "ja", "name", ""));
    }

    @Test
    void emptyInputsProduceEmptyString() {
        assertEquals("", I18nText.write(null));
        assertEquals("", I18nText.write(Map.of()));
        assertEquals("", I18nText.mergeAll("", null));
    }
}
