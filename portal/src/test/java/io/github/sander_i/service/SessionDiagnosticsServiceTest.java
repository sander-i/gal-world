/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.platform.CommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionDiagnosticsServiceTest {

    @TempDir
    Path root;

    private SessionDiagnosticsService service() {
        CommandRunner runner = mock(CommandRunner.class);
        when(runner.exec(anyList(), any(Duration.class)))
                .thenReturn(new CommandRunner.Result(0, ""));
        return new SessionDiagnosticsService(new PathConfig(root.toString()), runner);
    }

    @Test
    void readsTailAndBuildsCrashSummary() throws Exception {
        Path logs = root.resolve("data/logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("demo.log"),
                "line1\n[error] wine crashed at 0x4c\n   \nlast line\n");

        var status = new SessionService.SessionStatus(
                "demo", SessionService.State.FAILED, "failed", null, true);
        var d = service().of("demo", status);

        assertEquals("demo", d.gameId());
        assertTrue(d.summary().contains("异常终止"));
        assertTrue(d.summary().contains("wine crashed"));
        assertEquals(3, d.logTail().size());
        assertTrue(d.logTail().stream().noneMatch(String::isBlank));
        assertTrue(d.logFile().endsWith("demo.log"));
    }

    @Test
    void stoppedWithoutCrashDoesNotLookLikeCrash() throws Exception {
        Path logs = root.resolve("data/logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("demo.log"), "[error] old noise\n");

        var status = new SessionService.SessionStatus(
                "demo", SessionService.State.STOPPED, "inactive", null, false);
        var d = service().of("demo", status);

        assertFalse(d.crashed());
        assertFalse(d.summary().contains("异常"));
        assertFalse(d.summary().contains("old noise"));
    }

    @Test
    void missingLogReturnsEmptyTailAndStillHasSummary() {
        var status = new SessionService.SessionStatus(
                "missing", SessionService.State.FAILED, "failed", null, true);
        var d = service().of("missing", status);
        assertTrue(d.logTail().isEmpty());
        assertFalse(d.summary().isBlank());
        assertNull(d.exitCode());
        assertNull(d.signal());
    }

    @Test
    void tailIsCappedAtFortyLines() throws Exception {
        Path logs = root.resolve("data/logs");
        Files.createDirectories(logs);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("line ").append(i).append('\n');
        }
        Files.writeString(logs.resolve("demo.log"), sb.toString());

        var status = new SessionService.SessionStatus(
                "demo", SessionService.State.FAILED, "failed", null, true);
        var d = service().of("demo", status);

        assertEquals(40, d.logTail().size());
        assertEquals("line 99", d.logTail().get(39));
    }

    @Test
    void stoppedSessionIgnoresSystemdTerminationFacts() {
        var status = new SessionService.SessionStatus(
                "demo", SessionService.State.STOPPED, "inactive", null, false);
        // 用户主动 stop 后 systemd 记的是「被 SIGTERM 杀」，这不是崩溃
        var facts = new SessionDiagnosticsService.SystemdFacts(
                "success", "inactive", "dead", 2, 15);

        String summary = service().summary(status, facts, List.of());

        assertEquals("游戏会话已停止", summary);
    }

    @Test
    void ofDoesNotReportTerminationForUserStop() {
        String original = System.getProperty("os.name");
        System.setProperty("os.name", "Linux");
        try {
            CommandRunner runner = mock(CommandRunner.class);
            when(runner.exec(anyList(), any(Duration.class))).thenReturn(
                    new CommandRunner.Result(0,
                            "Result=success\nActiveState=inactive\nSubState=dead\n"
                                    + "ExecMainCode=2\nExecMainStatus=15\n"));
            var svc = new SessionDiagnosticsService(new PathConfig(root.toString()), runner);
            var status = new SessionService.SessionStatus(
                    "demo", SessionService.State.STOPPED, "inactive", null, false);

            var d = svc.of("demo", status);

            assertNull(d.exitCode());
            assertNull(d.signal());
            assertEquals("游戏会话已停止", d.summary());
        } finally {
            if (original == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", original);
            }
        }
    }

    @Test
    void parseFactsReadsSystemctlKeyValueOutput() {
        var facts = SessionDiagnosticsService.parseFacts(
                "Result=exit-code\nActiveState=failed\nSubState=failed\n"
                        + "ExecMainCode=1\nExecMainStatus=3\n");

        assertEquals("exit-code", facts.result());
        assertEquals("failed", facts.activeState());
        assertEquals("failed", facts.subState());
        assertEquals(Integer.valueOf(1), facts.mainCode());
        assertEquals(Integer.valueOf(3), facts.mainStatus());
    }

    @Test
    void classifyMapsExitCodeAndSignals() {
        var exited = SessionDiagnosticsService.classify(
                new SessionDiagnosticsService.SystemdFacts(
                        "exit-code", "failed", "failed", 1, 42));
        assertEquals(Integer.valueOf(42), exited.exitCode());
        assertNull(exited.signal());

        var killed = SessionDiagnosticsService.classify(
                new SessionDiagnosticsService.SystemdFacts(
                        "signal", "failed", "failed", 2, 9));
        assertNull(killed.exitCode());
        assertEquals("SIGKILL(9)", killed.signal());

        // CLD_DUMPED(3)：被信号杀且留下 core dump
        var dumped = SessionDiagnosticsService.classify(
                new SessionDiagnosticsService.SystemdFacts(
                        "core-dump", "failed", "failed", 3, 11));
        assertNull(dumped.exitCode());
        assertEquals("SIGSEGV(11)", dumped.signal());
    }

    @Test
    void crashedCoreDumpSummaryContainsSignalAndResult() {
        var status = new SessionService.SessionStatus(
                "demo", SessionService.State.FAILED, "failed", null, true);
        var facts = new SessionDiagnosticsService.SystemdFacts(
                "core-dump", "failed", "core-dump", 3, 11);

        String summary = service().summary(status, facts, List.of());

        assertTrue(summary.contains("SIGSEGV"));
        assertTrue(summary.contains("core-dump"));
    }
}
