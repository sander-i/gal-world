/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.platform;

import io.github.sander_i.domain.Game;
import io.github.sander_i.repo.GameRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Linux 环境探测：Selkies / Xvfb / Wine / PulseAudio 链路。
 *
 * 这些数据来自真实部署读取，不是写死文案；Windows 上有独立的 {@link WindowsEnvProbe}。
 */
@Component
public class LinuxEnvProbe implements EnvProbe {

    private final GameRepository games;
    private final PathConfigAccessor paths;
    private final CommandRunner runner;

    public LinuxEnvProbe(GameRepository games, io.github.sander_i.config.PathConfig paths, CommandRunner runner) {
        this.games = games;
        this.paths = new PathConfigAccessor(paths.root());
        this.runner = runner;
    }

    /** 只取根目录，避免把 PathConfig 整个暴露给探测逻辑。 */
    private record PathConfigAccessor(Path root) {
    }

    @Override
    public PlatformInfo os() {
        String name = "Linux";
        String version = "";
        try {
            Path osRelease = Path.of("/etc/os-release");
            if (Files.isRegularFile(osRelease)) {
                for (String line : Files.readAllLines(osRelease)) {
                    if (line.startsWith("PRETTY_NAME=")) {
                        version = line.substring("PRETTY_NAME=".length()).replace("\"", "").strip();
                    } else if (line.startsWith("NAME=") && name.equals("Linux")) {
                        name = line.substring("NAME=".length()).replace("\"", "").strip();
                    }
                }
            }
        } catch (IOException ignored) {
            // 读不到就用默认值
        }
        return new PlatformInfo(OsKind.LINUX, name,
                version.isEmpty() ? System.getProperty("os.version", "") : version,
                System.getProperty("os.arch", ""), hostname());
    }

    @Override
    public AccountInfo account() {
        String user = System.getProperty("user.name", "unknown");
        List<String> groups = groups();
        boolean root = "root".equals(user) || groups.contains("root");
        boolean sudo = root || groups.contains("sudo");
        boolean sudoNoPassword = runner.ok("sudo", "-n", "true");
        boolean dockerGroup = groups.contains("docker");
        boolean dockerDaemon = Files.isReadable(Path.of("/var/run/docker.sock"))
                || Files.isReadable(Path.of("/run/docker.sock"));
        boolean pythonVenv = runner.ok("python3", "-c", "import venv");
        boolean kvm = Files.isReadable(Path.of("/dev/kvm"));
        boolean libvirt = groups.contains("libvirt");

        List<EnvPermission> checks = List.of(
                new EnvPermission("账号组", !groups.isEmpty(),
                        groups.isEmpty() ? "读取失败" : String.join(" / ", groups)),
                new EnvPermission("sudo", sudo,
                        root ? "root 账号" : sudo ? (sudoNoPassword ? "免密" : "需密码") : "无 sudo"),
                new EnvPermission("docker 组", dockerGroup,
                        dockerGroup ? "已加入" : "不在 docker 组"),
                new EnvPermission("Docker daemon", dockerDaemon,
                        dockerDaemon ? "可访问" : "不可访问或无 docker"),
                new EnvPermission("Python venv", pythonVenv,
                        pythonVenv ? "python3 可用" : "python3 缺失"),
                new EnvPermission("/dev/kvm", kvm, kvm ? "可读" : "不存在或不可读"),
                new EnvPermission("libvirt 组", libvirt, libvirt ? "已加入" : "不在 libvirt 组"));

        return new AccountInfo(user, sudo, groups, checks);
    }

    @Override
    public List<EnvScheme> schemes(AccountInfo account) {
        return List.of(selkiesScheme(), dockerScheme(account), xpraScheme(account),
                windowsVmScheme(account));
    }

    // ---- 当前方案：从真实部署读取 ----

