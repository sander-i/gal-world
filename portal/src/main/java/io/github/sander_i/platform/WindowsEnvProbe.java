/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.platform;

import io.github.sander_i.i18n.I18n;
import io.github.sander_i.repo.GameRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Windows 环境探测：原生运行 exe，Sunshine 推流。
 *
 * 所有探测走 PowerShell（带超时）或文件系统判断，不改系统状态。
 * 命令行里的安装/配置命令只做展示，不在请求里执行。
 */
@Component
public class WindowsEnvProbe implements EnvProbe {

    private static final String SUNSHINE_DIR = "C:\\Program Files\\Sunshine";
    private static final String SUNSHINE_EXE = SUNSHINE_DIR + "\\sunshine.exe";
    private static final int SUNSHINE_WEB_PORT = 47990;
    private static final List<Integer> STREAM_TCP_PORTS = List.of(47984, 47989, 48010);

    private final GameRepository games;
    private final CommandRunner runner;
    private final Path root;

    public WindowsEnvProbe(GameRepository games, io.github.sander_i.config.PathConfig paths, CommandRunner runner) {
        this.games = games;
        this.runner = runner;
        this.root = paths.root();
    }

    @Override
    public PlatformInfo os() {
        String product = ps("(Get-CimInstance Win32_OperatingSystem).Caption");
        String build = ps("(Get-CimInstance Win32_OperatingSystem).BuildNumber");
        String name = product.isBlank() ? "Windows" : product.replace("Microsoft ", "").strip();
        String version = build.isBlank() ? System.getProperty("os.version", "") : build;
        return new PlatformInfo(OsKind.WINDOWS, name, version,
                System.getProperty("os.arch", ""), hostname());
    }

    @Override
    public AccountInfo account() {
        String user = System.getProperty("user.name", "unknown");
        boolean admin = isAdmin();
        List<EnvPermission> checks = new ArrayList<>();
        checks.add(new EnvPermission(I18n.t("管理员权限"), admin,
                admin ? I18n.t("当前会话已提权") : I18n.t("普通用户，安装服务与改防火墙需要提权")));
        checks.add(new EnvPermission(I18n.t("游戏目录"), Files.isReadable(root) && Files.isWritable(root),
                root + (Files.isReadable(root) && Files.isWritable(root) ? I18n.t(" · 可读写") : I18n.t(" · 不可读写"))));
        return new AccountInfo(user, admin, List.of(), checks);
    }

    @Override
    public List<EnvScheme> schemes(AccountInfo account) {
        return List.of(sunshineScheme(account), vncScheme(account), rdpScheme(account),
                wslScheme(account));
    }

    // ---- A. Sunshine + Moonlight Web（当前采用） ----

