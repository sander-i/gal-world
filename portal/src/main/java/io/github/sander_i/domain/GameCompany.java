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

/** 游戏与公司的多对多关联。 */
@Entity
@Table(name = "game_company")
@IdClass(GameCompany.Key.class)
public class GameCompany {

    @Id
    @Column(length = 64)
    private String gameId;

    @Id
    private Long companyId;

    /** 该游戏内的展示顺序；0 表示跟随公司的全局顺序 */
    @Column(name = "sort_order")
    private int sortOrder;

    public GameCompany() {
    }

    public GameCompany(String gameId, Long companyId) {
        this.gameId = gameId;
        this.companyId = companyId;
    }

    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }

    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public static class Key implements Serializable {
        private String gameId;
        private Long companyId;

        public Key() {
        }

        public Key(String gameId, Long companyId) {
            this.gameId = gameId;
            this.companyId = companyId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(gameId, k.gameId) && Objects.equals(companyId, k.companyId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(gameId, companyId);
        }
    }
}
