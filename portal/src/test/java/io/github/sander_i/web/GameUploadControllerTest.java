/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.platform.OsKind;
import io.github.sander_i.security.WriteSecurity;
import io.github.sander_i.service.GameUploadService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GameUploadControllerTest {

    private GameUploadService service() {
        GameUploadService s = mock(GameUploadService.class);
        when(s.create(any())).thenReturn(new GameUploadService.CreateResult(
                "up-20260101-000000-abcd", 8388608, "mygame",
                List.of(new GameUploadService.CreatedFile("f000001", "game.zip", 10, 0))));
        when(s.status(anyString())).thenReturn(new GameUploadService.Status(
                "up-20260101-000000-abcd", "ARCHIVE", "UPLOADING", "mygame", "游戏", 10, 4,
                List.of(), null, ""));
        when(s.putChunk(anyString(), anyString(), anyLong(), any(), any()))
                .thenReturn(new GameUploadService.ChunkResult("f000001", 4, false));
        when(s.complete(anyString())).thenReturn(new GameUploadService.CompleteResult(
                "up-20260101-000000-abcd", "ug-1", "COMPLETING", null));
        when(s.cancel(anyString())).thenReturn(new GameUploadService.CancelResult("CANCELLED", "ok"));
        when(s.limits()).thenReturn(new GameUploadService.Limits(1, 2, 3, 4, 5));
        return s;
    }

    private GameUploadController controller(GameUploadService s, OsKind os) {
        return new GameUploadController(s, mock(WriteSecurity.class), os);
    }

    @Test
    void createReturns201OnLinux() {
        GameUploadController c = controller(service(), OsKind.LINUX);
        ResponseEntity<?> res = c.create(new GameUploadService.CreateRequest(
                "ARCHIVE", "游戏", "game.zip", 10L, null), mock(HttpServletRequest.class));
        assertEquals(201, res.getStatusCode().value());
        assertEquals("up-20260101-000000-abcd",
                ((GameUploadService.CreateResult) res.getBody()).uploadId());
    }

    @Test
    void securityIsCheckedOnEveryMethod() {
        WriteSecurity security = mock(WriteSecurity.class);
        doThrow(new SecurityException("缺少或错误的写接口令牌")).when(security).check(any());
        GameUploadController c = new GameUploadController(service(), security, OsKind.LINUX);
        HttpServletRequest req = mock(HttpServletRequest.class);
        assertThrows(SecurityException.class, () -> c.create(
                new GameUploadService.CreateRequest("ARCHIVE", "x", "game.zip", 1L, null), req));
        assertThrows(SecurityException.class, () -> c.status("up-1", req));
        assertThrows(SecurityException.class, () -> c.limits(req));
        assertThrows(SecurityException.class, () -> c.cancel("up-1", req));
        assertThrows(SecurityException.class, () -> c.events("up-1", req));
    }

    @Test
    void mutationsAreRejectedOnWindows() {
        GameUploadController c = controller(service(), OsKind.WINDOWS);
        assertThrows(UnsupportedOperationException.class, () -> c.create(
                new GameUploadService.CreateRequest("ARCHIVE", "x", "game.zip", 1L, null),
                mock(HttpServletRequest.class)));
        assertThrows(UnsupportedOperationException.class, () -> c.chunk(
                "up-1", "f1", 0, "sha", new byte[]{1}, mock(HttpServletRequest.class)));
        assertThrows(UnsupportedOperationException.class, () -> c.complete(
                "up-1", mock(HttpServletRequest.class)));
        assertThrows(UnsupportedOperationException.class, () -> c.cancel(
                "up-1", mock(HttpServletRequest.class)));
    }

    @Test
    void completeReturns202() {
        ResponseEntity<?> res = controller(service(), OsKind.LINUX)
                .complete("up-1", mock(HttpServletRequest.class));
        assertEquals(202, res.getStatusCode().value());
    }

    @Test
    void errorMapping() {
        GameUploadController c = controller(service(), OsKind.LINUX);
        assertEquals(403, c.denied(new SecurityException("no")).getStatusCode().value());
        assertEquals(501, c.unsupported(new UnsupportedOperationException("no")).getStatusCode().value());
        assertEquals(404, c.notFound(new GameUploadService.NotFound("no")).getStatusCode().value());
        assertEquals(415, c.unsupportedArchive(
                new GameUploadService.UnsupportedArchive("no")).getStatusCode().value());
        assertEquals(413, c.tooLarge(new GameUploadService.TooLarge("no")).getStatusCode().value());
        assertEquals(400, c.badRequest(new IllegalArgumentException("no")).getStatusCode().value());
    }

    @Test
    void chunkConflictIncludesActualOffset() {
        GameUploadController c = controller(service(), OsKind.LINUX);
        ResponseEntity<?> res = c.conflict(new GameUploadService.ChunkConflict("偏移不连续", 8396800));
        assertEquals(409, res.getStatusCode().value());
        assertEquals(8396800L, ((Map<?, ?>) res.getBody()).get("actualOffset"));
    }

    @Test
    void limitsAreReturned() {
        var limits = controller(service(), OsKind.LINUX).limits(mock(HttpServletRequest.class));
        assertEquals(4, limits.chunkSize());
    }
}