    private EnvScheme sunshineScheme(AccountInfo account) {
        boolean installed = Files.isRegularFile(Path.of(SUNSHINE_EXE));
        String service = ps("(Get-Service SunshineService -ErrorAction SilentlyContinue).Status");
        boolean running = service.toLowerCase(Locale.ROOT).contains("running");
        boolean webUp = runner.portOpen(SUNSHINE_WEB_PORT);
        boolean anyStreamPort = STREAM_TCP_PORTS.stream().anyMatch(runner::portOpen);
        String gpus = ps("(Get-CimInstance Win32_VideoController).Name -join ' / '");
        String encoders = encoderHint(gpus);
        String sound = ps("(Get-CimInstance Win32_SoundDevice).Name -join ' / '");
        boolean virtualAudio = sound.toLowerCase(Locale.ROOT).matches(".*(cable|vb-audio|voicemeeter).*");
        String fwRules = ps("(Get-NetFirewallRule -DisplayName '*Sunshine*' -ErrorAction SilentlyContinue | Measure-Object).Count");
        boolean firewall = !fwRules.isBlank() && !"0".equals(fwRules.strip());

        String streamHealth = !installed ? "error" : running && anyStreamPort ? "ok" : "warn";
        String gpuHealth = gpus.isBlank() ? "error" : encoders.equals(I18n.t("未知")) ? "warn" : "ok";
        String audioHealth = virtualAudio ? "ok" : "warn";

        List<String> streamDetails = List.of(
                I18n.t("安装目录: {0}{1}", SUNSHINE_DIR, (installed ? I18n.t(" · 存在") : I18n.t(" · 未找到"))),
                I18n.t("服务 SunshineService: {0}", (service.isBlank() ? I18n.t("未注册") : service)),
                "Web UI: https://localhost:" + SUNSHINE_WEB_PORT + (webUp ? I18n.t(" · 监听中") : I18n.t(" · 未监听")),
                I18n.t("串流端口: {0} · {1}", STREAM_TCP_PORTS, (anyStreamPort ? I18n.t("有监听") : I18n.t("无监听"))),
                I18n.t("客户端: Moonlight Web（浏览器）"));

        List<String> gpuDetails = List.of(
                I18n.t("显卡: {0}", (gpus.isBlank() ? I18n.t("未识别") : gpus)),
                I18n.t("编码器: {0}", encoders),
                I18n.t("采集: Desktop Duplication / DXGI"));

        List<String> audioDetails = List.of(
                I18n.t("声音设备: {0}", (sound.isBlank() ? I18n.t("未识别") : sound)),
                I18n.t("虚拟声卡: {0}", (virtualAudio ? I18n.t("已安装") : I18n.t("未检测到（游戏声音需要）"))));

        List<EnvGroup> groups = List.of(
                new EnvGroup(I18n.t("串流"), streamHealth,
                        !installed ? I18n.t("Sunshine 未安装")
                                : running && anyStreamPort ? I18n.t("运行中 · Web UI ") + SUNSHINE_WEB_PORT
                                : I18n.t("已安装 · 服务或端口未就绪"),
                        List.of(new EnvItem("Sunshine", installed
                                ? I18n.t("已安装 · ") + (running ? I18n.t("服务运行中") : I18n.t("服务未运行"))
                                : I18n.t("未检测到 ") + SUNSHINE_EXE,
                                installed ? "active" : "planned", streamHealth, streamDetails))),
                new EnvGroup(I18n.t("编码"), gpuHealth, gpus.isBlank() ? I18n.t("未识别显卡") : encoders,
                        List.of(new EnvItem(I18n.t("GPU 编码"), gpus.isBlank() ? I18n.t("未识别") : gpus,
                                gpus.isBlank() ? "planned" : "active", gpuHealth, gpuDetails))),
                new EnvGroup(I18n.t("音频"), audioHealth,
                        virtualAudio ? I18n.t("虚拟声卡已就绪") : I18n.t("缺少虚拟声卡，游戏声音无法采集"),
                        List.of(new EnvItem(I18n.t("虚拟声卡"), virtualAudio
                                ? I18n.t("已安装 · 可采集游戏声音")
                                : I18n.t("未安装 · 建议 VB-Audio Virtual Cable"),
                                virtualAudio ? "active" : "planned", audioHealth, audioDetails))),
                new EnvGroup(I18n.t("网络"), firewall ? "ok" : "warn",
                        firewall ? I18n.t("已放行 Sunshine 端口") : I18n.t("未发现 Sunshine 防火墙规则"),
                        List.of(new EnvItem(I18n.t("防火墙"), firewall ? I18n.t("规则已存在") : I18n.t("未配置"),
                                firewall ? "active" : "planned", firewall ? "ok" : "warn",
                                List.of(I18n.t("规则数: {0}", (fwRules.isBlank() ? "0" : fwRules)),
                                        "TCP: 47984 / 47989 / 48010",
                                        "UDP: 47998–48000")))));

        List<EnvPermission> perms = List.of(
                new EnvPermission(I18n.t("管理员权限"), account.elevated(),
                        account.elevated() ? I18n.t("可安装服务与改防火墙") : I18n.t("需要以管理员身份运行安装脚本")),
                new EnvPermission(I18n.t("游戏目录"), Files.isReadable(root) && Files.isWritable(root),
                        root + " · " + (Files.isWritable(root) ? I18n.t("可写") : I18n.t("不可写"))));

        return new EnvScheme("sunshine", "Sunshine + Moonlight Web", "active",
                I18n.t("Windows 当前方案：原生运行游戏，GPU 硬编码推流，浏览器端用 Moonlight Web"),
                I18n.t("游戏以原生 exe 直接运行，Portal 只负责登记、启动与状态；Sunshine 作为 Windows 服务捕获桌面并硬件编码，Moonlight Web 在浏览器里解码播放。相比 Wine 方案，不再有 4:3 渲染与标题栏限制。"),
                List.of(I18n.t("原生运行，无 Wine 兼容层，画质与帧率接近本机"),
                        I18n.t("GPU 硬件编码，延迟明显低于软编码"),
                        I18n.t("Portal 只需管理进程，链路比 Xvfb/ALSA 组合简单")),
                List.of(I18n.t("需要显卡与硬件编码器，老机器或纯核显可能不支持"),
                        I18n.t("Sunshine 是整机串流，没有「每游戏独立端口」"),
                        I18n.t("浏览器端依赖 Moonlight Web，兼容性需实测")),
                List.of(I18n.t("Windows 10 1903+ 或 Windows 11（x64）"),
                        I18n.t("支持硬件编码的 GPU（NVENC / QSV / AMF）"),
                        I18n.t("管理员权限（安装服务、配置防火墙）"),
                        I18n.t("空闲 TCP 47984 / 47989 / 48010，UDP 47998–48000"),
                        I18n.t("虚拟声卡（VB-Audio Virtual Cable 或等价方案）"),
                        I18n.t("Moonlight Web 客户端（浏览器端）")),
                groups,
                List.of(
                        EnvCommand.powershell(I18n.t("安装 Sunshine"),
                                "msiexec /i Sunshine-Windows-AMD64-installer.msi /qn",
                                I18n.t("参考命令；安装包从 LizardByte/Sunshine Releases 下载")),
                        EnvCommand.powershell(I18n.t("启动服务"),
                                "Start-Service SunshineService", I18n.t("首次安装后需在 Web UI 设置账号与配对码")),
                        EnvCommand.powershell(I18n.t("开放防火墙"),
                                "New-NetFirewallRule -DisplayName 'Sunshine' -Direction Inbound -Action Allow "
                                        + "-Protocol TCP -LocalPort 47984,47989,48010",
                                I18n.t("UDP 47998-48000 另建一条规则")),
                        EnvCommand.powershell(I18n.t("检查 Web UI"),
                                "Invoke-WebRequest https://localhost:" + SUNSHINE_WEB_PORT + " -SkipCertificateCheck",
                                I18n.t("首次访问会要求设置用户名密码")),
                        EnvCommand.powershell(I18n.t("注册游戏示例"),
                                I18n.t("galworld.ps1 add sample-game --name '示例游戏' --exe 'D:\\Games\\sample-game\\game.exe'"),
                                I18n.t("Windows 下 port 统一指向 Moonlight Web"))),
                perms,
                List.of(EnvCommand.powershell(I18n.t("提升到管理员"),
                        "Start-Process powershell -Verb RunAs",
                        I18n.t("安装服务、改防火墙、写 Program Files 需要提权"))),
                List.of(
                        EnvCommand.powershell(I18n.t("停止服务"),
                                "Stop-Service SunshineService", I18n.t("只停服务，不卸载")),
                        EnvCommand.powershell(I18n.t("卸载防火墙规则"),
                                "Get-NetFirewallRule -DisplayName 'Sunshine' | Remove-NetFirewallRule",
                                I18n.t("回滚时执行")),
                        EnvCommand.powershell(I18n.t("卸载 Sunshine"),
                                "Get-Package '*Sunshine*' | Uninstall-Package",
                                I18n.t("会保留配置文件于 C:\\Program Files\\Sunshine"))));
    }

