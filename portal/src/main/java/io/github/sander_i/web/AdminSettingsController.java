/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.deploy.DeploySecurity;
import io.github.sander_i.service.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维设置：上传上限、图片派生图开关、元件下载源。
 *
 * 这些值改变服务端校验与磁盘行为，与用户个人偏好分权：
 * 必须带部署令牌，且只接受 {@link SettingsService#ADMIN_KEYS} 白名单。
 *
 * 每次成功修改都写一条审计日志：来源地址、键名、旧值 → 新值。
 * 不含令牌与请求头——日志可能被转发或归档，凭据不能进日志。
 */
@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingsController {

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsController.class);

    private final SettingsService settings;
    private final DeploySecurity security;

    public AdminSettingsController(SettingsService settings, DeploySecurity security) {
        this.settings = settings;
        this.security = security;
    }

    public static boolean isAdminKey(String key) {
        return SettingsService.ADMIN_KEYS.contains(key);
    }

    @GetMapping
    public ResponseEntity<?> get(HttpServletRequest req) {
        try {
            security.check(req);
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
        }
        Map<String, String> all = settings.all();
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : SettingsService.ADMIN_KEYS) {
            if (all.containsKey(k)) {
                out.put(k, all.get(k));
            }
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping
    public ResponseEntity<?> update(@RequestBody Map<String, String> patch, HttpServletRequest req) {
        try {
            security.check(req);
        } catch (SecurityException e) {
            log.warn("运维设置修改被拒绝：来源 {} 原因 {}", req.getRemoteAddr(), e.getMessage());
            return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
        }
        if (patch == null) {
            patch = Map.of();
        }
        for (String k : patch.keySet()) {
            if (!isAdminKey(k)) {
                log.warn("运维设置修改被拒绝：来源 {} 非运维键 {}", req.getRemoteAddr(), k);
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "非运维设置项: " + k));
            }
        }
        Map<String, String> before = settings.all();
        try {
            Map<String, String> after = settings.update(patch);
            audit(req, patch, before);
            return ResponseEntity.ok(after);
        } catch (IllegalArgumentException e) {
            log.warn("运维设置修改失败：来源 {} 原因 {}", req.getRemoteAddr(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 逐项记录实际发生的变化；值未变的不记，避免日志被无意义写入淹没 */
    private void audit(HttpServletRequest req, Map<String, String> patch,
                       Map<String, String> before) {
        String remote = req.getRemoteAddr();
        for (Map.Entry<String, String> e : patch.entrySet()) {
            String old = before.get(e.getKey());
            String now = e.getValue() == null ? "" : e.getValue().trim();
            if (old != null && old.equals(now)) {
                continue;
            }
            log.info("运维设置已修改：来源 {} 键 {} 旧值 [{}] 新值 [{}]",
                    remote, e.getKey(), old, now);
        }
    }
}
