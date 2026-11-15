/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.platform;

import java.util.List;

/** 环境信息里的分组（串流 / 显示 / 音频 …）。 */
public record EnvGroup(String name, String health, String summary, List<EnvItem> items) {

    public EnvGroup(String name, List<EnvItem> items) {
        this(name, null, null, items);
    }
}
