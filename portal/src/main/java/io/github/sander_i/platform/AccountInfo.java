/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.platform;

import java.util.List;

/**
 * 当前账号的能力快照。两个平台字段不同，统一收敛成 {@code checks} 列表，
 * 前端只按列表渲染，不再假设 Linux 的组 / sudo / docker 字段存在。
 *
 * @param elevated 是否具备管理员能力（Linux 的 sudo / Windows 的 Administrator）
 * @param groups   Linux 的组名；Windows 为令牌组名（可能为空）
 */
public record AccountInfo(String user, boolean elevated, List<String> groups,
                          List<EnvPermission> checks) {
}
