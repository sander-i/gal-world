/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.i18n.I18n;
import io.github.sander_i.i18n.I18nService;
import io.github.sander_i.i18n.LocalePack;
import io.github.sander_i.service.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语言包的公开读接口。
 *
 * 前端启动时就要拿到语言清单与消息表，否则首屏没法翻译，所以这里不鉴权。
 * 安装 / 删除走 {@link AdminI18nController}，与其它运维设置同权（部署令牌）。
 */
@RestController
@RequestMapping("/api/i18n")
public class I18nController {

    private final I18nService i18n;
    private final SettingsService settings;

    public I18nController(I18nService i18n, SettingsService settings) {
        this.i18n = i18n;
        this.settings = settings;
    }

    /**
     * 语言清单 + 站点默认语言。
     *
     * 不含消息表：清单每次页面加载都要，消息表按当前语言单独取，
     * 以后语言包变多也不会把首屏请求撑大。
     */
    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> packs = i18n.summaries();
        out.put("defaultLocale", i18n.defaultLocale());
        out.put("languages", packs);
        out.put("directory", i18n.directoryHint());
        return out;
    }

    /** 某语言的完整消息表；前端启动时加载当前语言 */
    @GetMapping("/{locale}")
    public ResponseEntity<?> messages(@PathVariable String locale) {
        if (!i18n.exists(locale)) {
            return ResponseEntity.status(404)
                    .body(Map.of("message", I18n.t("没有这个语言包: {0}", locale)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", LocalePack.normalize(locale));
        out.put("name", i18n.displayName(locale));
        out.put("messages", i18n.messages(locale));
        return ResponseEntity.ok(out);
    }
}
