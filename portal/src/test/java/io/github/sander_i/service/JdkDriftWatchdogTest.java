/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDK 漂移判定。
 *
 * <p>背景：`unattended-upgrades` 在门户运行中替换 `openjdk-21-jre-headless`，
 * 导致 `fork()` 全部失败、`systemctl` 调不到、会话状态永远 `UNKNOWN`
 * （2026-11-20 实测，进度条卡 55%）。这里只测纯判定逻辑：
 * 读不到 mtime 时绝不能误杀进程。
 */
class JdkDriftWatchdogTest {

    @Test
    void sameMtimeIsNotDrift() {
        assertFalse(JdkDriftWatchdog.drifted(1_700_000_000_000L, 1_700_000_000_000L));
    }

    @Test
    void changedMtimeIsDrift() {
        assertTrue(JdkDriftWatchdog.drifted(1_700_000_000_000L, 1_700_000_001_000L));
    }

    @Test
    void unreadableCurrentMtimeIsNotDrift() {
        // 目录被删或权限变化：读不到就跳过本轮，不能退出进程
        assertFalse(JdkDriftWatchdog.drifted(1_700_000_000_000L, -1L));
        assertFalse(JdkDriftWatchdog.drifted(1_700_000_000_000L, 0L));
    }

    @Test
    void unreadableInitialMtimeIsNotDrift() {
        // 启动时就没读到（例如 java.home 为空）：后续任何值都不触发
        assertFalse(JdkDriftWatchdog.drifted(-1L, 1_700_000_000_000L));
        assertFalse(JdkDriftWatchdog.drifted(0L, 1_700_000_000_000L));
    }

    @Test
    void bothUnreadableIsNotDrift() {
        assertFalse(JdkDriftWatchdog.drifted(-1L, -1L));
        assertFalse(JdkDriftWatchdog.drifted(0L, 0L));
    }
}
