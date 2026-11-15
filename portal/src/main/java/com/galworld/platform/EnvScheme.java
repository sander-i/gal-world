/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.platform;

import java.util.List;

/**
 * 一个部署方案。
 *
 * @param status       active / planned / rejected
 * @param requirements 前置条件（操作系统、硬件、端口、权限），按顺序展示
 * @param groups       实际探测到的组件状态
 * @param commands     部署命令（只展示）
 * @param permissions  权限检查
 * @param elevate      提权步骤
 * @param restore      回滚 / 回收权限
 */
public record EnvScheme(
        String key,
        String name,
        String status,
        String tagline,
        String description,
        List<String> pros,
        List<String> cons,
        List<String> requirements,
        List<EnvGroup> groups,
        List<EnvCommand> commands,
        List<EnvPermission> permissions,
        List<EnvCommand> elevate,
        List<EnvCommand> restore) {
}
