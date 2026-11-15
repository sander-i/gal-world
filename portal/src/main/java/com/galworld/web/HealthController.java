/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.web;

import com.galworld.deploy.DeploySecurity;
import com.galworld.security.WriteSecurity;
import com.galworld.service.SettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final String version;
    private final SettingsService settings;
    private final DeploySecurity deploySecurity;
    private final WriteSecurity writeSecurity;

    public HealthController(@Value("${galworld.version:dev}") String version,
                            SettingsService settings,
                            DeploySecurity deploySecurity,
                            WriteSecurity writeSecurity) {
        this.version = version;
        this.settings = settings;
        this.deploySecurity = deploySecurity;
        this.writeSecurity = writeSecurity;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "UP");
        out.put("app", "gal-world-portal");
        out.put("version", version);
        // 仓库地址可在「后台管理」里改；是否公开也由后台开关控制，
        // 关掉时不下发该字段，避免未认证访问者看到内网地址
        if (settings.repoUrlPublic()) {
            out.put("repoUrl", settings.repoUrl());
        }
        // 只暴露布尔值，不下发令牌本身：页面据此决定提示「向部署者索取」还是「去日志里找」
        out.put("deployTokenConfigured", deploySecurity.configured());
        out.put("writeTokenConfigured", writeSecurity.enabled());
        return out;
    }
}