    // ---- B. VNC + noVNC（候选） ----

    private EnvScheme vncScheme(AccountInfo account) {
        boolean vncPort = runner.portOpen(5900);
        List<EnvPermission> perms = List.of(
                new EnvPermission(I18n.t("管理员权限"), account.elevated(),
                        account.elevated() ? I18n.t("可安装 VNC 服务") : I18n.t("安装 VNC 服务需要提权")),
                new EnvPermission(I18n.t("VNC 端口"), vncPort, vncPort ? I18n.t("5900 监听中") : I18n.t("5900 未监听")));

        return new EnvScheme("vnc-novnc", "VNC + noVNC", "planned",
                I18n.t("备选：部署最简单，但没有硬件编码，延迟高、音频几乎不可用"),
                I18n.t("Windows 上运行 VNC Server，再用 websockify + noVNC 把 RFB 转成 WebSocket，浏览器直接看桌面。适合排障与轻量场景，不适合游玩。"),
                List.of(I18n.t("不依赖 GPU，老机器也能跑"),
                        I18n.t("noVNC 是纯前端，Portal 可直接 iframe 嵌入"),
                        I18n.t("部署步骤少，排障直观")),
                List.of(I18n.t("无硬件编码，画面更新慢，鼠标拖影明显"),
                        I18n.t("音频链路基本不可用"),
                        I18n.t("多人并发时带宽与 CPU 压力大")),
                List.of("Windows 10 / 11",
                        I18n.t("VNC Server（TightVNC / TigerVNC 等）"),
                        I18n.t("Node.js 或 Python 运行 websockify"),
                        I18n.t("空闲 TCP 5900（VNC）与 6080（noVNC）")),
                List.of(new EnvGroup(I18n.t("状态"), List.of(
                        new EnvItem("VNC Server", vncPort ? I18n.t("5900 监听中") : I18n.t("未检测到"),
                                vncPort ? "installed" : "planned", vncPort ? "ok" : "unknown"),
                        new EnvItem("noVNC", I18n.t("未部署"), "planned"))),
                        new EnvGroup(I18n.t("取舍"), List.of(
                                new EnvItem(I18n.t("优势"), I18n.t("部署简单、无 GPU 依赖"), "planned"),
                                new EnvItem(I18n.t("代价"), I18n.t("延迟高、音频不可用"), "planned")))),
                List.of(
                        EnvCommand.powershell(I18n.t("安装 websockify"),
                                "pip install websockify", I18n.t("需要 Python 3")),
                        EnvCommand.powershell(I18n.t("启动 noVNC 代理"),
                                "websockify --web noVNC 6080 localhost:5900",
                                I18n.t("noVNC 静态文件需另下载"))),
                perms,
                List.of(EnvCommand.powershell(I18n.t("提升到管理员"),
                        "Start-Process powershell -Verb RunAs", I18n.t("安装 VNC 服务需要"))),
                List.of(EnvCommand.powershell(I18n.t("停止代理"), "Stop-Process -Name websockify",
                        I18n.t("回滚时执行"))));
    }

