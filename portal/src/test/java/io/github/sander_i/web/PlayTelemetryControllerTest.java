/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.service.PlayTelemetryService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class PlayTelemetryControllerTest {

    @Test
    void 采样透传并返回204() {
        PlayTelemetryService service = mock(PlayTelemetryService.class);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.15");
        Map<String, Object> body = Map.of("gameId", "demo", "event", "sample");
        when(service.recordClient(body, "203.0.113.15")).thenReturn(true);

        var res = new PlayTelemetryController(service).report(body, req);

        assertEquals(204, res.getStatusCode().value());
        verify(service).recordClient(body, "203.0.113.15");
    }

    @Test
    void 未知游戏返回400() {
        PlayTelemetryService service = mock(PlayTelemetryService.class);
        MockHttpServletRequest req = new MockHttpServletRequest();
        when(service.recordClient(any(), anyString())).thenReturn(false);

        var res = new PlayTelemetryController(service)
                .report(Map.of("gameId", "nope"), req);

        assertEquals(400, res.getStatusCode().value());
    }
}
