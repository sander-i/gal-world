/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import io.github.sander_i.platform.OsKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 步骤表里的每个 token 都必须在命令目录里有实现。
 * 这条测试防止「步骤存在但一点就报未实现」的缺口重新出现。
 */
class DeployCommandsCoverageTest {

    private final DeploySteps steps = new DeploySteps();
    private final DeployCommands commands = new DeployCommands();

    @Test
    void everyStepTokenResolvesOnItsPlatform() {
        for (DeployStep step : steps.all()) {
            assertFalse(step.command().isEmpty(), step.id() + " 没有命令 token");
            String token = step.command().get(0);
            OsKind os = step.os() == OsKind.WINDOWS ? OsKind.WINDOWS : OsKind.LINUX;
            assertFalse(commands.resolve(os, token).isEmpty(),
                    step.id() + " / " + token + " 解析为空");
        }
    }
}
