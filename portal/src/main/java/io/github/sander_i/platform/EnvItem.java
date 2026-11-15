/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.platform;

import java.util.List;

/**
 * 环境信息里的一个组件条目。
 *
 * @param status 部署状态：active / installed / planned / rejected
 * @param health 健康状态：ok / warn / error / unknown
 */
public record EnvItem(String name, String detail, String status, String health, List<String> details) {

    public EnvItem(String name, String detail, String status) {
        this(name, detail, status, null, List.of());
    }

    public EnvItem(String name, String detail, String status, String health) {
        this(name, detail, status, health, List.of());
    }
}
