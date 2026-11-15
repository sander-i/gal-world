/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.config;

import com.galworld.domain.LocalizationGroup;
import com.galworld.repo.LocalizationGroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 常见汉化组的默认数据。
 *
 * 只补缺失的汉化组：按 名称 + 别名 + 网址 判断，已存在则跳过，可重复执行。
 * 不覆盖用户已改过的记录。
 *
 * 注意：本机无法联网核对，网址留空、简介为通用描述；可在「汉化组维护」页补充，
 * 种子不会覆盖。
 */
@Component
@Order(1)
public class LocalizationGroupDefaults implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalizationGroupDefaults.class);

    /** 默认汉化组：名称、别名、网址、简介 */
    record Seed(String name, String alias, String website, String description) {
    }

    static final List<Seed> DEFAULTS = List.of(
            new Seed("澄空学园", "cksk", "",
                    "国内较早的 galgame 汉化与交流组织，参与多部 Key 系作品汉化。"),
            new Seed("绯月", "KDays", "",
                    "以论坛为依托的汉化组织，长期翻译 galgame 与相关作品。"),
            new Seed("萌你妹汉化组", "", "",
                    "活跃于 2010 年代的 galgame 汉化组。"),
            new Seed("汉风汉化组", "", "",
                    "老牌 galgame 汉化组之一。"),
            new Seed("樱之空汉化组", "", "",
                    "galgame 汉化组。"),
            new Seed("星月汉化组", "", "",
                    "galgame 汉化组。")
    );

    private final LocalizationGroupRepository groups;

    public LocalizationGroupDefaults(LocalizationGroupRepository groups) {
        this.groups = groups;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int inserted = 0;
        for (Seed seed : DEFAULTS) {
            if (groups.findByNameAndAliasAndWebsite(
                    seed.name(), seed.alias(), seed.website()).isPresent()) {
                continue;
            }
            LocalizationGroup g = new LocalizationGroup();
            g.setName(seed.name());
            g.setAlias(seed.alias());
            g.setWebsite(seed.website());
            g.setDescription(seed.description());
            groups.save(g);
            inserted++;
        }
        if (inserted > 0) {
            log.info("已补充 {} 家默认汉化组数据", inserted);
        }
    }
}
