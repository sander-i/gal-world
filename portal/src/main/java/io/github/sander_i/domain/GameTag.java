/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/** 游戏与标签的多对多关联。 */
@Entity
@Table(name = "game_tag")
@IdClass(GameTag.Key.class)
public class GameTag {

    @Id
    @Column(length = 64)
    private String gameId;

    @Id
    private Long tagId;

    /** 该游戏内的展示顺序；0 表示跟随标签的全局顺序 */
    @Column(name = "sort_order")
    private int sortOrder;

    public GameTag() {
    }

    public GameTag(String gameId, Long tagId) {
        this.gameId = gameId;
        this.tagId = tagId;
    }

    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }

    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public static class Key implements Serializable {
        private String gameId;
        private Long tagId;

        public Key() {
        }

        public Key(String gameId, Long tagId) {
            this.gameId = gameId;
            this.tagId = tagId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(gameId, k.gameId) && Objects.equals(tagId, k.tagId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(gameId, tagId);
        }
    }
}
