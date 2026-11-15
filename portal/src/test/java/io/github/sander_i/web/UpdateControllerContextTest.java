/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.update.UpdateService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * 回归测试：{@link UpdateController} 声明了两个构造器，必须给公开构造器加 {@code @Autowired}，
 * 否则 Spring 6.1 找不到默认构造器，应用上下文启动时 bean 创建失败。
 */
class UpdateControllerContextTest {

    @Test
    void springCanCreateControllerWithMultipleConstructors() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(UpdateService.class, () -> mock(UpdateService.class));
            ctx.register(UpdateController.class);
            ctx.refresh();
            assertNotNull(ctx.getBean(UpdateController.class));
        }
    }
}
