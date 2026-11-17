/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.i18n;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.repo.SettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 第三方语言包格式的解析与导入。
 *
 * 重点不在「能不能解析」，而在「键能不能对上」：本项目的键是中文原文，
 * 只有源文即键的格式能直接映射，符号键格式必须靠 keyMap。
 */
class LocaleImporterTest {

    @TempDir
    Path root;

    private I18nService service;

    @BeforeEach
    void setUp() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        when(repo.findAll()).thenReturn(List.of());
        service = new I18nService(new PathConfig(root.toString()), repo);
        service.init();
    }

    // ---------------- 格式识别 ----------------

    @Test
    void 按扩展名识别格式() {
        assertEquals("po", LocaleImporter.detect("ko.po", ""));
        assertEquals("xliff", LocaleImporter.detect("ko.xlf", ""));
        assertEquals("xliff", LocaleImporter.detect("ko.xliff", ""));
        assertEquals("properties", LocaleImporter.detect("messages_ko.properties", ""));
        assertEquals("arb", LocaleImporter.detect("app_ko.arb", ""));
        assertEquals("strings", LocaleImporter.detect("Localizable.strings", ""));
        assertEquals("csv", LocaleImporter.detect("ko.csv", ""));
        assertEquals("tsv", LocaleImporter.detect("ko.tsv", ""));
        assertEquals("json", LocaleImporter.detect("ko.json", "{}"));
    }

    @Test
    void 无扩展名时按内容嗅探() {
        assertEquals("po", LocaleImporter.detect("", "msgid \"设置\"\nmsgstr \"설정\"\n"));
        assertEquals("android", LocaleImporter.detect("", "<resources><string name=\"a\">b</string></resources>"));
        assertEquals("xliff", LocaleImporter.detect("", "<?xml version=\"1.0\"?><xliff/>"));
        assertEquals("json", LocaleImporter.detect("", "{\"a\":\"b\"}"));
    }

    @Test
    void 无法识别时报错并列出支持的格式() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LocaleImporter.detect("x.unknown", "\u0001\u0002binary"));
        assertTrue(e.getMessage().contains(".po"));
    }

    // ---------------- PO ----------------

    @Test
    void 解析PO直接以msgid为键() {
        String po = """
                msgid ""
                msgstr ""
                "Language: ko\\n"
                "Content-Type: text/plain; charset=UTF-8\\n"

                msgid "设置"
                msgstr "설정"

                msgid "没有这个游戏: {0}"
                msgstr "게임이 없습니다: {0}"
                """;
        LocaleImporter.Parsed p = LocaleImporter.parse("po", po, "fallback");
        assertEquals("ko", p.id());
        assertEquals("설정", p.entries().get("设置"));
        assertEquals("게임이 없습니다: {0}", p.entries().get("没有这个游戏: {0}"));
        // 头部条目 msgid 为空，不进结果
        assertFalse(p.entries().containsKey(""));
    }

    @Test
    void PO支持多行与转义() {
        String po = """
                msgid "第一行\\n第二行"
                msgstr "첫 줄\\n둘째 줄"

                msgid "带\\"引号\\"的文案"
                msgstr "따옴표 \\"포함\\""
                """;
        LocaleImporter.Parsed p = LocaleImporter.parse("po", po, "ko");
        assertEquals("첫 줄\n둘째 줄", p.entries().get("第一行\n第二行"));
        assertEquals("따옴표 \"포함\"", p.entries().get("带\"引号\"的文案"));
    }

    @Test
    void PO长条目续行拼接() {
        String po = """
                msgid ""
                "这是一段很长的"
                "中文文案"
                msgstr ""
                "아주 긴"
                "한국어 문장"
                """;
        LocaleImporter.Parsed p = LocaleImporter.parse("po", po, "ko");
        assertEquals("아주 긴한국어 문장", p.entries().get("这是一段很长的中文文案"));
    }

    @Test
    void PO跳过空译文与复数形式() {
        String po = """
                msgid "未翻译"
                msgstr ""

                msgid "一个文件"
                msgid_plural "{0} 个文件"
                msgstr[0] "파일 하나"
                msgstr[1] "파일 {0}개"
                """;
        LocaleImporter.Parsed p = LocaleImporter.parse("po", po, "ko");
        assertFalse(p.entries().containsKey("未翻译"));
        // 复数只取 [0]
        assertEquals("파일 하나", p.entries().get("一个文件"));
    }

    // ---------------- XLIFF ----------------

    @Test
    void 解析XLIFF以source为键() {
        String xlf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
                  <file source-language="zh-CN" target-language="ko" original="gal-world">
                    <body>
                      <trans-unit id="1">
                        <source>设置</source>
                        <target>설정</target>
                      </trans-unit>
                      <trans-unit id="2">
                        <source>没有这个游戏: {0}</source>
                        <target>게임이 없습니다: {0}</target>
                      </trans-unit>
                    </body>
                  </file>
                </xliff>
                """;
        LocaleImporter.Parsed p = LocaleImporter.parse("xliff", xlf, "fallback");
        assertEquals("ko", p.id());
        assertEquals("설정", p.entries().get("设置"));
        assertEquals(2, p.entries().size());
    }

    @Test
    void XLIFF缺target时跳过() {
        String xlf = """
                <?xml version="1.0"?>
                <xliff version="1.2"><file target-language="ko"><body>
                  <trans-unit id="1"><source>设置</source><target>설정</target></trans-unit>
                  <trans-unit id="2"><source>未译</source><target></target></trans-unit>
                </body></file></xliff>
                """;
        LocaleImporter.Parsed p = LocaleImporter.parse("xliff", xlf, "ko");
        assertEquals(1, p.entries().size());
    }

    @Test
    void XML拒绝DOCTYPE防止读本机文件() {
        String evil = """
                <?xml version="1.0"?>
                <!DOCTYPE xliff [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <xliff version="1.2"><file><body>
                  <trans-unit id="1"><source>&xxe;</source><target>x</target></trans-unit>
                </body></file></xliff>
                """;
        assertThrows(IllegalArgumentException.class,
                () -> LocaleImporter.parse("xliff", evil, "ko"));
    }

    // ---------------- Android ----------------

    @Test
    void 解析Android字符串资源() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                  <string name="nav_library">게임 라이브러리</string>
                  <string name="error_not_found">게임이 없습니다: %1$s</string>
                  <string name="do_not_translate" translatable="false">Gal World</string>
                  <string name="with_quote">It\\'s here</string>
                </resources>
                """;
        LocaleImporter.Parsed p = LocaleImporter.parse("android", xml, "ko");
        assertEquals("게임 라이브러리", p.entries().get("nav_library"));
        assertFalse(p.entries().containsKey("do_not_translate"));
        assertEquals("It's here", p.entries().get("with_quote"));
    }

    // ---------------- ARB ----------------

    @Test
    void 解析FlutterARB跳过元数据() {
        String arb = """
                {
                  "@@locale": "ko",
                  "nav_library": "게임 라이브러리",
                  "@nav_library": { "description": "Navigation label" },
                  "count_files": "{count}개 파일",
                  "@count_files": { "placeholders": { "count": {} } }
                }
                """;
        LocaleImporter.Parsed p = LocaleImporter.parse("arb", arb, "fallback");
        assertEquals("ko", p.id());
        assertEquals("게임 라이브러리", p.entries().get("nav_library"));
        assertEquals("{count}개 파일", p.entries().get("count_files"));
        assertFalse(p.entries().keySet().stream().anyMatch(k -> k.startsWith("@")));
    }

    // ---------------- properties ----------------

    @Test
    void 解析JavaProperties含Unicode转义() {
        // Java 的 unicode 转义在词法分析前展开，源码里写 "\\uac8c" 也会被当成
        // 게 处理掉。要测「properties 文本里含字面量反斜杠 u」，
        // 只能用 (char) 92 拼出来。
        char bs = 92;
        String props = "nav.library=" + bs + "uac8c" + bs + "uc784 " + bs + "ub77c" + bs
                + "uc774" + bs + "ube0c" + bs + "ub7ec" + bs + "ub9ac\n"
                + "error.notfound=" + bs + "uac8c" + bs + "uc784" + bs + "uc774 " + bs
                + "uc5c6" + bs + "uc2b5" + bs + "ub2c8" + bs + "ub2e4: {0}\n"
                + "locale=ko\n";
        LocaleImporter.Parsed p = LocaleImporter.parse("properties", props, "fallback");
        assertEquals("ko", p.id());
        assertEquals("게임 라이브러리", p.entries().get("nav.library"));
        assertEquals("게임이 없습니다: {0}", p.entries().get("error.notfound"));
    }

    // ---------------- Apple .strings ----------------

    @Test
    void 解析AppleStrings() {
        String s = """
                /* 导航 */
                "nav_library" = "게임 라이브러리";
                "error_not_found" = "게임이 없습니다: %@";
                "multi_line" = "첫 줄
                둘째 줄";
                """;
        LocaleImporter.Parsed p = LocaleImporter.parse("strings", s, "ko");
        assertEquals("게임 라이브러리", p.entries().get("nav_library"));
        assertEquals("게임이 없습니다: %@", p.entries().get("error_not_found"));
        assertTrue(p.entries().get("multi_line").contains("첫 줄"));
    }

    // ---------------- CSV ----------------

    @Test
    void 解析两列CSV() {
        String csv = "设置,설정\n没有这个游戏: {0},게임이 없습니다: {0}\n";
        LocaleImporter.Parsed p = LocaleImporter.parse("csv", csv, "ko");
        assertEquals("설정", p.entries().get("设置"));
        assertEquals(2, p.entries().size());
    }

    @Test
    void CSV带表头时按source列定位() {
        String csv = "id,source,target,comment\n"
                + "1,设置,설정,\n"
                + "2,没有这个游戏: {0},게임이 없습니다: {0},\n";
        LocaleImporter.Parsed p = LocaleImporter.parse("csv", csv, "ko");
        assertEquals("설정", p.entries().get("设置"));
        assertFalse(p.entries().containsKey("id"));
    }

    @Test
    void CSV引号字段含逗号与换行() {
        String csv = "source,target\n"
                + "\"带,逗号的原文\",\"쉼표,포함\"\n"
                + "\"多行\n原文\",\"여러 줄\n번역\"\n";
        LocaleImporter.Parsed p = LocaleImporter.parse("csv", csv, "ko");
        assertEquals("쉼표,포함", p.entries().get("带,逗号的原文"));
        assertEquals("여러 줄\n번역", p.entries().get("多行\n原文"));
    }

    @Test
    void TSV按制表符切分() {
        String tsv = "设置\t설정\n";
        LocaleImporter.Parsed p = LocaleImporter.parse("tsv", tsv, "ko");
        assertEquals("설정", p.entries().get("设置"));
    }

    // ---------------- i18next 嵌套 JSON ----------------

    @Test
    void i18next嵌套JSON展开成点分键() {
        String json = """
                {
                  "nav": { "library": "게임 라이브러리", "settings": "설정" },
                  "error": { "notFound": "게임이 없습니다" }
                }
                """;
        LocaleImporter.Parsed p = LocaleImporter.parse("json", json, "ko");
        assertEquals("게임 라이브러리", p.entries().get("nav.library"));
        assertEquals("게임이 없습니다", p.entries().get("error.notFound"));
    }

    @Test
    void i18next的translation包装也能展开() {
        String json = "{\"translation\":{\"nav\":{\"library\":\"게임 라이브러리\"}}}";
        LocaleImporter.Parsed p = LocaleImporter.parse("json", json, "ko");
        assertEquals("게임 라이브러리", p.entries().get("nav.library"));
    }

    // ---------------- 键映射 ----------------

    @Test
    void 源文即键的格式无需映射() {
        Set<String> sources = Set.of("设置", "没有这个游戏: {0}");
        LocaleImporter.Resolved r = LocaleImporter.resolve(
                Map.of("设置", "설정", "没有这个游戏: {0}", "게임이 없습니다: {0}"),
                Map.of(), sources);
        assertEquals(2, r.messages().size());
        assertTrue(r.skippedSample().isEmpty());
    }

    @Test
    void 符号键靠keyMap映射到源文() {
        Set<String> sources = Set.of("设置");
        LocaleImporter.Resolved r = LocaleImporter.resolve(
                Map.of("nav.library", "설정", "unknown.key", "무시"),
                Map.of("nav.library", "设置"), sources);
        assertEquals("설정", r.messages().get("设置"));
        assertEquals(List.of("unknown.key"), r.skippedSample());
    }

    @Test
    void 没有keyMap时符号键全部跳过() {
        Set<String> sources = Set.of("设置");
        LocaleImporter.Resolved r = LocaleImporter.resolve(
                Map.of("nav.library", "설정"), Map.of(), sources);
        assertTrue(r.messages().isEmpty());
        assertEquals(List.of("nav.library"), r.skippedSample());
    }

    // ---------------- 占位符校验 ----------------

    @Test
    void 占位符编号不一致能被发现() {
        List<String> bad = LocaleImporter.placeholderMismatches(Map.of(
                "没有这个游戏: {0}", "게임이 없습니다: {1}",
                "共 {0} 个", "총 {0}개",
                "无占位符", "없음"));
        assertEquals(List.of("没有这个游戏: {0}"), bad);
    }

    // ---------------- 端到端导入 ----------------

    @Test
    void 导入PO后能按该语言翻译() throws Exception {
        String po = """
                msgid ""
                msgstr ""
                "Language: ko\\n"

                msgid "设置"
                msgstr "설정"
                """;
        I18nService.ImportResult r = service.importAny(po, "ko.po", "ko", Map.of());
        assertEquals("ko", r.pack().id());
        assertEquals("po", r.format());
        assertEquals(1, r.imported());
        assertEquals("설정", service.translate("ko", "设置"));
        // 未提供的键回退中文原文
        assertEquals("功能开关", service.translate("ko", "功能开关"));
    }

    @Test
    void 导入XLIFF后能按该语言翻译() throws Exception {
        String xlf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xliff version="1.2"><file target-language="ja"><body>
                  <trans-unit id="1"><source>设置</source><target>設定</target></trans-unit>
                </body></file></xliff>
                """;
        service.importAny(xlf, "ja.xlf", "ja", Map.of());
        assertEquals("設定", service.translate("ja", "设置"));
    }

    @Test
    void 导入符号键格式需要keyMap否则报错() {
        String props = "nav.library=\\uc124\\uc815\n";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.importAny(props, "ko.properties", "ko", Map.of()));
        assertTrue(e.getMessage().contains("keyMap"));
    }

    @Test
    void 导入符号键格式带keyMap成功() throws Exception {
        String props = "nav.library=\\uc124\\uc815\n";
        I18nService.ImportResult r = service.importAny(props, "ko.properties", "ko",
                Map.of("nav.library", "设置"));
        assertEquals(1, r.imported());
        assertEquals("설정", service.translate("ko", "设置"));
    }

    @Test
    void 导入结果带缺失与占位符提示() throws Exception {
        String po = """
                msgid "没有这个游戏: {0}"
                msgstr "게임이 없습니다: {1}"
                """;
        I18nService.ImportResult r = service.importAny(po, "ko.po", "ko", Map.of());
        assertEquals(List.of("没有这个游戏: {0}"), r.placeholderMismatches());
        assertFalse(r.missingSample().isEmpty());
    }

    @Test
    void 超过大小上限被拒绝() {
        String big = "msgid \"" + "x".repeat((int) I18nService.MAX_PACK_BYTES + 1) + "\"\nmsgstr \"y\"\n";
        assertThrows(IllegalArgumentException.class,
                () -> service.importAny(big, "ko.po", "ko", Map.of()));
    }

    // ---------------- 导出 ----------------

    @Test
    void 导出JSON可再导入() throws Exception {
        service.save("ko", "한국어", Map.of("设置", "설정", "功能开关", "기능"));
        String json = service.export("ko", "json");
        assertTrue(json.contains("\"id\" : \"ko\""));
        assertTrue(json.contains("\"设置\" : \"설정\""));
    }

    @Test
    void 导出PO可再导入() throws Exception {
        service.save("ko", "한국어", Map.of("设置", "설정"));
        String po = service.export("ko", "po");
        assertTrue(po.contains("msgid \"设置\""));
        assertTrue(po.contains("msgstr \"설정\""));

        LocaleImporter.Parsed p = LocaleImporter.parse("po", po, "ko");
        assertEquals("설정", p.entries().get("设置"));
    }

    @Test
    void 导出XLIFF可再导入() throws Exception {
        service.save("ko", "한국어", Map.of("设置", "설정"));
        String xlf = service.export("ko", "xliff");
        LocaleImporter.Parsed p = LocaleImporter.parse("xliff", xlf, "ko");
        assertEquals("설정", p.entries().get("设置"));
    }

    @Test
    void 导出CSV可再导入() throws Exception {
        service.save("ko", "한국어", Map.of("设置", "설정", "带,逗号", "쉼표,포함"));
        String csv = service.export("ko", "csv");
        LocaleImporter.Parsed p = LocaleImporter.parse("csv", csv, "ko");
        assertEquals("설정", p.entries().get("设置"));
        assertEquals("쉼표,포함", p.entries().get("带,逗号"));
    }

    @Test
    void 导出properties可再导入() throws Exception {
        service.save("ko", "한국어", Map.of("设置", "설정"));
        String props = service.export("ko", "properties");
        LocaleImporter.Parsed p = LocaleImporter.parse("properties", props, "ko");
        assertEquals("설정", p.entries().get("设置"));
    }

    @Test
    void 导出未知语言报错() {
        assertThrows(IllegalArgumentException.class, () -> service.export("xx", "json"));
    }

    @Test
    void 源文键清单来自内置包() {
        Set<String> keys = service.sourceKeys();
        assertTrue(keys.contains("设置"));
        assertTrue(keys.size() > 1000);
    }
}