    // ---- C. RDP + Guacamole（候选） ----

    private EnvScheme rdpScheme(AccountInfo account) {
        boolean rdp = runner.portOpen(3389);
        List<EnvPermission> perms = List.of(
                new EnvPermission(I18n.t("远程桌面"), rdp, rdp ? I18n.t("3389 监听中") : I18n.t("3389 未监听（未开启 RDP）")),
                new EnvPermission(I18n.t("管理员权限"), account.elevated(),
                        account.elevated() ? I18n.t("可开启 RDP") : I18n.t("开启 RDP 需要提权")));

        return new EnvScheme("rdp-guacamole", "RDP + Apache Guacamole", "planned",
                I18n.t("备选：Windows 原生会话，适合远程维护，不适合游戏画面"),
                I18n.t("开启远程桌面，用 Guacamole + guacd 在浏览器里提供 RDP。剪贴板、多显示器、打印重定向完整，但 RDP 本身为办公场景优化，游戏帧率与音频都很差。"),
                List.of(I18n.t("Windows 原生会话，快捷键与多显示器支持好"),
                        I18n.t("Guacamole 是标准 Web 网关，权限模型成熟"),
                        I18n.t("不需要额外编码器")),
                List.of(I18n.t("RDP 不适合高速画面，游戏体验差"),
                        I18n.t("音频经 RDP 重定向后延迟明显"),
                        I18n.t("Guacamole 需要额外 Java 运行环境与 guacd 服务")),
                List.of(I18n.t("Windows 10 Pro / 11 Pro（家庭版不支持 RDP 服务端）"),
                        I18n.t("可用的 Java 运行环境（Guacamole）"),
                        I18n.t("空闲 TCP 3389 与 8080")),
                List.of(new EnvGroup(I18n.t("状态"), List.of(
                        new EnvItem(I18n.t("远程桌面"), rdp ? I18n.t("3389 监听中") : I18n.t("未开启"),
                                rdp ? "installed" : "planned", rdp ? "ok" : "unknown"),
                        new EnvItem("Guacamole", I18n.t("未部署"), "planned"))),
                        new EnvGroup(I18n.t("取舍"), List.of(
                                new EnvItem(I18n.t("优势"), I18n.t("原生 RDP 会话，管理操作顺手"), "planned"),
                                new EnvItem(I18n.t("代价"), I18n.t("不适合游戏画面与音频"), "planned")))),
                List.of(
                        EnvCommand.powershell(I18n.t("开启远程桌面"),
                                "Set-ItemProperty -Path 'HKLM:\\System\\CurrentControlSet\\Control\\Terminal Server' "
                                        + "-Name fDenyTSConnections -Value 0",
                                I18n.t("需要管理员；家庭版无此能力")),
                        EnvCommand.powershell(I18n.t("放行 RDP"),
                                "Enable-NetFirewallRule -DisplayGroup 'Remote Desktop'", I18n.t("需要管理员"))),
                perms,
                List.of(EnvCommand.powershell(I18n.t("提升到管理员"),
                        "Start-Process powershell -Verb RunAs", I18n.t("改注册表与防火墙需要"))),
                List.of(EnvCommand.powershell(I18n.t("关闭远程桌面"),
                        "Set-ItemProperty -Path 'HKLM:\\System\\CurrentControlSet\\Control\\Terminal Server' "
                                + "-Name fDenyTSConnections -Value 1",
                        I18n.t("回滚时执行"))));
    }

