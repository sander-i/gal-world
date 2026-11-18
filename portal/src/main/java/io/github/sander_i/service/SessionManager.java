/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

/**
 * 会话后端：Linux 用 systemd user 单元，Windows 用独立进程 + pid 文件。
 *
 * {@link SessionService} 负责按当前操作系统选一个实现，对外 API 不变。
 */
public interface SessionManager {

    SessionService.SessionStatus status(String gameId, int port);

    /**
     * 批量状态：列表页一次取全部，避免每游戏 fork 一次查询进程。
     *
     * 默认实现逐条查询；Linux 后端会覆盖成一次 systemctl 调用。
     */
    default java.util.Map<String, SessionService.SessionStatus> statuses(
            java.util.List<io.github.sander_i.domain.Game> games) {
        java.util.Map<String, SessionService.SessionStatus> out = new java.util.HashMap<>();
        for (io.github.sander_i.domain.Game g : games) {
            out.put(g.getId(), status(g.getId(), g.getPort()));
        }
        return out;
    }

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
