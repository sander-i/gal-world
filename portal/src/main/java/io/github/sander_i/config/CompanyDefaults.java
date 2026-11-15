/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.config;

import io.github.sander_i.domain.Company;
import io.github.sander_i.repo.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 常见 galgame 出品会社的默认数据。
 *
 * 只补缺失的公司：按 名称 + 别名 + 网址 判断，已存在则跳过，可重复执行。
 * 不覆盖用户已改过的记录。
 *
 * 注意：本机无法联网核对，网址与简介按常见资料录入，若发现失效可直接在
 * 「公司维护」页修改，种子不会覆盖。
 */
@Component
@Order(1)
public class CompanyDefaults implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CompanyDefaults.class);

    /** 默认公司：名称、别名、网址、简介 */
    record Seed(String name, String alias, String website, String description) {
    }

    static final List<Seed> DEFAULTS = List.of(
            new Seed("TYPE-MOON", "型月", "https://www.typemoon.com/",
                    "由武内崇与奈须蘑菇主导的品牌，代表作《月姬》《Fate/stay night》《空之境界》。"),
            new Seed("Key", "Key社", "https://key.visualarts.gr.jp/",
                    "Visual Arts 旗下品牌，以催泪系视觉小说闻名，代表作《Kanon》《AIR》《CLANNAD》。"),
            new Seed("ゆずソフト", "Yuzusoft / 柚子社", "https://www.yuzu-soft.com/",
                    "以明快画风与日常恋爱喜剧著称，代表作《千恋＊万花》《魔女的夜宴》。"),
            new Seed("ALICESOFT", "アリスソフト", "https://www.alicesoft.com/",
                    "老牌日本成人游戏厂商，代表作《兰斯》系列、《斗神都市》。"),
            new Seed("Eushully", "エウシュリー", "https://www.eushully.net/",
                    "以大规模奇幻 RPG 著称，代表作《战女神》系列、《神采炼金名匠》。"),
            new Seed("Nitroplus", "ニトロプラス", "https://www.nitroplus.co.jp/",
                    "以硬派、科幻与猎奇题材闻名，代表作《沙耶之歌》《鬼哭街》。"),
            new Seed("AQUAPLUS", "Leaf", "https://aquaplus.jp/",
                    "日本游戏公司，Leaf 品牌代表作《白色相簿》《传颂之物》。"),
            new Seed("MAGES.", "5pb.", "https://www.mages.co.jp/",
                    "音乐与游戏综合公司，5pb. 品牌代表作《命运石之门》《混沌之子》。"),
            new Seed("sprite", "スプライト", "https://sprite-web.com/",
                    "代表作《恋爱与选举与巧克力》《苍之彼方的四重奏》。"),
            new Seed("SAGA PLANETS", "サガプラネッツ", "https://sagaplanets.com/",
                    "代表作《初雪樱》《金辉恋曲四重奏》。"),
            new Seed("Lump of Sugar", "ランプオブシュガー", "https://www.lumpofsugar.co.jp/",
                    "代表作《游魂 -Kiss on my Deity-》《学园退魔师》。"),
            new Seed("HOOKSOFT", "フックス", "https://www.hooksoft.co.jp/",
                    "代表作《Strawberry Nauts》《放学后的灰姑娘》。"),
            new Seed("ASa Project", "アサプロジェクト", "https://www.asa-pro.com/",
                    "以群像恋爱喜剧见长，代表作《恋爱0公里》《寄宿之恋》。"),
            new Seed("Purple software", "パープルソフトウェア", "https://www.purple-soft.com/",
                    "代表作《克洛的时钟》《幸福噩梦》。"),
            new Seed("Navel", "ネイブル", "https://www.project-navel.com/",
                    "代表作《Shuffle!》《近月少女的礼仪》。"),
            new Seed("minori", "ミノリ", "https://www.minori.ph/",
                    "代表作《ef - a fairy tale of the two.》《eden*》。"),
            new Seed("Frontwing", "フロントウイング", "https://frontwing.jp/",
                    "代表作《ISLAND》《灰色的果实》。"),
            new Seed("PULLTOP", "プルトップ", "https://www.pulltop.com/",
                    "代表作《大图书馆的牧羊人》《在这苍穹展翅》。"),
            new Seed("枕", "Makura", "https://www.makura-soft.com/",
                    "代表作《樱之诗》《美好的每一天》。"),
            new Seed("Cabbage Soft", "きゃべつそふと", "https://cabbage-soft.com/",
                    "代表作《巧克甜恋》《樱之刻》合作方之一。"),
            new Seed("Qruppo", "くるっぽ", "https://qruppo.com/",
                    "以高密度搞笑与独特设定闻名，代表作《拔作岛》。")
    );

    private final CompanyRepository companies;

    public CompanyDefaults(CompanyRepository companies) {
        this.companies = companies;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int inserted = 0;
        for (Seed seed : DEFAULTS) {
            if (companies.findByNameAndAliasAndWebsite(
                    seed.name(), seed.alias(), seed.website()).isPresent()) {
                continue;
            }
            Company c = new Company();
            c.setName(seed.name());
            c.setAlias(seed.alias());
            c.setWebsite(seed.website());
            c.setDescription(seed.description());
            companies.save(c);
            inserted++;
        }
        if (inserted > 0) {
            log.info("已补充 {} 家默认公司数据", inserted);
        }
    }
}
