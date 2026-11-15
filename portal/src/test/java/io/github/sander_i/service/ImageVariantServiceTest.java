/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageVariantServiceTest {

    @Test
    void largerThanHdTargetsThumbAndHd() {
        assertEquals(List.of(640, 1024), ImageVariantService.targetWidths(1920));
        assertEquals(List.of(640, 1024), ImageVariantService.targetWidths(1280));
        assertEquals(List.of(640, 1024), ImageVariantService.targetWidths(1025));
    }

    @Test
    void hdAndSmallerKeepOriginalWidth() {
        assertEquals(List.of(1024), ImageVariantService.targetWidths(1024));
        assertEquals(List.of(800), ImageVariantService.targetWidths(800));
        assertEquals(List.of(640), ImageVariantService.targetWidths(640));
        assertEquals(List.of(320), ImageVariantService.targetWidths(320));
    }
}
