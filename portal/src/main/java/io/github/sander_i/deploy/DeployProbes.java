/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.platform.CommandRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 幂等探测：判断步骤目标状态是否已满足。
 *
 * 只读、无副作用。没写探测的步骤统一返回 UNKNOWN（页面显示未知，执行时照常跑），
 * 绝不谎报 READY，避免「显示已就绪但实际没装」。
 */
@Component
public class DeployProbes implements StepProbe {

    private final PathConfig paths;
    private final CommandRunner runner;

    public DeployProbes(PathConfig paths, CommandRunner runner) {
        this.paths = paths;
        this.runner = runner;
    }

    @Override
    public ProbeState probe(String stepId, java.util.Map<String, String> ctx) {
        return switch (stepId) {
            case "win.dir" -> allDirsExist() ? ProbeState.READY : ProbeState.NOT_READY;
            case "win.cert" -> allExist(paths.dataDir().resolve("certs/galworld.p12"))
                    ? ProbeState.READY : ProbeState.NOT_READY;
            case "win.sunshine" -> powershellOk(
                    "if (Get-Service SunshineService -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }")
                    ? ProbeState.READY : ProbeState.NOT_READY;
            case "win.sunshine-conf" -> sunshineConfHasSink()
                    ? ProbeState.READY : ProbeState.NOT_READY;
            case "win.firewall" -> powershellOk(
                    "if (Get-NetFirewallRule -DisplayName 'GalWorld Portal' -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }")
                    ? ProbeState.READY : ProbeState.NOT_READY;
            case "win.moonlight" -> allExist(paths.root().resolve("moonlight-web/moonlight-web.exe"))
                    ? ProbeState.READY : ProbeState.NOT_READY;
            case "win.autostart" -> powershellOk(
                    "schtasks /Query /TN 'GalWorldPortal' 2>$null | Out-Null; if ($LASTEXITCODE -eq 0) { exit 0 } else { exit 1 }")
                    ? ProbeState.READY : ProbeState.NOT_READY;
            case "lin.python-venv" -> dirReady(paths.root().resolve("venv"))
                    ? ProbeState.READY : ProbeState.NOT_READY;
            case "lin.linger" -> lingerEnabled();
            case "lin.packages" -> basePackagesReady();
            case "lin.wine" -> dirReady(paths.root().resolve(".local/wine"))
                    || commandOk(List.of("bash", "-lc", "command -v wine"))
                    ? ProbeState.READY : ProbeState.NOT_READY;
            // wow64 构建是自己编的：以安装目录里的 wine 为准
            case "lin.wine-wow64" -> dirReady(paths.root().resolve(".local/wine11"))
                    ? ProbeState.READY : ProbeState.NOT_READY;
            case "lin.wine-wow64-deps" -> commandOk(List.of("bash", "-lc",
                    "command -v x86_64-w64-mingw32-gcc >/dev/null"
                            + " && command -v i686-w64-mingw32-g++ >/dev/null"
                            + " && command -v bison >/dev/null && command -v flex >/dev/null"))
                    ? ProbeState.READY : ProbeState.NOT_READY;
            case "lin.selkies" -> commandOk(List.of("bash", "-lc",
                    "\"${root}/venv/bin/python\" -c 'import selkies'"
                            .replace("${root}", paths.root().toString())))
                    ? ProbeState.READY : ProbeState.NOT_READY;
            case "lin.snd-aloop" -> commandOk(List.of("bash", "-lc", "lsmod | grep -q '^snd_aloop'"))
                    ? ProbeState.READY : ProbeState.NOT_READY;
            case "lin.audio-group" -> expect(audioGroupMembership(), true);
            case "lin.pulse" -> allExist(
                    paths.root().resolve("bin/pulse-daemon.sh"),
                    paths.root().resolve("bin/pulse-setup.sh"),
                    userUnit("galworld-pulse.service"))
                    ? ProbeState.READY : ProbeState.NOT_READY;
            case "lin.gameunit" -> allExist(
                    paths.root().resolve("bin/run-game.sh"),
                    paths.root().resolve("bin/stop-game.sh"),
                    userUnit("galworld-game@.service"))
                    ? ProbeState.READY : ProbeState.NOT_READY;
            case "lin.portal-unit" -> commandOk(List.of("bash", "-lc",
                    "systemctl --user is-active galworld-portal.service"))
                    ? ProbeState.READY : ProbeState.NOT_READY;
            case "lin.portal-scripts" -> allExist(
                    paths.root().resolve("bin/updater.sh"),
                    paths.root().resolve("bin/backup.sh"),
                    paths.root().resolve("bin/galworld"),
                    paths.root().resolve("bin/gen-stream-cert.sh"))
                    ? ProbeState.READY : ProbeState.NOT_READY;
            case "lin.uninstall" -> linuxRuntimeRemoved()
                    ? ProbeState.READY : ProbeState.NOT_READY;
            case "lin.uninstall-admin" -> linuxSystemConfigRolledBack();
            case "lin.uninstall-packages" -> basePackagesPurged();
            case "win.uninstall" -> Files.exists(paths.root().resolve("moonlight-web"))
                    ? ProbeState.NOT_READY : ProbeState.READY;
            case "win.uninstall-admin" -> windowsSunshineRemoved();
            case "win.uninstall-packages" -> windowsRuntimePurged();
            default -> ProbeState.UNKNOWN;
        };
    }

