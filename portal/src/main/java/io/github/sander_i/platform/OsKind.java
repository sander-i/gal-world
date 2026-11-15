/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.platform;

import java.util.Locale;

/** 当前运行平台。 */
public enum OsKind {
    LINUX,
    WINDOWS,
    OTHER;

    public static OsKind current() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return WINDOWS;
        }
        if (name.contains("linux")) {
            return LINUX;
        }
        return OTHER;
    }
}