    private EnvScheme selkiesScheme() {
        List<Game> all = games.findAll();

        List<Integer> portList = all.stream().map(Game::getPort).filter(p -> p > 0)
                .distinct().sorted().toList();
        String ports = portList.isEmpty() ? "9002 / 9003"
                : portList.stream().map(String::valueOf).collect(Collectors.joining(" / "));

        List<Integer> displayList = all.stream().map(Game::getDisplayNum).filter(d -> d > 0)
                .distinct().sorted().toList();
        String displays = displayList.isEmpty() ? ":2 / :3"
                : displayList.stream().map(d -> ":" + d).collect(Collectors.joining(" / "));

        long prefixes = all.stream().map(Game::getWinePrefix)
                .filter(p -> p != null && !p.isBlank()).distinct().count();

        Path selkiesPath = paths.root().resolve("venv/bin/selkies");
        boolean selkies = Files.isExecutable(selkiesPath);
        boolean xvfb = Files.isExecutable(Path.of("/usr/bin/Xvfb")) || runner.ok("Xvfb", "-help");
        boolean wine = runner.ok("wine", "--version");
        String wineVersion = runner.output("wine", "--version");
        boolean loopback = sndAloopLoaded();
        int pulseModules = countPulse("modules", "gal_");
        int pulseSinks = countPulse("sinks", "gal_");
        boolean nginx = Files.isRegularFile(Path.of("/etc/nginx/nginx.conf"))
                || Files.isRegularFile(Path.of("/usr/local/nginx/conf/nginx.conf"));

        boolean anyPort = portList.stream().anyMatch(runner::portOpen);
        boolean anyDisplay = displayList.stream()
                .anyMatch(d -> Files.exists(Path.of("/tmp/.X11-unix/X" + d)));

        String streamHealth = !selkies ? "error" : anyPort ? "ok" : "warn";
        String displayHealth = !xvfb ? "error" : anyDisplay ? "ok" : "warn";
        String wineHealth = !wine ? "error" : prefixes > 0 ? "ok" : "warn";
        String audioHealth = !loopback ? "error" : (pulseModules > 0 || pulseSinks > 0) ? "ok" : "warn";

        List<String> selkiesDetails = new ArrayList<>();
        selkiesDetails.add("可执行文件: " + selkiesPath + (selkies ? " · 存在" : " · 不存在"));
        selkiesDetails.add("配置端口: " + ports);
        for (int p : portList) {
            selkiesDetails.add("端口 " + p + ": " + (runner.portOpen(p) ? "监听中" : "未监听"));
        }
        selkiesDetails.add("传输: WebRTC / WebSocket");

        List<String> displayDetails = new ArrayList<>();
        displayDetails.add("Xvfb: " + (xvfb ? "已安装" : "未找到"));
        displayDetails.add("配置显示: " + displays);
        for (int d : displayList) {
            displayDetails.add(":" + d + " socket: "
                    + (Files.exists(Path.of("/tmp/.X11-unix/X" + d)) ? "存在" : "不存在")
                    + " (/tmp/.X11-unix/X" + d + ")");
        }
        displayDetails.add("渲染: Mesa llvmpipe 软件渲染");

        // wow64 wine：32 位 PE 跑进 64 位进程，绕开旧式 WoW64 的 4 GiB 地址空间上限。
        // 大型 32 位 galgame 需要它，game.env 里用 WINE_HOME 指过来。
        Path wow64Wine = paths.root().resolve(".local/wine11/bin/wine");
        boolean wow64 = Files.isExecutable(wow64Wine);
        String wow64Version = wow64 ? runner.output(wow64Wine.toString(), "--version") : "";

        List<String> wineDetails = List.of(
                "wine --version: " + (wineVersion.isEmpty() ? "不可用" : wineVersion),
                "WINEPREFIX 数量: " + prefixes,
                "隔离方式: 每游戏独立前缀",
                "wow64 构建: " + (wow64
                        ? (wow64Version.isEmpty() ? ".local/wine11" : wow64Version + " · .local/wine11")
                        : "未安装（32 位大型作品需要）"));
        List<String> alsaDetails = List.of(
                "设备: hw:0,0,<slot>",
                "snd-aloop: " + (loopback ? "已加载" : "未加载"),
                "链路: hw:0,0,<slot> → hw:0,1,<slot>");
        List<String> captureDetails = List.of(
                "PulseAudio gal_ 模块: " + (pulseModules < 0 ? "pactl 不可用" : pulseModules),
                "命名: gal_<id>_in");
        List<String> loopbackDetails = List.of(
                "PulseAudio gal_ sink: " + (pulseSinks < 0 ? "pactl 不可用" : pulseSinks),
                "监听: gal_<id> monitor",
                "编码: pcmflux → Opus");

        List<EnvGroup> groups = new ArrayList<>();
        groups.add(new EnvGroup("串流", streamHealth,
                !selkies ? "Selkies 未安装"
                        : anyPort ? "运行中 · 端口 " + ports : "已安装 · 无运行中端口",
                List.of(new EnvItem("Selkies", selkies
                        ? "已安装（venv） · 端口 " + ports
                        : "未检测到 venv/bin/selkies",
                        selkies ? "active" : "planned", streamHealth, selkiesDetails))));
        groups.add(new EnvGroup("显示", displayHealth,
                !xvfb ? "Xvfb 未安装"
                        : anyDisplay ? "运行中 · Xvfb " + displays : "已安装 · 无运行中显示",
                List.of(new EnvItem("X11 采集", "Xvfb " + displays + " · Mesa llvmpipe 软渲染",
                        "active", displayHealth, displayDetails))));
        groups.add(new EnvGroup("兼容层", wineHealth,
                !wine ? "未找到 wine"
                        : prefixes > 0 ? "正常 · " + prefixes + " 个独立前缀" : "正常 · 无 WINEPREFIX",
                List.of(new EnvItem("Wine", prefixes > 0
                        ? "独立 WINEPREFIX × " + prefixes : "独立 WINEPREFIX",
                        "active", wineHealth, wineDetails),
                        new EnvItem("wow64 Wine", wow64
                        ? (wow64Version.isEmpty() ? "已安装 · .local/wine11"
                        : wow64Version + " · .local/wine11")
                        : "未安装（一键部署可构建）",
                        wow64 ? "active" : "planned", wow64 ? "ok" : "warn",
                        List.of("用途: 32 位 PE 跑进 64 位进程，避开旧式 WoW64 的 4 GiB 地址空间上限",
                                "路径: " + wow64Wine,
                                "指定方式: 该游戏 game.env 里写 WINE_HOME")))));
        groups.add(new EnvGroup("音频", audioHealth,
                !loopback ? "snd-aloop 未加载"
                        : (pulseModules > 0 || pulseSinks > 0)
                        ? "正常 · " + pulseModules + " 个采集源 / " + pulseSinks + " 个回环"
                        : "已加载 · 无活动音频链路",
                List.of(
                        new EnvItem("ALSA 虚拟声卡", loopback
                                ? "hw:0,0,<slot> · snd-aloop 已加载"
                                : "hw:0,0,<slot> · 未检测到 snd-aloop",
                                loopback ? "active" : "planned", audioHealth, alsaDetails),
                        new EnvItem("音频采集", pulseModules >= 0
                                ? "hw:0,1,<slot> · gal_<id>_in × " + pulseModules
                                : "hw:0,1,<slot> · PulseAudio gal_<id>_in",
                                "active", audioHealth, captureDetails),
                        new EnvItem("回环编码", pulseSinks >= 0
                                ? "gal_<id> sink × " + pulseSinks + " · monitor pcmflux → Opus"
                                : "PulseAudio sink gal_<id> · monitor pcmflux → Opus",
                                "active", audioHealth, loopbackDetails))));
        groups.add(new EnvGroup("外围", nginx ? "warn" : "unknown",
                nginx ? "已安装 · 未接入门户" : "未安装",
                List.of(new EnvItem("Nginx", nginx ? "已安装，未接入门户（预留）" : "未接入（预留）",
                        nginx ? "installed" : "planned", nginx ? "warn" : "unknown",
                        List.of("配置: " + (nginx ? "/etc/nginx/nginx.conf 存在" : "未找到"),
                                "门户接入: 否",
                                "HTTPS: Spring Boot 内嵌提供")))));

        boolean pathOk = Files.isReadable(paths.root()) && Files.isWritable(paths.root());
        List<EnvPermission> perms = List.of(new EnvPermission("部署目录",
                pathOk, paths.root() + (pathOk ? " · 可读写" : " · 不可读写")));

        return new EnvScheme("selkies", "Selkies + Wine", "active",
                "当前线上方案：WebRTC 低延迟，有音频；pip 安装，无需 Docker",
                "Xvfb 提供虚拟 X11 显示，Wine 运行 Windows 游戏，"
                        + "PulseAudio/snd-aloop 采集声音，Selkies 通过 WebRTC 推流到浏览器。"
                        + "每个游戏一个 systemd user 单元，Portal 只通过状态与文件系统管理会话。",
                List.of("WebRTC 延迟最低，音频链路完整",
                        "pip 安装，不依赖 Docker 与镜像仓库",
                        "Portal 与会话解耦，替换播放器不影响业务"),
                List.of("无 GPU 时依赖 llvmpipe + x264 软编码，CPU 占用高",
                        "Xvfb/Wine/ALSA/Pulse 组合配置复杂，排障成本高",
                        "每游戏独立 WINEPREFIX 与声卡 slot，磁盘与配置量随游戏增长"),
                List.of("Ubuntu 22.04+ / Debian 12+",
                        "Python 3.10+ 与 venv",
                        "Wine 9.0+（32 位前缀）",
                        "snd-aloop 内核模块",
                        "每个游戏一个空闲 TCP 端口（默认 9002 起）",
                        "systemd --user + loginctl enable-linger"),
                groups,
                List.of(), perms, List.of(), List.of());
    }

