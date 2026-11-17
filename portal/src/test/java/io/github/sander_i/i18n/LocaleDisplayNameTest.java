/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.i18n;

import io.github.sander_i.config.PathConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 语言按钮上的名字必须是该语言自己的写法。
 *
 * 界面是日文时，中文用户得能认出「简体中文」才能切回去；若按当前界面
 * 语言翻译成「簡体字中国語」，看不懂日文的人就找不到自己的语言了。
 */
class LocaleDisplayNameTest {

    private static I18nService service(Path dir) {
        I18nService svc = new I18nService(new PathConfig(dir.toString()), null);
        svc.init();
        return svc;
    }

    @Test
    void builtinLocalesUseTheirOwnName(@TempDir Path dir) {
        I18nService svc = service(dir);
        assertEquals("English", svc.displayName("en"));
        assertEquals("简体中文", svc.displayName("zh-CN"));
        assertEquals("繁體中文", svc.displayName("zh-TW"));
        assertEquals("日本語", svc.displayName("ja"));
    }

    @Test
    void localeAliasesResolveToSameName(@TempDir Path dir) {
        I18nService svc = service(dir);
        assertEquals("简体中文", svc.displayName("zh-Hans"));
        assertEquals("繁體中文", svc.displayName("zh-Hant-TW"));
        assertEquals("日本語", svc.displayName("ja-JP"));
    }

    /** 只有译文、没有元数据的格式（.po / CSV）导入后也要有可读的名字 */
    @Test
    void externalPackWithoutNameFallsBackToNativeName(@TempDir Path dir) throws Exception {
        I18nService svc = service(dir);
        LocalePack saved = svc.save("ko", "", Map.of("保存", "저장"));
        assertEquals("한국어", saved.name());
        assertEquals("한국어", saved.nativeName());
        assertNotEquals("ko", saved.name(), "不能把裸 id 显示给用户");
    }

    @Test
    void unknownLocaleStillFallsBackToId(@TempDir Path dir) throws Exception {
        I18nService svc = service(dir);
        LocalePack saved = svc.save("xx", "", Map.of("保存", "save"));
        assertEquals("xx", saved.name(), "未知语言没有对照表，只能显示 id");
    }
}
