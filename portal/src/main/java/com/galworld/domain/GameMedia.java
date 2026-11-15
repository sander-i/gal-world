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
 * 一个游戏的一个媒体文件（封面 / 宣传 CG）。
 *
 * 同一 kind 下的多条记录构成轮换列表，按 sortOrder 升序播放。
 * 文件本体在 games/<id>.meta/media/ 下，这里只存相对 URL。
 */
@Entity
@Table(name = "game_media", indexes = @Index(name = "idx_media_game",
        columnList = "gameId,kind,sortOrder"))
public class GameMedia {

    /** 封面图（多张轮换） */
    public static final String KIND_COVER = "cover";
    /** 宣传 CG（图片或视频，多份轮换） */
    public static final String KIND_CG = "cg";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String gameId;

    @Column(nullable = false, length = 16)
    private String kind;

    /** 相对 URL，含内容版本参数 */
    @Column(nullable = false, length = 500)
    private String path;

    /** 轮换顺序，从 0 开始；0 为置顶项 */
    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
