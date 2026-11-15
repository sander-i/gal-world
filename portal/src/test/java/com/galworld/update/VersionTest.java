/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.update;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VersionTest {
    @Test
    void comparesThreeNumericPartsAndIgnoresPrefixAndSuffix() {
        assertTrue(Version.compare("v0.1.1", "0.1.0-SNAPSHOT") > 0);
        assertEquals(0, Version.compare("v0.1.0", "0.1.0-SNAPSHOT"));
        assertTrue(Version.compare("0.1.0", "v0.1.1") < 0);
        assertTrue(Version.compare("v1.2.3-beta", "1.2.2") > 0);
    }
}
