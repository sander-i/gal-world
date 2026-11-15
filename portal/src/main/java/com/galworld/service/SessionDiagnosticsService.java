/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import com.galworld.config.PathConfig;
import com.galworld.platform.CommandRunner;
import com.galworld.platform.OsKind;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 崩溃诊断：把「会话为什么停了」变成人能读的一段话。
 *
 * 数据来源：
 *  - 游戏日志（run-game.sh / Windows 子进程都写到 logsDir/<id>.log）
 *  - Linux 上再问 systemd 要退出码与终止信号
 *
 * 只读操作，失败一律降级为空结果，绝不因为诊断失败影响游玩页。
 */
@Service
public class SessionDiagnosticsService {

    /** 日志尾部最多 40 行，够定位「最后一条错误」又不至于把页面撑爆 */
    private static final int MAX_LINES = 40;
    /** 只读文件末尾 64 KB，避免把几十 MB 的日志整个读进内存 */
    private static final long MAX_BYTES = 64 * 1024;
    private static final int MAX_SUMMARY_CHARS = 220;
    private static final Duration SYSTEMCTL_TIMEOUT = Duration.ofSeconds(4);

    private static final Pattern ERROR_HINT = Pattern.compile(
            "(?i)\\b(fatal|error|exception|failed|crash|abort|segmentation fault|"
                    + "unhandled|not found|permission denied)\\b");

    /**
     * systemd 对失败单元的记账（数值含义见 systemd 的 CLD_* 定义）。
     * 包内可见，便于测试直接构造。
     */
    static record SystemdFacts(String result, String activeState, String subState,
                               Integer mainCode, Integer mainStatus) {
    }

    /**
     * 由 ExecMainCode/ExecMainStatus 翻译出的终止方式：
     * 正常 exit 给退出码，被信号杀给信号名。
     */
    static record ExitInfo(Integer exitCode, String signal) {
        static final ExitInfo NONE = new ExitInfo(null, null);
    }

    public record Diagnostics(String gameId, String state, boolean crashed, Integer exitCode,
                              String signal, String summary, List<String> logTail,
                              String logFile) {
    }

    private final PathConfig paths;
    private final CommandRunner runner;

    public SessionDiagnosticsService(PathConfig paths, CommandRunner runner) {
        this.paths = paths;
        this.runner = runner;
    }

    public Diagnostics of(String gameId, SessionService.SessionStatus status) {
        Path logFile = paths.logsDir().resolve(gameId + ".log").normalize();
        List<String> tail = readTail(logFile);
        SystemdFacts facts = systemdFacts(gameId);

        // 只有确实异常时才解释终止方式：用户主动 stop 时 systemd 记的是
        // ExecMainCode=2、ExecMainStatus=15（SIGTERM），那不是崩溃。
        ExitInfo exit = isFailed(status) ? classify(facts) : ExitInfo.NONE;
        String summary = summary(status, facts, tail);
        return new Diagnostics(gameId, status.state().name(), status.crashed(),
                exit.exitCode(), exit.signal(), summary, tail, logFile.toString());
    }

    private static boolean isFailed(SessionService.SessionStatus status) {
        return status.state() == SessionService.State.FAILED || status.crashed();
    }

    String summary(SessionService.SessionStatus status, SystemdFacts facts,
                   List<String> tail) {
        StringBuilder sb = new StringBuilder();
        boolean failed = isFailed(status);
        switch (status.state()) {
            case FAILED -> sb.append("游戏会话异常终止");
            case STOPPED -> sb.append(failed ? "游戏进程自行退出" : "游戏会话已停止");
            default -> sb.append("游戏会话状态：").append(status.state());
        }
        // 只有确实异常时才解释 systemd 的账本与日志里的错误行；
        // 正常停止时最后一条 error 通常是历史噪声，拼上来会误导。
        if (failed) {
            if (facts != null && facts.result() != null && !facts.result().isBlank()
                    && !"success".equalsIgnoreCase(facts.result())) {
                sb.append("（systemd 结果为 ").append(facts.result()).append("）");
            }
            ExitInfo exit = classify(facts);
            if (exit.exitCode() != null) {
                sb.append("，退出码 ").append(exit.exitCode());
            }
            if (exit.signal() != null) {
                sb.append("，被信号 ").append(exit.signal()).append(" 终止");
            }
            String hint = firstErrorLine(tail);
            if (!hint.isEmpty()) {
                sb.append("：").append(truncate(hint, MAX_SUMMARY_CHARS));
            }
        }
        return sb.toString();
    }

