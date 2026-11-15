/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.platform.EnvCommand;
import io.github.sander_i.platform.EnvGroup;
import io.github.sander_i.platform.EnvItem;
import io.github.sander_i.platform.EnvPermission;
import io.github.sander_i.platform.EnvScheme;
import io.github.sander_i.platform.OsKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentControllerTest {

    @Test
    void parseOsAcceptsCaseInsensitivePlatforms() {
        assertEquals(OsKind.LINUX, EnvironmentController.parseOs("linux"));
        assertEquals(OsKind.LINUX, EnvironmentController.parseOs(" LINUX "));
        assertEquals(OsKind.WINDOWS, EnvironmentController.parseOs("Windows"));
        assertNull(EnvironmentController.parseOs(null));
        assertNull(EnvironmentController.parseOs(""));
        assertNull(EnvironmentController.parseOs("darwin"));
    }

    @Test
    void previewKeepsStaticContentAndDropsLiveState() {
        EnvScheme live = new EnvScheme("k", "方案名", "active", "一句话", "说明",
                List.of("优点"), List.of("缺点"), List.of("前置"),
                List.of(new EnvGroup("串流", List.of(
                        new EnvItem("Selkies", "已安装", "active", "ok")))),
                List.of(new EnvCommand("安装", "echo hi", "参考")),
                List.of(new EnvPermission("sudo", true, "免密")),
                List.of(new EnvCommand("提权", "sudo -v", "临时")),
                List.of(new EnvCommand("回收", "sudo -k", "结束")));

        EnvScheme preview = EnvironmentController.withoutLiveState(live);

        assertEquals(live.key(), preview.key());
        assertEquals(live.name(), preview.name());
        assertEquals(live.requirements(), preview.requirements());
        assertEquals(live.commands(), preview.commands());
        assertEquals(live.elevate(), preview.elevate());
        assertEquals(live.restore(), preview.restore());
        assertTrue(preview.groups().isEmpty(), "预览不得带本机探测分组");
        assertTrue(preview.permissions().isEmpty(), "预览不得带本机权限检查");
    }
}
