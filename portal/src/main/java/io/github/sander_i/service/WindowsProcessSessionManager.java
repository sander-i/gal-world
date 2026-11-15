/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.domain.Game;
import io.github.sander_i.platform.CommandRunner;
import io.github.sander_i.repo.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Windows 会话后端：直接以子进程启动游戏 exe，用 pid 文件记录归属。
 *
 * 与 Linux 的差异：
 * - 没有 systemd，进程是 Portal 的子进程；用 {@code start /b} 之外的 detached 方式启动，
 *   并依赖 pid 文件做状态判断，Portal 重启后仍能识别仍在运行的会话。
 * - Sunshine 是整机串流，没有每游戏独立端口；streamUrl 统一指向 Moonlight Web。
 */
public class WindowsProcessSessionManager implements SessionManager {

    private static final Logger log = LoggerFactory.getLogger(WindowsProcessSessionManager.class);

    private final PathConfig paths;
    private final GameRepository games;
    private final CommandRunner runner;
    private final String streamBaseUrl;

    public WindowsProcessSessionManager(PathConfig paths, GameRepository games,
                                        CommandRunner runner, String streamBaseUrl) {
        this.paths = paths;
        this.games = games;
        this.runner = runner;
        this.streamBaseUrl = streamBaseUrl;
    }

    @Override
    public SessionService.SessionStatus status(String gameId, int port) {
        SessionRecord rec = readRecord(gameId).orElse(null);
        if (rec == null) {
            // 没有记录：从未启动，或启动记录已被清理
            boolean stopped = Files.exists(stoppedMarker(gameId));
            return new SessionService.SessionStatus(gameId, SessionService.State.STOPPED,
                    stopped ? "stopped" : "not-started", null, false);
        }
        boolean alive = alive(rec);
        if (alive) {
            clear(stoppedMarker(gameId));
            return new SessionService.SessionStatus(gameId, SessionService.State.RUNNING,
                    "running", streamUrl(port), false);
        }
        // 进程没了：用户主动停过的算正常停止，否则算崩溃。
        // 崩溃时不删 pid 记录，让 FAILED 状态像 systemd 的 failed 一样保持到
        // 用户重启或主动停止；否则刷新一次就变回「已停止」，丢失异常提示。
        boolean userStopped = Files.exists(stoppedMarker(gameId));
        return new SessionService.SessionStatus(gameId,
                userStopped ? SessionService.State.STOPPED : SessionService.State.FAILED,
                userStopped ? "stopped" : "exited", null, !userStopped);
    }