    /**
     * 把 systemd 的 ExecMainCode/ExecMainStatus 翻译成退出码或信号。
     * 1 = CLD_EXITED（正常 exit），2 = CLD_KILLED（被信号杀），
     * 3 = CLD_DUMPED（被信号杀且留下 core dump）。
     */
    static ExitInfo classify(SystemdFacts facts) {
        if (facts == null || facts.mainCode() == null || facts.mainStatus() == null) {
            return ExitInfo.NONE;
        }
        return switch (facts.mainCode()) {
            case 1 -> new ExitInfo(facts.mainStatus(), null);
            case 2, 3 -> new ExitInfo(null, signalName(facts.mainStatus()));
            default -> ExitInfo.NONE;
        };
    }

    /** 从尾部往前找第一条像错误日志的行 */
    private String firstErrorLine(List<String> tail) {
        for (int i = tail.size() - 1; i >= 0; i--) {
            String line = tail.get(i);
            if (ERROR_HINT.matcher(line).find()) {
                return line;
            }
        }
        return "";
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * 读日志末尾。按字节截断后按行切；UTF-8 截在多字节中间会得到一个
     * 替换字符，只影响一行，可接受。
     */
    private List<String> readTail(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        try (var in = Files.newInputStream(file)) {
            long size = Files.size(file);
            long skip = Math.max(0, size - MAX_BYTES);
            in.skipNBytes(skip);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String[] lines = text.split("\\R");
            List<String> out = new ArrayList<>();
            int from = Math.max(0, lines.length - MAX_LINES);
            for (int i = from; i < lines.length; i++) {
                String t = lines[i].strip();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    private SystemdFacts systemdFacts(String gameId) {
        if (OsKind.current() != OsKind.LINUX) {
            return null;
        }
        String unit = "galworld-game@" + gameId + ".service";
        CommandRunner.Result r = runner.exec(List.of(
                "systemctl", "--user", "show", unit,
                "--property=Result,ActiveState,SubState,ExecMainCode,ExecMainStatus"),
                SYSTEMCTL_TIMEOUT);
        if (!r.ok() || r.output().isBlank()) {
            return null;
        }
        return parseFacts(r.output());
    }

    /** 解析 `systemctl show --property=...` 的 Key=Value 输出；包内可见便于测试。 */
    static SystemdFacts parseFacts(String output) {
        String result = "";
        String active = "";
        String sub = "";
        Integer code = null;
        Integer status = null;
        if (output != null) {
            for (String line : output.split("\\R")) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                switch (key) {
                    case "Result" -> result = value;
                    case "ActiveState" -> active = value;
                    case "SubState" -> sub = value;
                    case "ExecMainCode" -> code = parseInt(value);
                    case "ExecMainStatus" -> status = parseInt(value);
                    default -> { }
                }
            }
        }
        return new SystemdFacts(result, active, sub, code, status);
    }

    private static Integer parseInt(String v) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 常见信号名；未知信号只显示编号 */
    private static String signalName(int signal) {
        return switch (signal) {
            case 6 -> "SIGABRT(6)";
            case 9 -> "SIGKILL(9)";
            case 11 -> "SIGSEGV(11)";
            case 15 -> "SIGTERM(15)";
            default -> "signal " + signal;
        };
    }
}
