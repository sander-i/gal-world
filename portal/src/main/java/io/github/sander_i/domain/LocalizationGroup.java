/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 汉化组。
 *
 * 与公司（出品方）平行的独立维度：一个游戏可由多个汉化组翻译，
 * 一个汉化组下有多个游戏 —— 多对多。名称 + 别名 + 网址共同构成身份。
 */
@Entity
@Table(name = "localization_group")
public class LocalizationGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    /** 别名（可选），如「KDays」 */
    @Column(length = 128)
    private String alias;

    /** 汉化组网址；名称 + 别名 + 网址共同构成身份 */
    @Column(length = 500)
    private String website;

    /** 简介（可选）；仅展示，不参与身份判定 */
    @Column(length = 1000)
    private String description;

    /** 维护页排序值，越小越靠前；0 表示未排序 */
    @Column(name = "sort_order")
    private int sortOrder;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }

    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
