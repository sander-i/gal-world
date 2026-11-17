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
     * 请求体两种形态：
     * <ul>
     *   <li>{@code {"json": "{\"id\":...}"}} —— 后台页面上传文件内容</li>
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
            LocalePack saved;
            Object json = body.get("json");
            if (json instanceof String s && !s.isBlank()) {
                saved = i18n.importJson(s, String.valueOf(body.getOrDefault("id", "")));
            } else {
                Object raw = body.get("messages");
                if (!(raw instanceof Map<?, ?> map)) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("message", "缺少 messages 或 json 字段"));
                }
                Map<String, String> msgs = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    msgs.put(String.valueOf(e.getKey()),
                            e.getValue() == null ? "" : String.valueOf(e.getValue()));
                }
                String id = String.valueOf(body.getOrDefault("id", ""));
                String name = String.valueOf(body.getOrDefault("name", ""));
                saved = i18n.save(id, name, msgs);
            }
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