    @Override
    public SessionService.SessionStatus start(String gameId, int port) {
        SessionRecord existing = readRecord(gameId).orElse(null);
        if (existing != null && alive(existing)) {
            return status(gameId, port);
        }
        Game game = games.findById(gameId).orElse(null);
        if (game == null) {
            return new SessionService.SessionStatus(gameId, SessionService.State.UNKNOWN,
                    "game not found", null, false);
        }
        clear(stoppedMarker(gameId));
        Path logFile = paths.logsDir().resolve(gameId + ".log");
        Path pidFile = recordFile(gameId);
        try {
            Files.createDirectories(logFile.getParent());
            Files.createDirectories(pidFile.getParent());
            Files.createDirectories(gameWorkDir(game));
            appendLog(logFile, "[start] game=" + gameId + " exe=" + game.getExe());

            ProcessBuilder pb = new ProcessBuilder(game.getExe());
            pb.directory(gameWorkDir(game).toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            // 让进程不随 Portal 的 JVM 退出：JVM 退出不会给子进程发 SIGTERM，
            // Windows 上子进程默认独立，这里只要不主动 destroy 即可
            Process p = pb.start();
            writeRecord(pidFile, new SessionRecord(gameId, p.pid(), game.getExe(),
                    System.currentTimeMillis(), pidFile));
            // 立刻把游戏窗口提到前台。否则窗口会留在桌面「可视但在屏幕外」
            // （坐标 -32000,-32000），串流看到的是桌面而不是游戏。
            surface(p.pid(), logFile);
            return status(gameId, port);
        } catch (IOException e) {
            log.warn("启动游戏失败 {}: {}", gameId, e.toString());
            appendLog(logFile, "[error] " + e);
            return new SessionService.SessionStatus(gameId, SessionService.State.FAILED,
                    e.getMessage(), null, true);
        }
    }

    @Override
    public SessionService.SessionStatus stop(String gameId, int port) {
        SessionRecord rec = readRecord(gameId).orElse(null);
        if (rec != null && alive(rec)) {
            // 先撤掉置顶再结束进程：进程被强杀时 topmost 标志会随窗口销毁，
            // 但如果先 kill 再撤，窗口已经没了，取消置顶就落空；而且万一
            // 进程没被立刻结束，带 topmost 的窗口会一直压在其它窗口上面。
            untop(rec.pid(), paths.logsDir().resolve(gameId + ".log"));
            runner.exec(List.of("taskkill", "/PID", String.valueOf(rec.pid()), "/T", "/F"),
                    Duration.ofSeconds(10));
        }
        clear(rec == null ? recordFile(gameId) : rec.pidFile());
        write(stoppedMarker(gameId));
        appendLog(paths.logsDir().resolve(gameId + ".log"), "[stop] game=" + gameId);
        return status(gameId, port);
    }

    // ---- 进程与记录 ----

    /**
     * pid 记录里的启动时刻，即本次会话开了多久的基准。
     *
     * 会话已不在运行（进程没了）时返回 null：调用方不该拿一次已经结束的
     * 启动时间当空闲基准。
     */
    @Override
    public java.time.Instant startedAt(String gameId) {
        SessionRecord rec = readRecord(gameId).orElse(null);
        if (rec == null || !alive(rec) || rec.startedAt() <= 0) {
            return null;
        }
        return java.time.Instant.ofEpochMilli(rec.startedAt());
    }

    private record SessionRecord(String gameId, long pid, String exe, long startedAt, Path pidFile) {
    }

    private Path recordFile(String gameId) {
        return paths.dataDir().resolve("sessions").resolve(gameId + ".pid");
    }

    private Path stoppedMarker(String gameId) {
        return paths.dataDir().resolve("session-intent").resolve(gameId + ".stopped");
    }

    private void writeRecord(Path file, SessionRecord rec) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("pid=").append(rec.pid()).append('\n');
        sb.append("exe=").append(rec.exe()).append('\n');
        sb.append("startedAt=").append(rec.startedAt()).append('\n');
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private Optional<SessionRecord> readRecord(String gameId) {
        Path file = recordFile(gameId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            long pid = -1;
            String exe = "";
            long started = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = line.substring(0, eq);
                String v = line.substring(eq + 1).strip();
                switch (k) {
                    case "pid" -> pid = Long.parseLong(v);
                    case "exe" -> exe = v;
                    case "startedAt" -> started = Long.parseLong(v);
                    default -> { }
                }
            }
            if (pid <= 0) {
                return Optional.empty();
            }
            return Optional.of(new SessionRecord(gameId, pid, exe, started, file));
        } catch (IOException | NumberFormatException e) {
            log.debug("读取会话记录失败 {}: {}", file, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 进程存活且仍是当初启动的那个可执行文件。
     *
     * pid 会被系统复用，只比对存活不够；Windows 上拿不到可靠命令行时，
     * 退化为只比对 exe 文件名是否出现在进程的命令行里（拿不到就只判存活）。
     */
    private boolean alive(SessionRecord rec) {
        Optional<ProcessHandle> handle = ProcessHandle.of(rec.pid());
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return false;
        }
        String expected = fileName(rec.exe());
        return handle.get().info().commandLine()
                .map(cmd -> expected.isEmpty() || cmd.toLowerCase().contains(expected.toLowerCase()))
                .orElse(true);
    }

    private String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private Path gameWorkDir(Game game) {
        String workDir = game.getWorkDir();
        return workDir == null || workDir.isBlank()
                ? paths.gameDir(game.getId()) : Path.of(workDir);
    }

    /**
     * 把游戏的顶层窗口移到主屏并提到前台。
     *
     * 为什么要做：从服务/后台会话拉起的进程，窗口常被放到 (-32000,-32000)
     * （Windows 的「最小化到屏幕外」），进程状态是 RUNNING，但串流捕获到的
     * 只是桌面，看不到游戏。这里等窗口出现后把它最大化并置于前台。
     *
     * 用 PowerShell 调用 user32，避免引入 JNA；失败只记日志，不影响启动结果。
     */
    private void surface(long pid, Path logFile) {
        String body = "using System;using System.Runtime.InteropServices;\n"
                + "public class GWWin{\n"
                + "[DllImport(\"user32.dll\")]public static extern bool ShowWindow(IntPtr h,int n);\n"
                + "[DllImport(\"user32.dll\")]public static extern bool SetForegroundWindow(IntPtr h);\n"
                + "[DllImport(\"user32.dll\")]public static extern bool BringWindowToTop(IntPtr h);\n"
                + "[DllImport(\"user32.dll\")]private static extern bool SetWindowPos(IntPtr h,IntPtr after,int x,int y,int cx,int cy,uint f);\n"
                // PowerShell 里 [IntPtr](-1) / New-Object IntPtr -1 都会失败，
                // 所以把 HWND_TOPMOST 调用封进 C#，PowerShell 只负责调用。
                + "public static bool Topmost(IntPtr h){return SetWindowPos(h,(IntPtr)(-1),0,0,0,0,0x0043);}\n"
                // 铺满主屏：先把窗口从最大化恢复（否则 SetWindowPos 的尺寸被忽略），
                // 再设到 (0,0) 全屏尺寸。
                + "public static bool FillScreen(IntPtr h,int w,int hh){"
                + "SetWindowPos(h,(IntPtr)(-1),0,0,0,0,0x0043);"
                + "ShowWindow(h,9);"
                + "return SetWindowPos(h,(IntPtr)(-1),0,0,w,hh,0x0040|0x0004);}\n"
                + "}\n";
        String script = "$code = @'\n" + body + "'@\n"
                + "Add-Type -TypeDefinition $code\n"
                + "Add-Type -AssemblyName System.Windows.Forms\n"
                + "$b=[System.Windows.Forms.Screen]::PrimaryScreen.Bounds\n"
                + "for($i=0;$i -lt 24;$i++){\n"
                + "  $p=Get-Process -Id " + pid + " -ErrorAction SilentlyContinue\n"
                + "  if($p -and $p.MainWindowHandle -ne 0){\n"
                + "    $h=$p.MainWindowHandle\n"
                + "    [GWWin]::ShowWindow($h,3)|Out-Null\n"
                + "    [GWWin]::Topmost($h)|Out-Null\n"
                + "    [GWWin]::FillScreen($h,$b.Width,$b.Height)|Out-Null\n"
                + "    [GWWin]::BringWindowToTop($h)|Out-Null\n"
                + "    [GWWin]::SetForegroundWindow($h)|Out-Null\n"
                + "    Write-Output 'surfaced'\n"
                + "    break\n"
                + "  }\n"
                + "  Start-Sleep -Milliseconds 500\n"
                + "}";
        runScript("surface", script, logFile, Duration.ofSeconds(20));
    }

    /**
     * 撤掉窗口的 topmost 标志，把游戏窗口还原成普通窗口。
     *
     * 与 {@link #surface} 对称：surface 用 HWND_TOPMOST 把游戏压在最上层，
     * stop 时用 HWND_NOTOPMOST(-2) 撤回，否则游戏窗口会一直盖住桌面上的
     * 其它程序。失败只记日志，不影响停止结果。
     */
    private void untop(long pid, Path logFile) {
        String body = "using System;using System.Runtime.InteropServices;\n"
                + "public class GWUn{\n"
                + "[DllImport(\"user32.dll\")]private static extern bool SetWindowPos(IntPtr h,IntPtr after,int x,int y,int cx,int cy,uint f);\n"
                + "public static bool Notopmost(IntPtr h){return SetWindowPos(h,(IntPtr)(-2),0,0,0,0,0x0043);}\n"
                + "}\n";
        String script = "$code = @'\n" + body + "'@\n"
                + "Add-Type -TypeDefinition $code\n"
                + "$p=Get-Process -Id " + pid + " -ErrorAction SilentlyContinue\n"
                + "if($p -and $p.MainWindowHandle -ne 0){\n"
                + "  [GWUn]::Notopmost($p.MainWindowHandle)|Out-Null\n"
                + "  Write-Output 'untop'\n"
                + "}";
        runScript("untop", script, logFile, Duration.ofSeconds(8));
    }

    /**
     * 把 PowerShell 脚本落盘再以 -File 执行。
     *
     * 为什么不直接 -Command：脚本里有 here-string（@' ... '@），
     * 经 Windows 的参数引号规则传递时换行与引号会被破坏，
     * 表现为 Add-Type 收不到类型定义（MissingMandatoryParameter TypeDefinition）。
     * 走文件就没有任何转义问题。脚本用完即删。
     */
    private void runScript(String name, String script, Path logFile, Duration timeout) {
        Path dir = paths.dataDir().resolve("scripts");
        Path file = dir.resolve(name + "-" + ProcessHandle.current().pid() + ".ps1");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, script, StandardCharsets.UTF_8);
            CommandRunner.Result r = runner.exec(
                    List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                            "-ExecutionPolicy", "Bypass", "-File", file.toString()),
                    timeout);
            appendLog(logFile, "[" + name + "] pid=" + ProcessHandle.current().pid()
                    + " " + r.output().strip().replace('\n', ' ').replace('\r', ' '));
        } catch (IOException e) {
            log.warn("写 PowerShell 脚本失败 {}: {}", file, e.toString());
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // 清理失败无所谓，data/scripts 下是临时文件
            }
        }
    }

    private void write(Path p) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, String.valueOf(System.currentTimeMillis()));
        } catch (IOException e) {
            log.warn("写会话标记失败 {}: {}", p, e.toString());
        }
    }

    private void clear(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("清会话标记失败 {}: {}", p, e.toString());
        }
    }

    private void appendLog(Path logFile, String line) {
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("写会话日志失败 {}: {}", logFile, e.toString());
        }
    }

    /**
     * 配置了 galworld.stream-url 就用它；否则返回 null，
     * 让前端按当前页面主机名 + 游戏端口拼（与 Linux 一致）。
     */
    private String streamUrl(int port) {
        if (streamBaseUrl == null || streamBaseUrl.isBlank()) {
            return null;
        }
        // 带查询串的地址（Moonlight 的 stream.html?hostId=..&appId=..）不能再补 "/"，
        // 否则会变成 ...&appId=123/ 而打不开
        if (streamBaseUrl.contains("?") || streamBaseUrl.endsWith("/")) {
            return streamBaseUrl;
        }
        return streamBaseUrl + "/";
    }
}
