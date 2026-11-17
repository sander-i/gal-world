/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.deploy.DeploySecurity;
import io.github.sander_i.i18n.I18nService;
import io.github.sander_i.i18n.LocalePack;
import io.github.sander_i.service.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语言包安装 / 删除（后台管理）。
 *
 * 与其它运维设置同权：必须带部署令牌，且受允许网段限制。
 * 语言包只是文本，但仍是一份会被所有访客看到的站点内容，不能开放给任意人写。
 */
@RestController
@RequestMapping("/api/admin/i18n")
public class AdminI18nController {

    private final I18nService i18n;
    private final SettingsService settings;
    private final DeploySecurity security;

    public AdminI18nController(I18nService i18n, SettingsService settings,
                               DeploySecurity security) {
        this.i18n = i18n;
        this.settings = settings;
        this.security = security;
    }

    /**
     * 安装 / 覆盖语言包。
     *
     * 请求体三种形态：
     * <ul>
     *   <li>{@code {"file": "...", "fileName": "ko.po", "id": "ko", "keyMap": {...}}}
     *       —— 上传文件内容，按扩展名自动识别格式（推荐）</li>
     *   <li>{@code {"json": "{\"id\":...}"}} —— 本项目 JSON</li>
     *   <li>{@code {"id": "...", "name": "...", "messages": {...}}} —— 直接给结构</li>
     * </ul>
     */
    @PostMapping
    public ResponseEntity<?> install(@RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest req) {
        try {
            security.check(req);
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "请求体为空"));
        }
        try {
            Object file = body.get("file");
            Object json = body.get("json");
            if (file instanceof String s && !s.isBlank()) {
                return ResponseEntity.ok(installFile(s, body));
            }
            if (json instanceof String s && !s.isBlank()) {
                // 兼容旧调用：json 字段里也可能塞的是 .po 之类
                String name = String.valueOf(body.getOrDefault("fileName", ""));
                if (!name.isBlank() || !looksLikeOurJson(s)) {
                    return ResponseEntity.ok(installFile(s, body));
                }
                LocalePack saved = i18n.importJson(s, String.valueOf(body.getOrDefault("id", "")));
                i18n.refreshDefaultLocale();
                return ResponseEntity.ok(saved.summary());
            }
            Object raw = body.get("messages");
            if (!(raw instanceof Map<?, ?> map)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "缺少 file / json / messages 字段"));
            }
            Map<String, String> msgs = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                msgs.put(String.valueOf(e.getKey()),
                        e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
            String id = String.valueOf(body.getOrDefault("id", ""));
            String name = String.valueOf(body.getOrDefault("name", ""));
            LocalePack saved = i18n.save(id, name, msgs);
            // 新装的语言包可能正好是站点默认语言（此前不存在，默认回退 en）
            i18n.refreshDefaultLocale();
            return ResponseEntity.ok(saved.summary());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "语言包解析失败: " + e.getMessage()));
        }
    }

    /** 我们的 JSON 有 messages 对象；否则按第三方格式走导入器 */
    private static boolean looksLikeOurJson(String content) {
        return content.contains("\"messages\"");
    }

    private Map<String, Object> installFile(String content, Map<String, Object> body)
            throws IOException {
        String fileName = String.valueOf(body.getOrDefault("fileName", ""));
        String fallbackId = String.valueOf(body.getOrDefault("id", ""));
        if (fallbackId.isBlank() && fileName.contains(".")) {
            fallbackId = fileName.substring(0, fileName.lastIndexOf('.'));
        }
        Map<String, String> keyMap = new LinkedHashMap<>();
        Object rawMap = body.get("keyMap");
        if (rawMap instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                keyMap.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        I18nService.ImportResult result =
                i18n.importAny(content, fileName, fallbackId, keyMap);
        i18n.refreshDefaultLocale();
        Map<String, Object> out = new LinkedHashMap<>(result.pack().summary());
        out.put("format", result.format());
        out.put("imported", result.imported());
        out.put("skipped", result.skipped());
        out.put("skippedSample", result.skippedSample());
        out.put("missingSample", result.missingSample());
        out.put("placeholderMismatches", result.placeholderMismatches());
        return out;
    }

    /**
     * 导出语言包，供译者在市场工具里编辑后导回。
     *
     * 响应是纯文本附件：{@code format} 取 json / po / xliff / csv / properties。
     */
    @GetMapping(value = "/{locale}/export", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<?> export(@PathVariable String locale,
                                    @RequestParam(defaultValue = "json") String format,
                                    HttpServletRequest req) {
        try {
            security.check(req);
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
        }
        try {
            String text = i18n.export(locale, format);
            String id = LocalePack.normalize(locale);
            String ext = switch (format) {
                case "po" -> "po";
                case "xliff" -> "xlf";
                case "csv" -> "csv";
                case "properties" -> "properties";
                default -> "json";
            };
            return ResponseEntity.ok()
                    .header("Content-Disposition",
                            "attachment; filename=\"" + id + "." + ext + "\"")
                    .body(text);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 源文键清单，供生成 keyMap 用。
     *
     * 符号键格式（i18next / properties / Android XML / .arb / .strings）无法自动
     * 映射到中文原文，调用方拿到这份清单后人工建映射表。
     */
    @GetMapping("/source-keys")
    public ResponseEntity<?> sourceKeys(HttpServletRequest req) {
        try {
            security.check(req);
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
        }
        java.util.List<String> keys = new java.util.ArrayList<>(i18n.sourceKeys());
        java.util.Collections.sort(keys);
        return ResponseEntity.ok(Map.of("count", keys.size(), "keys", keys));
    }

    /** 支持的导入格式与各自是否需要 keyMap */
    @GetMapping("/formats")
    public Map<String, Object> formats() {
        return Map.of(
                "import", java.util.List.of(
                        Map.of("format", "json", "ext", ".json",
                                "needsKeyMap", false,
                                "note", "本项目格式；i18next / vue-i18n 嵌套 JSON 需要 keyMap"),
                        Map.of("format", "po", "ext", ".po",
                                "needsKeyMap", false, "note", "msgid 即中文原文"),
                        Map.of("format", "xliff", "ext", ".xlf/.xliff",
                                "needsKeyMap", false, "note", "source 即中文原文"),
                        Map.of("format", "csv", "ext", ".csv/.tsv",
                                "needsKeyMap", false,
                                "note", "两列 原文,译文；表头含 source 时按表头定位"),
                        Map.of("format", "properties", "ext", ".properties",
                                "needsKeyMap", true, "note", "符号键，需 keyMap"),
                        Map.of("format", "android", "ext", ".xml",
                                "needsKeyMap", true, "note", "strings.xml，符号键，需 keyMap"),
                        Map.of("format", "arb", "ext", ".arb",
                                "needsKeyMap", true, "note", "Flutter，符号键，需 keyMap"),
                        Map.of("format", "strings", "ext", ".strings",
                                "needsKeyMap", true, "note", "Apple，符号键，需 keyMap")),
                "export", java.util.List.of("json", "po", "xliff", "csv", "properties"));
    }

    /**
     * 删除外部语言包。
     *
     * 内置语言不能删：删了页面就没有兜底语言，界面上会出现成片键名。
     * 想恢复内置版本，删掉同 id 的外部覆盖文件即可——{@code ?force=1} 走这条路，
     * 此时内置包本身仍在。
     */
    @DeleteMapping("/{locale}")
    public ResponseEntity<?> delete(@PathVariable String locale,
                                    @RequestParam(value = "force", defaultValue = "false")
                                    boolean force,
                                    HttpServletRequest req) {
        try {
            security.check(req);
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
        }
        String id = LocalePack.normalize(locale);
        boolean builtin = I18nService.BUILTIN_IDS.contains(id);
        if (builtin && !force) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "内置语言包不能删除: " + id));
        }
        if (!i18n.isExternal(id)) {
            return ResponseEntity.status(404)
                    .body(Map.of("message", "没有这个外部语言包: " + id));
        }
        try {
            i18n.delete(id);
            // 删掉的正是站点默认语言：回退英文，避免默认值指向不存在的包
            if (id.equals(settings.get(SettingsService.KEY_LANGUAGE))) {
                settings.update(Map.of(SettingsService.KEY_LANGUAGE, I18nService.DEFAULT_LOCALE));
            }
            return ResponseEntity.ok(Map.of("deleted", id, "restoredBuiltin", builtin));
        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "删除失败: " + e.getMessage()));
        }
    }
}
