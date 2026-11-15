/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.web;

import com.galworld.platform.OsKind;
import com.galworld.update.UpdateService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpdateControllerTest {
    private UpdateService service() {
        UpdateService s = mock(UpdateService.class);
        var result = new UpdateService.CheckResult(
                "0.1.0", "v0.1.1", "Gal World v0.1.1", true,
                "发现新版本 v0.1.1", "https://x", "2026-11-07T21:00:00Z");
        when(s.check(false)).thenReturn(result);
        when(s.check(true)).thenReturn(result);
        when(s.apply("v0.1.1", "UPDATE 0.1.1", false)).thenReturn("upd-1");
        return s;
    }

    @Test
    void statusReturnsServiceStatus() {
        UpdateService s = service();
        when(s.status()).thenReturn(Map.of("phase", "idle"));
        assertEquals("idle", new UpdateController(s, OsKind.LINUX).status().get("phase"));
    }

    @Test
    void checkReturnsServiceFields() {
        var res = new UpdateController(service(), OsKind.LINUX).check(false);
        assertEquals(200, res.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertEquals("0.1.0", body.get("currentVersion"));
        assertEquals("v0.1.1", body.get("latestVersion"));
        assertEquals("Gal World v0.1.1", body.get("releaseName"));
        assertEquals(Boolean.TRUE, body.get("hasUpdate"));
        assertEquals("发现新版本 v0.1.1", body.get("message"));
        assertEquals("https://x", body.get("releaseUrl"));
        assertEquals("2026-11-07T21:00:00Z", body.get("publishedAt"));
    }

    @Test
    void applyReturns202() {
        UpdateService s = service();
        var res = new UpdateController(s, OsKind.LINUX)
                .apply(new UpdateController.ApplyRequest("v0.1.1", "UPDATE 0.1.1"));
        assertEquals(202, res.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertEquals("upd-1", body.get("jobId"));
        assertEquals("queued", body.get("phase"));
        verify(s).apply("v0.1.1", "UPDATE 0.1.1", false);
    }

    @Test
    void applyAcceptedOnWindows() {
        UpdateService s = service();
        var res = new UpdateController(s, OsKind.WINDOWS)
                .apply(new UpdateController.ApplyRequest("v0.1.1", "UPDATE 0.1.1"));
        assertEquals(202, res.getStatusCode().value());
        verify(s).apply("v0.1.1", "UPDATE 0.1.1", false);
    }

    @Test
    void checkForwardsRefreshFlag() {
        UpdateService s = service();
        new UpdateController(s, OsKind.LINUX).check(true);
        verify(s).check(true);
    }

    @Test
    void checkExposesOverrideEnabled() {
        UpdateService s = service();
        when(s.overrideEnabled()).thenReturn(true);
        var res = new UpdateController(s, OsKind.LINUX).check(false);
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertEquals(Boolean.TRUE, body.get("overrideEnabled"));
    }

    @Test
    void applyPassesOverrideFlag() {
        UpdateService s = service();
        when(s.apply("v0.1.0", "OVERRIDE v0.1.0", true)).thenReturn("upd-2");
        var res = new UpdateController(s, OsKind.LINUX)
                .apply(new UpdateController.ApplyRequest("v0.1.0", "OVERRIDE v0.1.0", true));
        assertEquals(202, res.getStatusCode().value());
        verify(s).apply("v0.1.0", "OVERRIDE v0.1.0", true);
    }

    @Test
    void applyRejectedOnOtherPlatforms() {
        var c = new UpdateController(service(), OsKind.OTHER);
        assertThrows(UnsupportedOperationException.class,
                () -> c.apply(new UpdateController.ApplyRequest("v0.1.1", "UPDATE 0.1.1")));
        assertEquals(501, c.unsupported(new UnsupportedOperationException("x"))
                .getStatusCode().value());
    }

    @Test
    void argumentsMapTo400() {
        assertEquals(400, new UpdateController(service(), OsKind.LINUX)
                .badRequest(new IllegalArgumentException("bad")).getStatusCode().value());
    }

    @Test
    void deniedMapsTo403() {
        assertEquals(403, new UpdateController(service(), OsKind.LINUX)
                .denied(new SecurityException("x")).getStatusCode().value());
    }

    @Test
    void conflictMapsTo409() {
        assertEquals(409, new UpdateController(service(), OsKind.LINUX)
                .conflict(new IllegalStateException("x")).getStatusCode().value());
    }
}
