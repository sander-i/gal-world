/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

/**
 * 会话后端：Linux 用 systemd user 单元，Windows 用独立进程 + pid 文件。
 *
 * {@link SessionService} 负责按当前操作系统选一个实现，对外 API 不变。
 */
public interface SessionManager {

    SessionService.SessionStatus status(String gameId, int port);

    SessionService.SessionStatus start(String gameId, int port);

    SessionService.SessionStatus stop(String gameId, int port);

    /**
     * 当前会话的启动时间；会话没在运行（或拿不到）时返回 null。
     *
     * 空闲自动停止需要它：刚启动、还没进游玩页的会话没有心跳，
     * 只能拿启动时间当基准，否则会被历史心跳误判成「空闲很久」。
     */
    java.time.Instant startedAt(String gameId);
}
