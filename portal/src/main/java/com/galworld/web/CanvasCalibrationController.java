/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.web;

import com.galworld.service.CanvasCalibrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 游玩页画布校准：manual=true 时忽略全局「自动校准」开关。
 *
 * 返回约定：只有游戏不存在返回 404；其余结果（ok/restarted/failed/not-running/
 * no-window/disabled）统一 200 + body.status，客户端按 status 分支处理。
 */
@RestController
@RequestMapping("/api/games")
public class CanvasCalibrationController {

    private final CanvasCalibrationService calibration;

    public CanvasCalibrationController(CanvasCalibrationService calibration) {
        this.calibration = calibration;
    }

    @PostMapping("/{id}/canvas/calibrate")
    public ResponseEntity<CanvasCalibrationService.Result> calibrate(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean manual) {
        var result = calibration.calibrate(id, manual);
        if ("not-found".equals(result.status())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }
}
