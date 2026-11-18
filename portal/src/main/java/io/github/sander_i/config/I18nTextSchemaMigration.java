/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 公司 / 汉化组 / 标签的多语言覆盖列。
 *
 * Hibernate 的 ddl-auto=update 会加列，但不会给老数据补值；
 * 这里把新列初始化成空串，避免 null 参与 JSON 解析与比较。
 * 每一步失败只记日志，不阻止门户启动——空串与 null 在解析层等价。
 */
@Component
@Order(1)
public class I18nTextSchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(I18nTextSchemaMigration.class);

    private final JdbcTemplate jdbc;

    public I18nTextSchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String table : List.of("company", "localization_group", "tag")) {
            try {
                jdbc.update("update " + table + " set i18n_text = '' where i18n_text is null");
            } catch (RuntimeException e) {
                // 列还不存在时 ddl-auto 会先建表；这里失败属正常，下次启动就补上
                log.debug("{} 多语言列初始化跳过: {}", table, e.toString());
            }
        }
    }
}
