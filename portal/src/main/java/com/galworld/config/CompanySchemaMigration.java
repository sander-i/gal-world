/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 公司身份从「名称」升级为「名称 + 别名 + 网址」。
 *
 * 老库的 company.name 上有唯一约束，必须去掉才能允许同名不同别名/网址。
 * Hibernate 的 ddl-auto=update 不会删除旧约束与旧列，所以启动时手动迁移：
 *  1. 空值归一为空串，避免 null 参与唯一索引比较
 *  2. 删除只包含 name 一列的唯一约束，删除旧的 (name, website) 唯一索引
 *  3. 旧列 name_ja 的值并入 alias，然后删除该列
 *  4. 建立 (name, alias, website) 唯一索引
 *
 * 每一步失败只记日志，不阻止门户启动；业务层仍会做重复校验。
 */
@Component
@Order(0)
public class CompanySchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CompanySchemaMigration.class);

    private final JdbcTemplate jdbc;

    public CompanySchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbc.update("update company set website = '' where website is null");
            jdbc.update("update company set alias = '' where alias is null");
            jdbc.update("update company set description = '' where description is null");
        } catch (RuntimeException e) {
            log.warn("公司字段空值迁移失败: {}", e.toString());
        }

        try {
            List<String> old = jdbc.queryForList("""
                    select tc.constraint_name
                      from information_schema.table_constraints tc
                      join information_schema.key_column_usage k
                        on tc.constraint_catalog = k.constraint_catalog
                       and tc.constraint_schema = k.constraint_schema
                       and tc.constraint_name = k.constraint_name
                     where tc.table_name = 'COMPANY' and tc.constraint_type = 'UNIQUE'
                     group by tc.constraint_name
                    having count(*) = 1 and max(k.column_name) = 'NAME'
                    """, String.class);
            for (String name : old) {
                jdbc.execute("alter table company drop constraint if exists \""
                        + name.replace("\"", "\"\"") + "\"");
                log.info("已删除公司名唯一约束: {}", name);
            }
        } catch (RuntimeException e) {
            log.warn("删除旧公司名唯一约束失败: {}", e.toString());
        }

        try {
            jdbc.execute("drop index if exists uk_company_name_website");
            log.info("已删除旧公司唯一索引 uk_company_name_website");
        } catch (RuntimeException e) {
            log.warn("删除旧公司唯一索引失败: {}", e.toString());
        }

        try {
            // 旧字段 name_ja 语义并入 alias；alias 已有值时保留
            jdbc.update("update company set alias = name_ja "
                    + "where (alias is null or alias = '') and name_ja is not null and name_ja <> ''");
        } catch (RuntimeException e) {
            log.warn("name_ja 迁移到 alias 失败（新库无此列时属正常）: {}", e.toString());
        }

        try {
            jdbc.execute("alter table company drop column if exists name_ja");
            log.info("已删除旧列 name_ja");
        } catch (RuntimeException e) {
            log.warn("删除旧列 name_ja 失败: {}", e.toString());
        }

        try {
            jdbc.execute("create unique index if not exists uk_company_name_alias_website "
                    + "on company(name, alias, website)");
        } catch (RuntimeException e) {
            log.warn("创建公司名+别名+网址唯一索引失败: {}", e.toString());
        }

        // 排序列为新增列：老数据补 0，避免 null 参与排序
        for (String table : List.of("company", "localization_group", "tag",
                "game_company", "game_localization", "game_tag")) {
            try {
                jdbc.update("update " + table + " set sort_order = 0 where sort_order is null");
            } catch (RuntimeException e) {
                log.warn("{} 排序列初始化失败: {}", table, e.toString());
            }
        }
        try {
            jdbc.update("update game set icon_auto_fetched = false where icon_auto_fetched is null");
            jdbc.update("update game set icon_auto_fetch_disabled = false "
                    + "where icon_auto_fetch_disabled is null");
        } catch (RuntimeException e) {
            log.warn("游戏图标标志初始化失败: {}", e.toString());
        }
    }
}
