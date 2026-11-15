/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeployRunEventTest {

    @Test
    void eventIsPushedToListenersWithItsName() throws Exception {
        DeployRun run = new DeployRun("r1", "game-import");
        SseEmitter emitter = mock(SseEmitter.class);
        run.addListener(emitter);

        run.event("step", "{\"id\":\"wineboot\",\"state\":\"RUNNING\"}");

        verify(emitter, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void logStillAppendsToLines() {
        DeployRun run = new DeployRun("r2", "game-import");
        run.log("hello");
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("hello"), run.lines());
    }
}
