/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.web;

import com.galworld.service.CanvasCalibrationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CanvasCalibrationControllerTest {

    @Test
    void 手动校准透传结果() {
        CanvasCalibrationService service = mock(CanvasCalibrationService.class);
        var result = new CanvasCalibrationService.Result(
                "restarted", 992, 624, 968, 593, 1, "ok");
        when(service.calibrate("demo", true)).thenReturn(result);

        ResponseEntity<?> res = new CanvasCalibrationController(service)
                .calibrate("demo", true);

        assertEquals(200, res.getStatusCode().value());
        assertSame(result, res.getBody());
        verify(service).calibrate("demo", true);
    }

    /** 校准失败仍按 200 + body.status 返回，由前端按 status 分支处理 */
    @Test
    void 失败状态按约定返回200() {
        CanvasCalibrationService service = mock(CanvasCalibrationService.class);
        var result = new CanvasCalibrationService.Result(
                "failed", 1280, 720, 968, 593, 1, "重启失败");
        when(service.calibrate("demo", false)).thenReturn(result);

        ResponseEntity<?> res = new CanvasCalibrationController(service)
                .calibrate("demo", false);

        assertEquals(200, res.getStatusCode().value());
        assertEquals("failed",
                ((CanvasCalibrationService.Result) res.getBody()).status());
    }

    @Test
    void 游戏不存在返回404() {
        CanvasCalibrationService service = mock(CanvasCalibrationService.class);
        when(service.calibrate("missing", false)).thenReturn(
                new CanvasCalibrationService.Result("not-found", 0, 0, null, null, 0, "no"));

        ResponseEntity<?> res = new CanvasCalibrationController(service)
                .calibrate("missing", false);

        assertEquals(404, res.getStatusCode().value());
    }
}