    // ---- D. WSL2 + Docker Selkies（不采用） ----

    private EnvScheme wslScheme(AccountInfo account) {
        String wsl = ps("(wsl.exe -l -q) -join ','");
        boolean wslInstalled = !wsl.isBlank();
        List<EnvPermission> perms = List.of(
                new EnvPermission(I18n.t("管理员权限"), account.elevated(),
                        account.elevated() ? I18n.t("可启用 WSL") : I18n.t("启用 WSL 需要提权")),
                new EnvPermission("WSL2", wslInstalled, wslInstalled ? I18n.t("已安装发行版") : I18n.t("未安装")));

        return new EnvScheme("wsl-selkies", "WSL2 + Docker Selkies", "rejected",
                I18n.t("未采用：复用 Linux 串流链路，但 WSLg 音频与 GPU 直通限制多，延迟不可控"),
                I18n.t("在 WSL2 里跑现有的 Selkies 容器，Windows 只作为宿主。好处是复用 Linux 侧全部脚本，代价是音频要跨 WSLg，GPU 需要额外配置。"),
                List.of(I18n.t("复用 Linux 侧的 Selkies 与音频脚本"),
                        I18n.t("Windows 与 Linux 两套部署只需维护一份串流代码")),
                List.of(I18n.t("WSLg 音频链路延迟与兼容性都不稳定"),
                        I18n.t("GPU 直通需要额外驱动与配置"),
                        I18n.t("WSL 网络与端口转发增加一层排障成本")),
                List.of("Windows 10 2004+ / Windows 11",
                        I18n.t("已启用虚拟机平台与 WSL2"),
                        I18n.t("WSL 内可运行 Docker")),
                List.of(new EnvGroup(I18n.t("状态"), List.of(
                        new EnvItem("WSL2", wslInstalled ? I18n.t("已安装") : I18n.t("未安装"),
                                wslInstalled ? "installed" : "planned",
                                wslInstalled ? "ok" : "unknown"),
                        new EnvItem("Selkies", I18n.t("未部署"), "planned"))),
                        new EnvGroup(I18n.t("取舍"), List.of(
                                new EnvItem(I18n.t("优势"), I18n.t("复用 Linux 串流链路"), "planned"),
                                new EnvItem(I18n.t("代价"), I18n.t("WSLg 音频与 GPU 直通限制"), "planned")))),
                List.of(
                        EnvCommand.powershell(I18n.t("安装 WSL"),
                                "wsl --install", I18n.t("需要管理员；安装后需重启")),
                        EnvCommand.powershell(I18n.t("查看发行版"), "wsl -l -v", I18n.t("确认默认版本为 2"))),
                perms,
                List.of(EnvCommand.powershell(I18n.t("提升到管理员"),
                        "Start-Process powershell -Verb RunAs", I18n.t("启用 WSL 需要"))),
                List.of(EnvCommand.powershell(I18n.t("卸载 WSL"),
                        "wsl --unregister Ubuntu", I18n.t("会删除发行版内所有数据"))));
    }

    // ---- 探测辅助 ----

    /** 执行一段 PowerShell 片段，返回 stdout（失败返回空串）。
     *  必须先切 UTF-8 输出，否则中文在默认代码页下会变成乱码。 */
    private String ps(String script) {
        String wrapped = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; "
                + "$OutputEncoding=[Text.Encoding]::UTF8; " + script;
        CommandRunner.Result r = runner.exec(
                List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", wrapped),
                java.time.Duration.ofSeconds(6));
        return r.ok() ? r.output().strip() : "";
    }

    private boolean isAdmin() {
        return runner.ok("net", "session");
    }

    private String encoderHint(String gpus) {
        String g = gpus.toLowerCase(Locale.ROOT);
        if (g.contains("nvidia")) {
            return "NVENC";
        }
        if (g.contains("intel") || g.contains("uhd") || g.contains("iris")) {
            return "QSV";
        }
        if (g.contains("amd") || g.contains("radeon")) {
            return "AMF";
        }
        return g.isBlank() ? I18n.t("未知") : I18n.t("未知");
    }

    private String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            String name = System.getenv("COMPUTERNAME");
            return name == null || name.isBlank() ? "localhost" : name;
        }
    }
}
