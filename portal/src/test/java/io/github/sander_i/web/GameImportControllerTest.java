/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.platform.OsKind;
import io.github.sander_i.security.WriteSecurity;
import io.github.sander_i.service.GameImportService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GameImportControllerTest {

    private GameImportService service() {
        GameImportService service = mock(GameImportService.class);
        when(service.candidates()).thenReturn(new GameImportService.Candidates(
                "/root/galworld/games", true, List.of(), List.of()));
        when(service.start(anyString(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new GameImportService.StartResult("gi-1", "sample-game", null));
        when(service.snapshot("gi-1")).thenReturn(new GameImportService.RunSnapshot(
                "gi-1", "RUNNING", List.of(), List.of(), "", "sample-game"));
        return service;
    }

    private WriteSecurity security() {
        return mock(WriteSecurity.class);
    }

    @Test
    void candidatesRejectsMissingTokenWhenSecurityEnabled() {
        WriteSecurity security = mock(WriteSecurity.class);
        doThrow(new SecurityException("缺少或错误的写接口令牌")).when(security).check(any());
        GameImportController c = new GameImportController(service(), security, OsKind.LINUX);
        assertThrows(SecurityException.class,
                () -> c.candidates(mock(HttpServletRequest.class)));
    }

    @Test
    void startReturnsRunIdOnLinux() {
        GameImportController c = new GameImportController(service(), security(), OsKind.LINUX);
        var res = c.start(new GameImportController.ImportRequest(
                "sample-game", "标题", "game.exe", null, 1280, 720, "30", null),
                mock(HttpServletRequest.class));
        assertEquals(202, res.getStatusCode().value());
        assertEquals("gi-1", ((Map<?, ?>) res.getBody()).get("runId"));
        assertEquals("sample-game", ((Map<?, ?>) res.getBody()).get("gameId"));
    }

    @Test
    void startOnOtherPlatformsIsRejectedAndMapsTo501() {
        GameImportController c = new GameImportController(service(), security(), OsKind.WINDOWS);
        assertThrows(UnsupportedOperationException.class,
                () -> c.start(new GameImportController.ImportRequest(
                        "sample-game", "标题", "game.exe", null, 1280, 720, "30", null),
                        mock(HttpServletRequest.class)));
        ResponseEntity<?> res = c.unsupported(
                new UnsupportedOperationException("当前平台不支持游戏接入（仅 Linux）"));
        assertEquals(501, res.getStatusCode().value());
    }

    @Test
    void deniedMapsTo403() {
        GameImportController c = new GameImportController(service(), security(), OsKind.LINUX);
        ResponseEntity<?> res = c.denied(new SecurityException("no"));
        assertEquals(403, res.getStatusCode().value());
    }

    @Test
    void unknownRunIdMapsTo404() {
        GameImportController c = new GameImportController(service(), security(), OsKind.LINUX);
        ResponseEntity<?> res = c.badRequest(new IllegalArgumentException("没有这个 run: gi-9"));
        assertEquals(404, res.getStatusCode().value());
    }
}
