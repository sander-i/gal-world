/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.platform.CommandRunner;
import io.github.sander_i.platform.OsKind;
import io.github.sander_i.repo.GameRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 会话管理门面：按当前操作系统选择后端实现，对外 API 保持不变。
 *
 * Linux → {@link SystemdSessionManager}（systemctl --user）
 * Windows → {@link WindowsProcessSessionManager}（子进程 + pid 文件）
 */
@Service
public class SessionService {

    public enum State { RUNNING, STOPPED, FAILED, UNKNOWN, UNSUPPORTED }

    /**
     * @param crashed 上次是我们启动的，但现在不是 active —— 说明游戏自己退出了
     *                （崩溃、游戏关闭、被系统杀掉），可以一键重启
     */
    public record SessionStatus(String gameId, State state, String raw, String streamUrl,
                                boolean crashed) {
    }

    private final SessionManager delegate;

    public SessionService(PathConfig paths, GameRepository games, CommandRunner runner,
                          @Value("${galworld.stream-url:}") String streamUrl) {
        this.delegate = OsKind.current() == OsKind.WINDOWS
                ? new WindowsProcessSessionManager(paths, games, runner, streamUrl)
                : new SystemdSessionManager(paths, runner, streamUrl);
    }

    @PostConstruct
    void logBackend() {
        org.slf4j.LoggerFactory.getLogger(SessionService.class)
                .info("会话后端: {}", delegate.getClass().getSimpleName());
    }

    public String unitName(String gameId) {
        return delegate instanceof SystemdSessionManager s
                ? s.unitName(gameId) : "galworld-game@" + gameId + ".service";
    }

    public SessionStatus status(String gameId, int port) {
        return delegate.status(gameId, port);
    }

    public SessionStatus start(String gameId, int port) {
        return delegate.start(gameId, port);
    }

    public SessionStatus stop(String gameId, int port) {
        return delegate.stop(gameId, port);
    }

    /** 当前会话的启动时间；没在运行返回 null。空闲判定用它做兜底基准。 */
    public java.time.Instant startedAt(String gameId) {
        return delegate.startedAt(gameId);
    }
}
