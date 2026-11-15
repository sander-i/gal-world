/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/** 游戏与汉化组的多对多关联。 */
@Entity
@Table(name = "game_localization")
@IdClass(GameLocalization.Key.class)
public class GameLocalization {

    @Id
    @Column(length = 64)
    private String gameId;

    @Id
    private Long groupId;

    /** 该游戏内的展示顺序；0 表示跟随汉化组的全局顺序 */
    @Column(name = "sort_order")
    private int sortOrder;

    public GameLocalization() {
    }

    public GameLocalization(String gameId, Long groupId) {
        this.gameId = gameId;
        this.groupId = groupId;
    }

    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }

    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public static class Key implements Serializable {
        private String gameId;
        private Long groupId;

        public Key() {
        }

        public Key(String gameId, Long groupId) {
            this.gameId = gameId;
            this.groupId = groupId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(gameId, k.gameId) && Objects.equals(groupId, k.groupId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(gameId, groupId);
        }
    }
}