    // ---- 候选方案 ----

    private EnvScheme dockerScheme(AccountInfo account) {
        boolean docker = account.groups().contains("docker");
        boolean daemon = Files.isReadable(Path.of("/var/run/docker.sock"))
                || Files.isReadable(Path.of("/run/docker.sock"));
        List<EnvPermission> perms = List.of(
                new EnvPermission("docker 组", docker,
                        docker ? "当前账号已在 docker 组" : "不在 docker 组，需要提权后重新登录"),
                new EnvPermission("Docker daemon", daemon,
                        daemon ? "可访问" : "不可访问或无 docker"),
                new EnvPermission("APT 提权", account.elevated(),
                        account.elevated() ? "sudo 可用" : "无 sudo"));

        return new EnvScheme("docker-selkies", "Docker Selkies", "rejected",
                "未采用：账号无 docker 组，GHCR 拉取慢；与当前 Selkies 功能相同",
                "把 Selkies 与依赖打进容器，宿主机只保留 X11/Wine/音频挂载。"
                        + "隔离性与可复现性好，但国内拉取 GHCR 慢，且需要 docker 组权限。",
                List.of("环境隔离，依赖不污染宿主机",
                        "镜像版本固定，换机复现简单",
                        "可同时运行多套串流实例"),
                List.of("当前账号不在 docker 组，需要提权并重新登录",
                        "国内拉取 GHCR 镜像慢，首次部署耗时长",
                        "需要处理 X11 socket、声卡设备与 Pulse socket 的挂载"),
                List.of("Docker Engine 24+",
                        "账号在 docker 组",
                        "可访问 GHCR 或国内镜像"),
                List.of(new EnvGroup("部署方式", List.of(
                        new EnvItem("镜像", "Selkies 官方 Docker 镜像（GHCR）", "rejected"),
                        new EnvItem("网络", "国内拉取 GHCR 缓慢，首次部署耗时长", "rejected"),
                        new EnvItem("权限", "账号不在 docker 组，需额外授权", "rejected"))),
                        new EnvGroup("对比", List.of(
                                new EnvItem("功能", "与当前 pip 版 Selkies 一致", "planned"),
                                new EnvItem("结论", "保留为可选方案，当前不部署", "planned")))),
                List.of(
                        new EnvCommand("拉取镜像",
                                "docker pull ghcr.io/selkies-project/selkies:latest",
                                "国内网络可能很慢"),
                        new EnvCommand("运行示例",
                                "docker run --rm --network host -e DISPLAY=:2 "
                                        + "-v /tmp/.X11-unix:/tmp/.X11-unix "
                                        + "ghcr.io/selkies-project/selkies:latest",
                                "参考命令；音频与 Pulse socket 还需额外挂载")),
                perms,
                List.of(
                        new EnvCommand("加入 docker 组",
                                "sudo usermod -aG docker $USER",
                                "需要 sudo；执行后重新登录或 newgrp"),
                        new EnvCommand("当前 shell 生效", "newgrp docker", "只影响当前终端")),
                List.of(new EnvCommand("恢复权限",
                        "sudo gpasswd -d $USER docker", "部署完成后移除 docker 组权限")));
    }

