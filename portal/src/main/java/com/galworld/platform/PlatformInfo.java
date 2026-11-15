/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.platform;

/**
 * 平台基本信息，展示在设置页「环境信息」顶部。
 *
 * @param name    系统名，如 {@code Windows} / {@code Linux}
 * @param version 版本描述，如 {@code 11 (26100)} / {@code Ubuntu 24.04.4 LTS}
 * @param arch    架构，如 {@code amd64}
 * @param host    主机名
 */
public record PlatformInfo(OsKind kind, String name, String version, String arch, String host) {
}