    private boolean allDirsExist() {
        for (String d : List.of("lib", "data", "logs", "games", "moonlight-web")) {
            if (!dirReady(paths.root().resolve(d))) {
                return false;
            }
        }
        return true;
    }

    private boolean dirReady(Path p) {
        return Files.isDirectory(p);
    }

    private boolean allExist(Path... files) {
        for (Path f : files) {
            if (!Files.isRegularFile(f)) {
                return false;
            }
        }
        return true;
    }

    private Path userUnit(String name) {
        return Path.of(System.getProperty("galworld.deploy.unit-dir",
                        Path.of(System.getProperty("user.home"), ".config", "systemd", "user").toString()))
                .resolve(name);
    }

    /** 卸载步骤会删除的脚本；任何一个还在就说明没卸干净 */
    private static final List<String> RUNTIME_BIN = List.of(
            "pulse-daemon.sh", "pulse-setup.sh", "selfcheck.sh",
            "run-game.sh", "stop-game.sh", "wine-fonts.sh", "game-assets.sh",
            "build-wow64-wine.sh");

    private boolean linuxRuntimeRemoved() {
        for (String name : RUNTIME_BIN) {
            if (Files.exists(paths.root().resolve("bin").resolve(name))) {
                return false;
            }
        }
        return !Files.exists(paths.root().resolve(".local/wine"))
                && !Files.exists(paths.root().resolve(".local/wine11"))
                && !Files.exists(paths.root().resolve("venv"))
                && !Files.exists(userUnit("galworld-pulse.service"))
                && !Files.exists(userUnit("galworld-game@.service"));
    }

    private ProbeState linuxSystemConfigRolledBack() {
        if (Files.exists(Path.of("/etc/modules-load.d/snd-aloop.conf"))) {
            return ProbeState.NOT_READY;
        }
        // 模块还加载着：conf 删了但 modprobe -r 没成功
        CommandRunner.Result mod = runner.exec(List.of("bash", "-lc", "lsmod | grep -q '^snd_aloop'"));
        if (mod.exit() == 0) {
            return ProbeState.NOT_READY;
        }
        if (mod.exit() != 1) {
            return ProbeState.UNKNOWN;
        }
        // 回滚完成 = 账号已不在 audio 组
        ProbeState audio = expect(audioGroupMembership(), false);
        if (audio != ProbeState.READY) {
            return audio;
        }
        // 不再检查 linger：门户是 --user 单元，卸载故意保留 linger，
        // 关掉它会让门户随最后一个登录会话被停掉（2026-11-09 真机实测）。
        return ProbeState.READY;
    }

    /**
     * 账号是否在 audio 组。null = 查不到（/etc/group 读失败）。
     *
     * 读 /etc/group 而不是 `id -nG`：后者是门户进程启动时的快照，
     * 无论 gpasswd -d（卸载）还是 usermod -aG（安装）之后都不会变，
     * 会导致「已卸载」永远显示为待执行、「已加入」永远显示为未加入。
     */
    private Boolean audioGroupMembership() {
        try {
            return audioGroupContains(Files.readAllLines(Path.of("/etc/group")),
                    System.getProperty("user.name", ""));
        } catch (IOException e) {
            return null;
        }
    }

    /** 从 /etc/group 内容判断账号是否在 audio 组；null = 找不到 audio 行 */
    static Boolean audioGroupContains(List<String> groupLines, String user) {
        for (String line : groupLines) {
            if (!line.startsWith("audio:")) {
                continue;
            }
            String[] parts = line.split(":", -1);
            String members = parts.length > 3 ? parts[3] : "";
            return List.of(members.split(",")).contains(user);
        }
        return null;
    }

    /** 三态布尔转探测状态：未知不能当成任一确定结论 */
    private static ProbeState expect(Boolean actual, boolean expected) {
        if (actual == null) {
            return ProbeState.UNKNOWN;
        }
        return actual == expected ? ProbeState.READY : ProbeState.NOT_READY;
    }

    private ProbeState windowsSunshineRemoved() {
        Boolean service = powerShellFlag("if (Get-Service SunshineService -ErrorAction SilentlyContinue) "
                + "{ exit 0 } else { exit 1 }");
        Boolean task = powerShellFlag("schtasks /Query /TN 'GalWorldMoonlightWeb' 2>$null | Out-Null; "
                + "if ($LASTEXITCODE -eq 0) { exit 0 } else { exit 1 }");
        Boolean firewall = powerShellFlag("if (Get-NetFirewallRule -DisplayName 'Sunshine TCP' "
                + "-ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }");
        if (service == null || task == null || firewall == null) {
            return ProbeState.UNKNOWN;
        }
        return (!service && !task && !firewall) ? ProbeState.READY : ProbeState.NOT_READY;
    }

