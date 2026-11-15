/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.service.PlayTelemetryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 游玩页遥测：客户端每 10 秒上报一次，崩溃/页面错误时立即上报。
 *
 * 公开接口，只允许已注册游戏；body 字段由 PlayTelemetryService 白名单过滤，
 * 日志文件有大小上限，避免被刷爆磁盘。
 */
@RestController
@RequestMapping("/api/play-telemetry")
public class PlayTelemetryController {

    private final PlayTelemetryService telemetry;

    public PlayTelemetryController(PlayTelemetryService telemetry) {
        this.telemetry = telemetry;
    }

    @PostMapping
    public ResponseEntity<?> report(@RequestBody Map<String, Object> body,
                                    HttpServletRequest req) {
        if (body == null || body.size() > 30) {
            return ResponseEntity.badRequest().build();
        }
        boolean ok = telemetry.recordClient(body, req.getRemoteAddr());
        return ok ? ResponseEntity.noContent().build() : ResponseEntity.badRequest().build();
    }
}
