/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 一次游玩记录。
 *
 * 由游玩页的心跳驱动：首个心跳开一条，后续心跳刷新 lastBeatAt，
 * 超过 {@code IDLE_GAP} 没有心跳就视为用户离开了。会话停止时兜底关闭，
 * 避免页面被直接关掉后这条记录一直挂着。
 */
@Entity
@Table(name = "play_session", indexes = @Index(name = "idx_play_game", columnList = "gameId"))
public class PlaySession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String gameId;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant lastBeatAt;

    /** null 表示仍在进行中 */
    private Instant endedAt;

    /** 已结算时长（秒）；进行中的记录用 now-startedAt 现算 */
    private long seconds;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getLastBeatAt() { return lastBeatAt; }
    public void setLastBeatAt(Instant lastBeatAt) { this.lastBeatAt = lastBeatAt; }

    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }

    public long getSeconds() { return seconds; }
    public void setSeconds(long seconds) { this.seconds = seconds; }
}
