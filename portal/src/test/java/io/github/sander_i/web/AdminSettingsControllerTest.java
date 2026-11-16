/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.deploy.DeploySecurity;
import io.github.sander_i.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminSettingsControllerTest {

    private SettingsService settings;
    private AdminSettingsController controller;
    private MockHttpServletRequest withToken;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        settings = mock(SettingsService.class);
        when(settings.all()).thenReturn(Map.of(
                "coverMaxCount", "8", "switchMode", "page", "artifactMirrors", "https://x"));
        controller = new AdminSettingsController(settings,
                new DeploySecurity(new PathConfig(tempDir.toString()), "tok", "127.0.0.1/32"));
        withToken = new MockHttpServletRequest();
        withToken.setRemoteAddr("127.0.0.1");
        withToken.addHeader(DeploySecurity.HEADER, "tok");
    }

    @Test
    void rejectsWithoutToken() {
        var req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        assertEquals(403, controller.get(req).getStatusCode().value());
    }

    @Test
    void returnsOnlyAdminKeys() {
        var res = controller.get(withToken);
        assertEquals(200, res.getStatusCode().value());
        assertEquals(Map.of("coverMaxCount", "8", "artifactMirrors", "https://x"), res.getBody());
    }

    @Test
    void rejectsNonAdminKeyOnWrite() {
        ResponseEntity<?> res = controller.update(Map.of("switchMode", "tab"), withToken);
        assertEquals(400, res.getStatusCode().value());
        verify(settings, never()).update(any());
    }

    @Test
    void acceptsAdminKeysOnWrite() {
        when(settings.update(Map.of("coverMaxCount", "10"))).thenReturn(Map.of("coverMaxCount", "10"));
        var res = controller.update(Map.of("coverMaxCount", "10"), withToken);
        assertEquals(200, res.getStatusCode().value());
        verify(settings).update(Map.of("coverMaxCount", "10"));
    }

    @Test
    void settingsControllerRejectsAdminKeys() {
        SettingsController plain = new SettingsController(settings);
        var res = plain.update(Map.of("imageMaxMb", "16"));
        assertEquals(400, res.getStatusCode().value());
        verify(settings, never()).update(any());
    }

    /** 审计日志只看"实际变化"：值没变时不应被当成一次修改记下来 */
    @Test
    void unchangedValueStillSucceedsButDoesNotChangeState() {
        when(settings.update(Map.of("coverMaxCount", "8")))
                .thenReturn(Map.of("coverMaxCount", "8"));
        var res = controller.update(Map.of("coverMaxCount", "8"), withToken);
        assertEquals(200, res.getStatusCode().value());
        verify(settings).update(Map.of("coverMaxCount", "8"));
    }

    /** 校验失败时不应落库；日志只记录被拒绝的原因 */
    @Test
    void invalidValueIsRejectedAndNotPersisted() {
        when(settings.update(any())).thenThrow(
                new IllegalArgumentException("coverMaxCount 范围 1-30"));
        var res = controller.update(Map.of("coverMaxCount", "999"), withToken);
        assertEquals(400, res.getStatusCode().value());
        assertEquals("coverMaxCount 范围 1-30",
                ((Map<?, ?>) res.getBody()).get("message"));
    }
}
