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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 游戏标签，如「纯爱」「NTR」「汉化」「已通关」。 */
@Entity
@Table(name = "tag", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    /** 别名（可选），参与编辑页搜索，不参与唯一约束 */
    @Column(length = 64)
    private String alias;

    /** 十六进制颜色，如 #d8a05c；空则前端用默认色 */
    @Column(length = 16)
    private String color;

    /** 维护页排序值，越小越靠前；0 表示未排序 */
    @Column(name = "sort_order")
    private int sortOrder;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