    private EnvScheme xpraScheme(AccountInfo account) {
        return new EnvScheme("xpra", "xpra + Wine", "planned",
                "备选：Portal 只依赖会话状态，播放器可替换，业务代码无需改动",
                "用 xpra 的 HTML5 客户端替代 Selkies。Portal 侧只改 /play/:id 的 iframe 地址，"
                        + "会话仍然由 systemd 管理。适合弱网与断线重连场景。",
                List.of("HTML5/WebSocket，无需 WebCodecs",
                        "断线重连和带宽自适应更成熟",
                        "Portal 业务代码几乎不改"),
                List.of("延迟与音频链路弱于 WebRTC，未在本机实测",
                        "xpra 与 Wine 的剪贴板、输入法兼容性需重新验证",
                        "需要额外维护 xpra 服务与端口"),
                List.of("Ubuntu 22.04+", "apt 可安装 xpra-html5", "空闲 TCP 端口"),
                List.of(new EnvGroup("状态", List.of(
                        new EnvItem("部署", "未部署", "planned"),
                        new EnvItem("接入点", "/play/:id 的 iframe 播放器", "planned"))),
                        new EnvGroup("取舍", List.of(
                                new EnvItem("优势", "HTML5/WebSocket，断线重连与带宽占用更友好", "planned"),
                                new EnvItem("代价", "延迟与音频链路弱于 WebRTC，未做实测", "planned")))),
                List.of(
                        new EnvCommand("安装 xpra",
                                "sudo apt install -y xpra xpra-html5", "参考命令；未在本机执行"),
                        new EnvCommand("启动示例",
                                "xpra start :2 --bind-tcp=0.0.0.0:9002 --html=on "
                                        + "--start-child=\"wine game.exe\"",
                                "端口与 Wine 命令需按游戏调整")),
                List.of(new EnvPermission("APT 提权", account.elevated(),
                        account.elevated() ? "sudo 可用" : "当前账号无 sudo")),
                List.of(new EnvCommand("临时提权", "sudo -v", "用于 apt 安装")),
                List.of(new EnvCommand("回收提权", "sudo -k", "部署完成后清掉凭据")));
    }

