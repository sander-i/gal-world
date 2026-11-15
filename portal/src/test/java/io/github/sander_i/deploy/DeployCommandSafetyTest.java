/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import io.github.sander_i.platform.OsKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令表的安全约束。对应 2026-11-09 真机事故：
 *
 * 1. ADMIN 步骤的命令行自带 {@code sudo -n}，而 {@link DeployService#runLinux} 又包了一层，
 *    变成 {@code sudo -n sh -c "sudo -n usermod ..."}；内层 shell 里 {@code $USER} 是 root，
 *    于是把 root 加进 audio 组、给 root 开了 linger，而两个步骤都返回 exit=0 报 SUCCESS。
 * 2. {@code pip install --no-index} 装不了 selkies 的依赖，卸载后无法把串流装回来。
 */
class DeployCommandSafetyTest {

    private final DeploySteps steps = new DeploySteps();
    private final DeployCommands commands = new DeployCommands();

    private Map<String, String> varsFor(DeployStep step) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("root", "/home/tester/galworld");
        v.put("dataDir", "/home/tester/galworld/data");
        v.put("portalPort", "8088");
        v.put("user", "tester");
        if (step.artifactId() != null && !step.artifactId().isBlank()) {
            v.put("artifact:" + step.artifactId(), "/tmp/artifact");
        }
        return v;
    }

    @Test
    void linuxCommandsNeverUseShellUserVariable() {
        for (DeployStep step : steps.forOs(OsKind.LINUX)) {
            for (String raw : commands.resolve(OsKind.LINUX, step.command().get(0))) {
                assertFalse(raw.contains("$USER"),
                        step.id() + " 不能用 $USER（sudo 下会变成 root），"
                                + "请改用 ${user} 占位符: " + raw);
            }
        }
    }

    @Test
    void linuxAdminStepsLeaveElevationToTheRunner() {
        for (DeployStep step : steps.forOs(OsKind.LINUX)) {
            if (step.runAs() != DeployStep.RunAs.ADMIN) {
                continue;
            }
            Map<String, String> vars = varsFor(step);
            for (String raw : commands.resolve(OsKind.LINUX, step.command().get(0))) {
                String line = DeployService.render(raw, vars).trim();
                assertFalse(line.startsWith("sudo"),
                        step.id() + " 的提权由 runLinux 统一加，命令行不得自带 sudo: " + line);
            }
        }
    }

    @Test
    void selkiesInstallFallsBackToIndexWhenDependenciesAreMissing() {
        List<String> lines = commands.resolve(OsKind.LINUX, "install-selkies");
        String joined = String.join("\n", lines);
        assertTrue(joined.contains("--no-index"), "应优先走离线安装");
        assertTrue(joined.contains("||"), "离线装不上依赖时必须回退到联网安装");
    }

    /**
     * 探测读 /etc/group 而不是进程快照：装完 usermod 后进程组没变，
     * 用 `id -nG` 会永远显示未加入（2026-11-09 真机实测）。
     */
    @Test
    void audioGroupProbeReadsTheGroupFile() {
        assertEquals(Boolean.TRUE,
                DeployProbes.audioGroupContains(List.of("audio:x:29:pulse,claude", "video:x:44:"), "claude"));
        assertEquals(Boolean.FALSE,
                DeployProbes.audioGroupContains(List.of("audio:x:29:pulse,root"), "claude"));
        assertNull(DeployProbes.audioGroupContains(List.of("video:x:44:"), "claude"),
                "没有 audio 行时必须是未知，不能猜");
    }

    /**
     * 部署的 wine 是 32 位二进制，缺 i386 运行库在全新机器上直接 "cannot execute"；
     * 文泉驿字体是 wine-fonts.sh 改写 Tahoma 的底字体。2026-11-09 真机实测。
     */
    @Test
    void basePackagesCover32BitWineRuntime() {
        String joined = String.join("\n", commands.resolve(OsKind.LINUX, "apt-packages"));
        assertTrue(joined.contains("--add-architecture i386"), "必须先启用 i386 架构");
        assertTrue(joined.contains("libc6:i386"), "缺少 32 位 C 运行库");
        assertTrue(joined.contains("fonts-wqy-microhei"), "缺少 wine 中文字体底字");
    }

    @Test
    void venvInstallsFontToolsForWineFontRewriting() {
        String joined = String.join("\n", commands.resolve(OsKind.LINUX, "create-venv"));
        assertTrue(joined.contains("fonttools"), "wine-fonts.sh 依赖 fontTools 改写字体");
    }

    /**
     * 免密提权入口：每个 Linux ADMIN 步骤都必须映射到 galworld-sudo 的固定子命令，
     * 否则会退回 `sudo -n sh -c` 路径——那条路要么要页面输密码，要么就得放行 sh（等于 root）。
     * 2026-11-09 按 A+D 方案落地。
     */
    @Test
    void everyLinuxAdminStepHasFixedElevationEntry() {
        for (DeployStep step : steps.forOs(OsKind.LINUX)) {
            if (step.runAs() != DeployStep.RunAs.ADMIN) {
                continue;
            }
            String token = step.command().get(0);
            assertNotNull(commands.sudoSubcommand(token),
                    step.id() + " 是 ADMIN 步骤但没有固定提权子命令");
        }
    }

    /**
     * deploy.sh 里的包装脚本与 sudoers 白名单必须覆盖命令表映射的全部子命令。
     * 两边分处 Java 与 shell，靠这条测试防止漂移（漂移的表现是「页面点了报需要密码」）。
     */
    @Test
    void deployScriptWrapperCoversEveryMappedSubcommand() throws Exception {
        java.nio.file.Path script = java.nio.file.Path.of("..", "deploy.sh");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(script),
                "不在仓库内运行（缺少 ../deploy.sh），跳过脚本一致性检查");
        String text = java.nio.file.Files.readString(script);
        String sudoersLine = null;
        for (String line : text.split("\n")) {
            if (line.trim().startsWith("for sub in ")) {
                sudoersLine = line.trim();
                break;
            }
        }
        assertNotNull(sudoersLine, "deploy.sh 里找不到 sudoers 子命令循环");
        for (DeployStep step : steps.forOs(OsKind.LINUX)) {
            if (step.runAs() != DeployStep.RunAs.ADMIN) {
                continue;
            }
            String sub = commands.sudoSubcommand(step.command().get(0));
            assertTrue(text.contains("  " + sub + ")"),
                    "galworld-sudo 缺子命令分支: " + sub);
            assertTrue(sudoersLine.contains(" " + sub),
                    "sudoers 白名单缺子命令: " + sub + "（" + sudoersLine + "）");
        }
    }

    /**
     * 包装脚本里的 packages 分支必须覆盖命令表里的全部 apt 包。
     * 两处各写一遍是现状（deploy.sh 无法读 Java 常量），漏一个的表现是「页面一键装完仍缺依赖」。
     */
    @Test
    void wrapperPackagesCoverCommandTable() throws Exception {
        java.nio.file.Path script = java.nio.file.Path.of("..", "deploy.sh");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(script),
                "不在仓库内运行（缺少 ../deploy.sh），跳过脚本一致性检查");
        String text = java.nio.file.Files.readString(script);
        int start = text.indexOf("  packages)");
        int end = text.indexOf(";;", start);
        assertTrue(start > 0 && end > start, "deploy.sh 里找不到 packages 分支");
        String segment = text.substring(start, end);
        for (String line : commands.resolve(OsKind.LINUX, "apt-packages")) {
            if (!line.startsWith("apt-get install")) {
                continue;
            }
            for (String pkg : line.split("\\s+")) {
                if (pkg.startsWith("apt-get") || pkg.equals("install") || pkg.equals("-y")
                        || pkg.equals("-q") || pkg.isEmpty()) {
                    continue;
                }
                assertTrue(segment.contains(pkg),
                        "galworld-sudo packages 缺包: " + pkg);
            }
        }
    }
}