    /**
     * Windows 彻底卸载的完成判据：虚拟声卡安装包目录、Sunshine 残留目录、CABLE 音频设备都不在。
     * 驱动卸载要重启才真正消失，所以重启前这里会一直是 NOT_READY（诚实反映现状）。
     */
    private ProbeState windowsRuntimePurged() {
        String vb = paths.root().resolve("data").resolve("vbcable").toString();
        Boolean purged = powerShellFlag(
                "if ((Test-Path -LiteralPath '" + vb + "') -or "
                        + "(Test-Path -LiteralPath (Join-Path $env:ProgramFiles 'Sunshine')) -or "
                        + "(Get-CimInstance Win32_SoundDevice -ErrorAction SilentlyContinue"
                        + " | Where-Object { $_.Name -like '*CABLE*' })) { exit 1 } else { exit 0 }");
        if (purged == null) {
            return ProbeState.UNKNOWN;
        }
        return purged ? ProbeState.READY : ProbeState.NOT_READY;
    }

    /**
     * 只把脚本约定的 0/1 当作「有/无」；命令本身失败（PowerShell 缺失、查询报错）
     * 返回 null，调用方必须给 UNKNOWN，绝不能当成「已清理」。
     */
    private Boolean powerShellFlag(String script) {
        CommandRunner.Result r = runner.exec(List.of("powershell", "-NoProfile", "-Command", script));
        if (r.exit() == 0) {
            return Boolean.TRUE;
        }
        return r.exit() == 1 ? Boolean.FALSE : null;
    }

    private boolean commandOk(List<String> argv) {
        return runner.exec(argv).ok();
    }

    private boolean powershellOk(String script) {
        return runner.exec(List.of("powershell", "-NoProfile", "-Command", script)).ok();
    }

    private boolean sunshineConfHasSink() {
        try {
            Path conf = Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"),
                    "Sunshine", "config", "sunshine.conf");
            return Files.isRegularFile(conf) && Files.readString(conf).contains("virtual_sink");
        } catch (Exception e) {
            return false;
        }
    }

    private ProbeState lingerEnabled() {
        CommandRunner.Result r = runner.exec(List.of("loginctl", "show-user",
                System.getProperty("user.name", ""), "-p", "Linger"));
        if (!r.ok()) {
            return ProbeState.UNKNOWN;
        }
        return r.output().contains("Linger=yes") ? ProbeState.READY : ProbeState.NOT_READY;
    }

    private ProbeState commandAvailable(String cmd) {
        CommandRunner.Result r = runner.exec(List.of("bash", "-lc", "command -v " + cmd));
        return r.ok() ? ProbeState.READY : ProbeState.NOT_READY;
    }

    /**
     * 基础包是否齐。
     *
     * 只看 {@code xvfb-run} 会漏掉 i386 运行库与中文字体：机器上装了 xvfb 但没启用 i386 时，
     * 探针仍报 READY，一键安装直接跳过这一步，wine 起来就 "cannot execute"。
     * 所以按「三样代表 + 外来架构」判断：xvfb（串流显示）、libc6:i386（32 位 wine）、
     * fonts-wqy-microhei（wine 中文底字）。
     */
    private ProbeState basePackagesReady() {
        String script = "dpkg --print-foreign-architectures | grep -qx i386"
                + " && dpkg-query -W -f='${Status}' xvfb 2>/dev/null | grep -q 'install ok installed'"
                + " && dpkg-query -W -f='${Status}' libc6:i386 2>/dev/null | grep -q 'install ok installed'"
                + " && dpkg-query -W -f='${Status}' fonts-wqy-microhei 2>/dev/null | grep -q 'install ok installed'";
        CommandRunner.Result r = runner.exec(List.of("bash", "-lc", script));
        return r.ok() ? ProbeState.READY : ProbeState.NOT_READY;
    }

    /** 彻底卸载的完成判据：i386 架构撤销，运行时基础包不在。 */
    private ProbeState basePackagesPurged() {
        String script = "! dpkg --print-foreign-architectures | grep -qx i386"
                + " && ! dpkg-query -W -f='${Status}' xvfb 2>/dev/null | grep -q 'install ok installed'"
                + " && ! dpkg-query -W -f='${Status}' pulseaudio 2>/dev/null | grep -q 'install ok installed'"
                + " && ! dpkg-query -W -f='${Status}' python3-venv 2>/dev/null | grep -q 'install ok installed'";
        CommandRunner.Result r = runner.exec(List.of("bash", "-lc", script));
        return r.ok() ? ProbeState.READY : ProbeState.NOT_READY;
    }
}