    private EnvScheme windowsVmScheme(AccountInfo account) {
        boolean kvm = Files.isReadable(Path.of("/dev/kvm"));
        boolean libvirt = account.groups().contains("libvirt");
        List<EnvPermission> perms = List.of(
                new EnvPermission("/dev/kvm", kvm, kvm ? "当前账号可读" : "不存在或不可读"),
                new EnvPermission("libvirt 组", libvirt, libvirt ? "已在 libvirt 组" : "不在 libvirt 组"),
                new EnvPermission("APT 提权", account.elevated(),
                        account.elevated() ? "sudo 可用" : "无 sudo"));

        return new EnvScheme("windows-vm", "Windows VM", "planned",
                "退路：Wine 兼容失败时改用 KVM 装 Windows；服务器无 GPU，性能更差",
                "用 KVM 运行完整 Windows，再在虚拟机内跑游戏与串流端。"
                        + "兼容性最好，但服务器无 GPU，软渲染性能明显下降。",
                List.of("兼容性最好，不依赖 Wine",
                        "可直接使用 Windows 原生驱动与工具链",
                        "与现有 Portal 解耦，虚拟机内自成体系"),
                List.of("无 GPU，QEMU 软渲染性能差",
                        "每台虚拟机独立占用内存与 CPU，并发能力低",
                        "需要额外的 Windows 授权与虚拟机镜像维护"),
                List.of("CPU 支持 VT-x / AMD-V", "/dev/kvm 可读", "libvirt + QEMU",
                        "Windows 安装镜像与授权", "≥ 8 GB 空闲内存"),
                List.of(new EnvGroup("状态", List.of(
                        new EnvItem("部署", "未部署", "planned"),
                        new EnvItem("触发条件", "Wine 无法运行目标游戏时启用", "planned"))),
                        new EnvGroup("限制", List.of(
                                new EnvItem("图形", "无 GPU，只能软件渲染", "planned"),
                                new EnvItem("资源", "每台虚拟机独立占用内存与 CPU", "planned")))),
                List.of(
                        new EnvCommand("安装虚拟化组件",
                                "sudo apt install -y qemu-kvm libvirt-daemon-system virtinst",
                                "参考命令；未在本机执行"),
                        new EnvCommand("创建虚拟机示例",
                                "virt-install --name gal-win --memory 8192 --vcpus 4 "
                                        + "--disk size=60 --cdrom win.iso --os-variant win10",
                                "镜像、内存与磁盘按实际情况调整")),
                perms,
                List.of(
                        new EnvCommand("加入虚拟化组",
                                "sudo usermod -aG libvirt,kvm $USER",
                                "需要 sudo；执行后重新登录或 newgrp"),
                        new EnvCommand("当前 shell 生效", "newgrp libvirt", "只影响当前终端")),
                List.of(new EnvCommand("恢复权限",
                        "sudo gpasswd -d $USER libvirt && sudo gpasswd -d $USER kvm",
                        "部署完成后移除虚拟化组权限")));
    }

    // ---- 探测辅助 ----

    private List<String> groups() {
        CommandRunner.Result r = runner.exec("id", "-nG");
        if (!r.ok()) {
            return List.of();
        }
        return Arrays.stream(r.output().strip().split("\\s+"))
                .filter(s -> !s.isBlank()).distinct().sorted().toList();
    }

    private boolean sndAloopLoaded() {
        try {
            Path cards = Path.of("/proc/asound/cards");
            return Files.isRegularFile(cards)
                    && Files.readString(cards, StandardCharsets.UTF_8).toLowerCase().contains("loopback");
        } catch (IOException e) {
            return false;
        }
    }

    private int countPulse(String what, String needle) {
        CommandRunner.Result r = runner.exec("pactl", "list", "short", what);
        if (!r.ok()) {
            return -1;
        }
        return (int) r.output().lines().filter(l -> l.contains(needle)).count();
    }

    private String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "localhost";
        }
    }
}
