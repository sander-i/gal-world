/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import com.galworld.domain.Game;
import com.galworld.repo.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 空闲自动停止。
 *
 * 判定依据是**最后一次心跳时间**：心跳只在游玩页可见时才有，
 * 所以「用户离开游玩页 / 关掉标签」都会让心跳停下，
 * 超过设定分钟数就自动停掉会话，省下 CPU 与编码资源。
 *
 * 关闭方式：设置里 idleStopMinutes = 0。
 */
@Service
public class IdleStopService {

    private static final Logger log = LoggerFactory.getLogger(IdleStopService.class);

    private final SettingsService settings;
    private final GameRepository games;
    private final PlaytimeService playtime;
    private final SessionService sessions;

    public IdleStopService(SettingsService settings, GameRepository games,
                           PlaytimeService playtime, SessionService sessions) {
        this.settings = settings;
        this.games = games;
        this.playtime = playtime;
        this.sessions = sessions;
    }

    /** 每 60 秒扫一次 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sweep() {
        int minutes = settings.idleStopMinutes();
        if (minutes <= 0) {
            return;
        }
        List<Game> all = games.findAll();
        Instant now = Instant.now();
        for (Game g : all) {
            try {
                var st = sessions.status(g.getId(), g.getPort());
                if (st.state() != SessionService.State.RUNNING) {
                    continue;
                }
                // 基准取「本次会话」的最后心跳，而不是该游戏历史上最后一次心跳。
                // 用历史心跳会让刚启动、还没进游玩页的会话立刻被判空闲
                // （实测：9-nine 启动 20 秒就被判「空闲 9651 分钟」停掉）。
                Instant last = playtime.lastBeat(g.getId());
                if (last == null) {
                    // 还没有心跳：以会话启动时间为基准，让新会话有完整的空闲窗口。
                    // 启动时间也拿不到（刚起、systemd 还没记时间戳）就跳过这一轮。
                    last = sessions.startedAt(g.getId());
                    if (last == null) {
                        continue;
                    }
                }
                long idleMinutes = Duration.between(last, now).toMinutes();
                if (idleMinutes >= minutes) {
                    log.info("空闲 {} 分钟，自动停止会话 {}（阈值 {} 分钟）",
                            idleMinutes, g.getId(), minutes);
                    sessions.stop(g.getId(), g.getPort());
                    playtime.closeOpen(g.getId());
                }
            } catch (RuntimeException e) {
                log.warn("空闲检查失败 {}: {}", g.getId(), e.toString());
            }
        }
    }
}
