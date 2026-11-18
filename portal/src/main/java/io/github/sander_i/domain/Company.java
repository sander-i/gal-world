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
 * 开发公司。
 *
 * 与 Tag 的区别：标签是自由分类，公司是**出品方**语义。
 * 一个游戏可由多家公司联合开发，一家公司下有多个游戏 —— 多对多。
 */
@Entity
@Table(name = "company")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    /** 别名（可选），如「まどそふと」 */
    @Column(length = 128)
    private String alias;

    /** 公司网址；名称 + 别名 + 网址共同构成公司身份 */
    @Column(length = 500)
    private String website;

    /** 简介（可选）；仅展示，不参与身份判定 */
    @Column(length = 1000)
    private String description;

    @Column(name = "logo_path", length = 500)
    private String logoPath;

    /** 维护页排序值，越小越靠前；0 表示未排序 */
    @Column(name = "sort_order")
    private int sortOrder;

    /**
     * 多语言覆盖：{"en": {"name": "...", "alias": "...", "description": "..."}}。
     *
     * 主列存默认值，这里存按界面语言的覆盖；缺失的语言回退主列。
     * 用 JSON 列而不是「每语言一列」，加语言时不用改表结构。
     */
    @Column(name = "i18n_text", length = 4000)
    private String i18nText;

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

    public String getLogoPath() { return logoPath; }
    public void setLogoPath(String logoPath) { this.logoPath = logoPath; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getI18nText() { return i18nText; }
    public void setI18nText(String i18nText) { this.i18nText = i18nText; }
}
