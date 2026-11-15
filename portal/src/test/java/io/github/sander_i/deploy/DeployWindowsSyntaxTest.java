/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import io.github.sander_i.platform.OsKind;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Windows 命令不做真实执行（会改注册表/装驱动/建证书），
 * 但用 PowerShell 自己的语法分析器验证每个 .ps1 能被解析。
 */
class DeployWindowsSyntaxTest {

    @TempDir
    Path dir;

    @Test
    void allWindowsScriptsParse() throws Exception {
        Assumptions.assumeTrue(OsKind.current() == OsKind.WINDOWS, "仅 Windows 上跑 PowerShell 语法检查");
        DeployCommands commands = new DeployCommands();
        DeploySteps steps = new DeploySteps();

        List<String> tokens = new ArrayList<>();
        for (DeployStep step : steps.all()) {
            if (step.os() == OsKind.WINDOWS && !step.command().isEmpty()) {
                tokens.add(step.command().get(0));
            }
        }
        for (String extra : List.of("gen-cert", "configure-sunshine", "pair",
                "register-autostart", "verify-windows")) {
            if (!tokens.contains(extra)) {
                tokens.add(extra);
            }
        }

        for (String token : tokens) {
            StringBuilder body = new StringBuilder("\uFEFF$ErrorActionPreference = 'Stop'\n");
            for (String line : commands.resolve(OsKind.WINDOWS, token)) {
                body.append(DeployService.render(line, java.util.Map.of(
                        "root", dir.toString(),
                        "dataDir", dir.resolve("data").toString(),
                        "user", "tester",
                        "portalPort", "8088",
                        "artifact:windows.sunshine", "C:\\x\\sunshine.msi",
                        "artifact:windows.vbcable", "C:\\x\\vbcable.zip",
                        "artifact:windows.moonlight-web", "C:\\x\\moonlight.zip")))
                        .append('\n');
            }
            Path ps1 = dir.resolve(token + ".ps1");
            Files.writeString(ps1, body.toString(), StandardCharsets.UTF_8);

            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "$e=$null; [System.Management.Automation.Language.Parser]::ParseFile('"
                            + ps1.toString().replace("'", "''") + "',[ref]$null,[ref]$e) | Out-Null; $e.Count")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            assertEquals("0", out, token + " 语法错误:\n" + out);
        }
    }
}
