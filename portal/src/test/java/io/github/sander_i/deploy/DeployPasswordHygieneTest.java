/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** sudo 密码不得出现在日志/审计里（文档 §15）。 */
class DeployPasswordHygieneTest {

    @Test
    void masksSecretInOutputLines() {
        String line = "Sorry, try again. password=hunter2";
        assertEquals("Sorry, try again. password=***",
                DeployService.maskSecret(line, "hunter2"));
    }

    @Test
    void leavesCleanLinesUntouched() {
        assertEquals("Reading package lists...",
                DeployService.maskSecret("Reading package lists...", "hunter2"));
    }

    @Test
    void nullSecretIsNoop() {
        assertEquals("anything", DeployService.maskSecret("anything", null));
        assertNull(DeployService.maskSecret(null, "x"));
    }

    @Test
    void clearSudoZeroesBuffer() {
        DeployRun run = new DeployRun("r-test", "lin.test");
        char[] pwd = "hunter2".toCharArray();
        run.sudo(pwd);
        run.clearSudo();
        assertNull(run.sudo(), "引用应被清空");
        for (char c : pwd) {
            assertEquals('\0', c, "缓冲区必须清零");
        }
        assertTrue(true);
    }
}
