/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import io.github.sander_i.platform.OsKind;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 更新器脚本随 jar 分发，测试直接断言打包后的资源。 */
class WindowsUpdaterScriptTest {

    private static final String RESOURCE = "deploy/windows/update-portal.ps1";

    private static byte[] bytes() throws Exception {
        try (InputStream in = WindowsUpdaterScriptTest.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "jar 内缺少 " + RESOURCE + "：检查 portal/pom.xml 的 copy-windows-updater");
            return in.readAllBytes();
        }
    }

    @Test
    void scriptIsUtf8WithBom() throws Exception {
        byte[] b = bytes();
        assertEquals((byte) 0xEF, b[0]);
        assertEquals((byte) 0xBB, b[1]);
        assertEquals((byte) 0xBF, b[2]);
    }

    @Test
    void scriptCoversBackupSmokeAndRollback() throws Exception {
        String s = new String(bytes(), StandardCharsets.UTF_8);
        for (String token : new String[]{
                "precheck", "downloading", "verifying", "backing_up", "stopping",
                "replacing", "starting", "success", "rolled_back", "failed",
                "update-status.json", "gal-world.mv.db", "pre-update-",
                "Get-FileHash", "System.IO.Compression.ZipFile", "Start-ScheduledTask",
                "Local\\GalWorldUpdater", "artifact.name", "UTF8Encoding($false)",
                "GALWORLD_PORTAL_PORT", "停服失败"}) {
            assertTrue(s.contains(token), "脚本缺少 " + token);
        }
    }

    @Test
    void scriptDoesNotDependOnUnixTools() throws Exception {
        String s = new String(bytes(), StandardCharsets.UTF_8);
        assertFalse(s.contains("jq "), "不应依赖 jq");
        assertFalse(s.contains("curl "), "不应依赖 curl");
        assertFalse(s.contains("sha256sum"), "不应依赖 sha256sum");
    }

    @Test
    void startProcessQuotesJarPath() throws Exception {
        String s = new String(bytes(), StandardCharsets.UTF_8);
        // Start-Process 的 -ArgumentList 数组不做引号处理：根目录含空格时 jar 路径会被拆断
        assertTrue(s.contains("'-jar', \"`\"$TargetJar`\"\""),
                "直接启动必须给 jar 路径加引号");
    }

    @Test
    void javaDiscoveryCoversNonSystemDrives() throws Exception {
        String s = new String(bytes(), StandardCharsets.UTF_8);
        // JDK 可能装在非系统盘（本机就在 G:），只扫 Program Files 会找不到
        assertTrue(s.contains("Get-PSDrive -PSProvider FileSystem"),
                "找 JDK 必须扫描所有文件系统盘");
    }

    @Test
    void backupPruningSortsByTimeNotName() throws Exception {
        String s = new String(bytes(), StandardCharsets.UTF_8);
        // 目录名以版本开头，"0.9.2" > "0.10.0"，按名字倒序会把刚建的备份删掉
        assertTrue(s.contains("Sort-Object LastWriteTime -Descending"),
                "备份裁剪必须按时间排序，不能按目录名");
    }

    @Test
    void overrideSkipsVersionGateAndChecksUpdater() throws Exception {
        String s = new String(bytes(), StandardCharsets.UTF_8);
        assertTrue(s.contains("$script:Override = [bool]$rq.override"),
                "必须读取 update-request 的 override");
        assertTrue(s.contains("function Test-JarUpdater"), "必须校验目标 jar 是否带更新器");
        assertTrue(s.contains("BOOT-INF/classes/io/github/sander_i/update/UpdateService.class"));
        assertTrue(s.contains("目标版本不含更新器"));
    }

    @Test
    void manualParameterSetsUnaffected() throws Exception {
        String s = new String(bytes(), StandardCharsets.UTF_8);
        // 手工模式没有 override 概念：版本闸必须只在 -not $script:Override 时生效
        assertTrue(s.contains("-not $script:Override"),
                "手动参数集不能继承覆盖语义");
    }

    @Test
    void scriptParsesWithPowerShell(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(OsKind.current() == OsKind.WINDOWS, "仅 Windows 上跑 PowerShell 语法检查");
        Path ps1 = dir.resolve("update-portal.ps1");
        Files.write(ps1, bytes());
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                "$e=$null; [System.Management.Automation.Language.Parser]::ParseFile('"
                        + ps1.toString().replace("'", "''") + "',[ref]$null,[ref]$e) | Out-Null; $e.Count")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor();
        assertEquals("0", out, "语法错误:\n" + out);
    }
}
