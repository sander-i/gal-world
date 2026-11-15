/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.deploy;

import com.galworld.platform.OsKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployUninstallTest {

    private static final Map<String, String> VARS = Map.of(
            "root", "/home/tester/galworld",
            "dataDir", "/home/tester/galworld/data",
            "portalPort", "8088",
            "user", "tester");

    private final DeploySteps steps = new DeploySteps();
    private final DeployCommands commands = new DeployCommands();

    @Test
    void uninstallStepsAreDestructiveAndOrdered() {
        assertUninstall("lin.uninstall", OsKind.LINUX, List.of());
        assertUninstall("lin.uninstall-admin", OsKind.LINUX, List.of("lin.uninstall"));
        assertUninstall("lin.uninstall-packages", OsKind.LINUX, List.of("lin.uninstall-admin"));
        assertUninstall("win.uninstall", OsKind.WINDOWS, List.of());
        assertUninstall("win.uninstall-admin", OsKind.WINDOWS, List.of("win.uninstall"));
        assertUninstall("win.uninstall-packages", OsKind.WINDOWS, List.of("win.uninstall-admin"));

        assertTrue(indexOf(OsKind.LINUX, "lin.uninstall") < indexOf(OsKind.LINUX, "lin.uninstall-admin"));
        assertTrue(indexOf(OsKind.LINUX, "lin.uninstall-admin")
                < indexOf(OsKind.LINUX, "lin.uninstall-packages"));
        assertTrue(indexOf(OsKind.WINDOWS, "win.uninstall") < indexOf(OsKind.WINDOWS, "win.uninstall-admin"));
        assertTrue(indexOf(OsKind.WINDOWS, "win.uninstall-admin")
                < indexOf(OsKind.WINDOWS, "win.uninstall-packages"));
    }

    private int indexOf(OsKind os, String id) {
        List<DeployStep> list = steps.forOs(os);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void assertUninstall(String id, OsKind os, List<String> requires) {
        DeployStep step = steps.find(id);
        assertNotNull(step, "缺少步骤 " + id);
        assertEquals(os, step.os(), id + " 平台不符");
        assertEquals(requires, step.requires(), id + " 前置依赖不符");
        assertTrue(step.destructive(), id + " 必须是破坏性步骤");
        assertTrue(DeploySteps.isUninstall(step), id + " 未被识别为卸载步骤");
        assertFalse(step.command().isEmpty(), id + " 没有命令 token");

        List<String> lines = commands.resolve(os, step.command().get(0));
        assertFalse(lines.isEmpty(), id + " 命令为空");
        for (String line : lines) {
            String rendered = DeployService.render(line, VARS);
            assertFalse(rendered.contains("${"), id + " 残留占位符: " + rendered);
        }
    }

    @Test
    void uninstallNeverTouchesPortalGamesOrUserData() {
        for (OsKind os : List.of(OsKind.LINUX, OsKind.WINDOWS)) {
            for (DeployStep step : steps.forOs(os)) {
                if (!DeploySteps.isUninstall(step)) {
                    continue;
                }
                String id = step.id();
                for (String line : commands.resolve(os, step.command().get(0))) {
                    assertFalse(line.contains("${root}/games") || line.contains("${root}\\games"),
                            id + " 不得删除游戏目录: " + line);
                    // 运行时目录（元件缓存、虚拟声卡安装包）允许清；其它 data 子目录
                    // （数据库、证书、日志、存档）一律不许动
                    String withoutCache = line
                            .replace("${root}/data/artifacts", "")
                            .replace("${root}\\data\\artifacts", "")
                            .replace("${root}/data/vbcable", "")
                            .replace("${root}\\data\\vbcable", "");
                    assertFalse(withoutCache.contains("${root}/data")
                                    || withoutCache.contains("${root}\\data"),
                            id + " 不得删除数据目录: " + line);
                    assertFalse(line.contains("galworld-portal.service")
                                    && (line.contains("rm ") || line.contains("stop ")),
                            id + " 不得停止或删除门户单元: " + line);
                    assertFalse(line.contains("gal-world-portal.jar"),
                            id + " 不得删除门户 jar: " + line);

                    // 系统包只允许「卸载系统依赖」这一步动，且绝不能把 JDK 清掉——门户还要靠它跑
                    boolean touchesPackages = line.matches("(?i).*apt(-get)?\\s+(remove|purge|autoremove).*");
                    if (touchesPackages) {
                        assertEquals("lin.uninstall-packages", id,
                                id + " 不得卸载系统包: " + line);
                        assertFalse(line.contains("openjdk") || line.contains("jre"),
                                id + " 不得卸载 JDK: " + line);
                    }
                }
            }
        }
    }

    @Test
    void systemCommandsTolerateMissingComponents() {
        for (DeployStep step : steps.all()) {
            if (!DeploySteps.isUninstall(step)) {
                continue;
            }
            for (String line : commands.resolve(step.os(), step.command().get(0))) {
                if (line.contains("systemctl") || line.contains("modprobe") || line.contains("gpasswd")) {
                    assertTrue(line.contains("|| true"),
                            step.id() + " 系统命令缺少失败容忍: " + line);
                }
                if (line.contains("pkill")) {
                    assertTrue(line.contains("'[r]un-game.sh'"),
                            step.id() + " pkill 会匹配到自身: " + line);
                }
            }
        }
    }

    @Test
    void windowsNamesMatchInstallSteps() {
        String install = String.join("\n", commands.resolve(OsKind.WINDOWS, "register-autostart"))
                + String.join("\n", commands.resolve(OsKind.WINDOWS, "add-firewall"));
        String uninstallSys = String.join("\n", commands.resolve(OsKind.WINDOWS, "uninstall-windows-sys"));

        assertTrue(install.contains("GalWorldMoonlightWeb"));
        assertTrue(uninstallSys.contains("GalWorldMoonlightWeb"), "计划任务名必须与安装一致");
        for (String rule : List.of("'Sunshine TCP'", "'Sunshine UDP'")) {
            assertTrue(install.contains(rule), "安装步骤缺少规则 " + rule);
            assertTrue(uninstallSys.contains(rule), "卸载步骤缺少规则 " + rule);
        }
    }

    @Test
    void windowsUninstallValidatesProcessBeforeKilling() {
        String user = String.join("\n", commands.resolve(OsKind.WINDOWS, "uninstall-windows"));
        String admin = String.join("\n", commands.resolve(OsKind.WINDOWS, "uninstall-windows-sys"));

        assertFalse(user.contains("taskkill"), "不得用 taskkill 盲杀 PID");
        assertTrue(user.contains("Stop-Process"), "必须用 Stop-Process 并按存活判断");
        assertTrue(user.contains("^exe="), "必须读取会话记录的 exe 做校验");
        assertTrue(user.contains("throw"), "目录删不掉时必须让步骤失败");
        assertFalse(user.contains("Unregister-ScheduledTask"), "计划任务需要管理员，应放在 admin 步骤");
        assertFalse(user.contains("ProgramFiles"), "USER 步骤不得写 Program Files");
        assertTrue(admin.contains("Unregister-ScheduledTask"));
        assertTrue(admin.contains("msiexec"));
        assertTrue(admin.contains("-PassThru"), "msiexec 必须检查退出码");
    }

    @Test
    void linuxUninstallClearsPulseConfigAndCrashMarkers() {
        String user = String.join("\n", commands.resolve(OsKind.LINUX, "uninstall-linux"));
        assertTrue(user.contains(".config/pulse/default.pa"), "必须清理 pulse 默认配置");
        assertTrue(user.contains("session-intent"), "必须清理会话意图标记");
        assertTrue(user.contains(".started"), "必须清掉 started 标记，避免误判崩溃");
    }

    /**
     * 门户本身是 --user 单元：关掉 linger 会让它在最后一个登录会话结束时被 systemd 连带停掉，
     * 「卸载保留门户」的承诺就落空了（2026-11-09 真机实测进入重启循环）。
     */
    @Test
    void uninstallKeepsLingerForThePortalUnit() {
        for (DeployStep step : steps.all()) {
            if (!DeploySteps.isUninstall(step)) {
                continue;
            }
            for (String line : commands.resolve(step.os(), step.command().get(0))) {
                assertFalse(line.contains("disable-linger"),
                        step.id() + " 不得关闭 linger: " + line);
            }
        }
    }
}
