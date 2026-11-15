/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.platform;

/** 权限 / 前置条件检查项。 */
public record EnvPermission(String name, boolean ok, String detail) {
}
