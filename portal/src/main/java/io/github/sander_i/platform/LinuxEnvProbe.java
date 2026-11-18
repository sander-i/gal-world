/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.platform;

import io.github.sander_i.domain.Game;
import io.github.sander_i.i18n.I18n;
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
                new EnvPermission(I18n.t("账号组"), !groups.isEmpty(),
                        groups.isEmpty() ? I18n.t("读取失败") : String.join(" / ", groups)),
                new EnvPermission("sudo", sudo,
                        root ? I18n.t("root 账号") : sudo ? (sudoNoPassword ? I18n.t("免密") : I18n.t("需密码")) : I18n.t("无 sudo")),
                new EnvPermission(I18n.t("docker 组"), dockerGroup,
                        dockerGroup ? I18n.t("已加入") : I18n.t("不在 docker 组")),
                new EnvPermission("Docker daemon", dockerDaemon,
                        dockerDaemon ? I18n.t("可访问") : I18n.t("不可访问或无 docker")),
                new EnvPermission("Python venv", pythonVenv,
                        pythonVenv ? I18n.t("python3 可用") : I18n.t("python3 缺失")),
                new EnvPermission("/dev/kvm", kvm, kvm ? I18n.t("可读") : I18n.t("不存在或不可读")),
                new EnvPermission(I18n.t("libvirt 组"), libvirt, libvirt ? I18n.t("已加入") : I18n.t("不在 libvirt 组")));

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
        selkiesDetails.add(I18n.t("可执行文件: {0}{1}", selkiesPath, (selkies ? I18n.t(" · 存在") : I18n.t(" · 不存在"))));
        selkiesDetails.add(I18n.t("配置端口: {0}", ports));
        for (int p : portList) {
            selkiesDetails.add(I18n.t("端口 {0}: {1}", p, (runner.portOpen(p) ? I18n.t("监听中") : I18n.t("未监听"))));
        }
        selkiesDetails.add(I18n.t("传输: WebRTC / WebSocket"));

        List<String> displayDetails = new ArrayList<>();
        displayDetails.add("Xvfb: " + (xvfb ? I18n.t("已安装") : I18n.t("未找到")));
        displayDetails.add(I18n.t("配置显示: {0}", displays));
        for (int d : displayList) {
            displayDetails.add(I18n.t(":{0} socket: {1} (/tmp/.X11-unix/X{2})", d,
                    (Files.exists(Path.of("/tmp/.X11-unix/X" + d))
                            ? I18n.t("存在") : I18n.t("不存在")), d));
        }
        displayDetails.add(I18n.t("渲染: Mesa llvmpipe 软件渲染"));

        // wow64 wine：32 位 PE 跑进 64 位进程，绕开旧式 WoW64 的 4 GiB 地址空间上限。
        // 大型 32 位 galgame 需要它，game.env 里用 WINE_HOME 指过来。
        Path wow64Wine = paths.root().resolve(".local/wine11/bin/wine");
        boolean wow64 = Files.isExecutable(wow64Wine);
        String wow64Version = wow64 ? runner.output(wow64Wine.toString(), "--version") : "";

        List<String> wineDetails = List.of(
                "wine --version: " + (wineVersion.isEmpty() ? I18n.t("不可用") : wineVersion),
                I18n.t("WINEPREFIX 数量: {0}", prefixes),
                I18n.t("隔离方式: 每游戏独立前缀"),
                I18n.t("wow64 构建: {0}", (wow64
                        ? (wow64Version.isEmpty() ? ".local/wine11" : wow64Version + " · .local/wine11")
                        : I18n.t("未安装（32 位大型作品需要）"))));
        List<String> alsaDetails = List.of(
                I18n.t("设备: hw:0,0,<slot>"),
                "snd-aloop: " + (loopback ? I18n.t("已加载") : I18n.t("未加载")),
                I18n.t("链路: hw:0,0,<slot> → hw:0,1,<slot>"));
        List<String> captureDetails = List.of(
                I18n.t("PulseAudio gal_ 模块: {0}", (pulseModules < 0 ? I18n.t("pactl 不可用") : pulseModules)),
                I18n.t("命名: gal_<id>_in"));
        List<String> loopbackDetails = List.of(
                "PulseAudio gal_ sink: " + (pulseSinks < 0 ? I18n.t("pactl 不可用") : pulseSinks),
                I18n.t("监听: gal_<id> monitor"),
                I18n.t("编码: pcmflux → Opus"));

        List<EnvGroup> groups = new ArrayList<>();
        groups.add(new EnvGroup(I18n.t("串流"), streamHealth,
                !selkies ? I18n.t("Selkies 未安装")
                        : anyPort ? I18n.t("运行中 · 端口 ") + ports : I18n.t("已安装 · 无运行中端口"),
                List.of(new EnvItem("Selkies", selkies
                        ? I18n.t("已安装（venv） · 端口 ") + ports
                        : I18n.t("未检测到 venv/bin/selkies"),
                        selkies ? "active" : "planned", streamHealth, selkiesDetails))));
        groups.add(new EnvGroup(I18n.t("显示"), displayHealth,
                !xvfb ? I18n.t("Xvfb 未安装")
                        : anyDisplay ? I18n.t("运行中 · Xvfb ") + displays : I18n.t("已安装 · 无运行中显示"),
                List.of(new EnvItem(I18n.t("X11 采集"), I18n.t("Xvfb {0} · Mesa llvmpipe 软渲染", displays),
                        "active", displayHealth, displayDetails))));
        groups.add(new EnvGroup(I18n.t("兼容层"), wineHealth,
                !wine ? I18n.t("未找到 wine")
                        : prefixes > 0 ? I18n.t("正常 · ") + prefixes + I18n.t(" 个独立前缀") : I18n.t("正常 · 无 WINEPREFIX"),
                List.of(new EnvItem("Wine", prefixes > 0
                        ? I18n.t("独立 WINEPREFIX × ") + prefixes : I18n.t("独立 WINEPREFIX"),
                        "active", wineHealth, wineDetails),
                        new EnvItem("wow64 Wine", wow64
                        ? (wow64Version.isEmpty() ? I18n.t("已安装 · .local/wine11")
                        : wow64Version + " · .local/wine11")
                        : I18n.t("未安装（一键部署可构建）"),
                        wow64 ? "active" : "planned", wow64 ? "ok" : "warn",
                        List.of(I18n.t("用途: 32 位 PE 跑进 64 位进程，避开旧式 WoW64 的 4 GiB 地址空间上限"),
                                I18n.t("路径: {0}", wow64Wine),
                                I18n.t("指定方式: 该游戏 game.env 里写 WINE_HOME"))))));
        groups.add(new EnvGroup(I18n.t("音频"), audioHealth,
                !loopback ? I18n.t("snd-aloop 未加载")
                        : (pulseModules > 0 || pulseSinks > 0)
                        ? I18n.t("正常 · ") + pulseModules + I18n.t(" 个采集源 / ") + pulseSinks + I18n.t(" 个回环")
                        : I18n.t("已加载 · 无活动音频链路"),
                List.of(
                        new EnvItem(I18n.t("ALSA 虚拟声卡"), loopback
                                ? I18n.t("hw:0,0,<slot> · snd-aloop 已加载")
                                : I18n.t("hw:0,0,<slot> · 未检测到 snd-aloop"),
                                loopback ? "active" : "planned", audioHealth, alsaDetails),
                        new EnvItem(I18n.t("音频采集"), pulseModules >= 0
                                ? "hw:0,1,<slot> · gal_<id>_in × " + pulseModules
                                : "hw:0,1,<slot> · PulseAudio gal_<id>_in",
                                "active", audioHealth, captureDetails),
                        new EnvItem(I18n.t("回环编码"), pulseSinks >= 0
                                ? "gal_<id> sink × " + pulseSinks + " · monitor pcmflux → Opus"
                                : "PulseAudio sink gal_<id> · monitor pcmflux → Opus",
                                "active", audioHealth, loopbackDetails))));
        groups.add(new EnvGroup(I18n.t("外围"), nginx ? "warn" : "unknown",
                nginx ? I18n.t("已安装 · 未接入门户") : I18n.t("未安装"),
                List.of(new EnvItem("Nginx", nginx ? I18n.t("已安装，未接入门户（预留）") : I18n.t("未接入（预留）"),
                        nginx ? "installed" : "planned", nginx ? "warn" : "unknown",
                        List.of(I18n.t("配置: {0}", (nginx ? I18n.t("/etc/nginx/nginx.conf 存在") : I18n.t("未找到"))),
                                I18n.t("门户接入: 否"),
                                I18n.t("HTTPS: Spring Boot 内嵌提供"))))));

        boolean pathOk = Files.isReadable(paths.root()) && Files.isWritable(paths.root());
        List<EnvPermission> perms = List.of(new EnvPermission(I18n.t("部署目录"),
                pathOk, paths.root() + (pathOk ? I18n.t(" · 可读写") : I18n.t(" · 不可读写"))));

        return new EnvScheme("selkies", "Selkies + Wine", "active",
                I18n.t("当前线上方案：WebRTC 低延迟，有音频；pip 安装，无需 Docker"),
                I18n.t("Xvfb 提供虚拟 X11 显示，Wine 运行 Windows 游戏，PulseAudio/snd-aloop 采集声音，Selkies 通过 WebRTC 推流到浏览器。每个游戏一个 systemd user 单元，Portal 只通过状态与文件系统管理会话。"),
                List.of(I18n.t("WebRTC 延迟最低，音频链路完整"),
                        I18n.t("pip 安装，不依赖 Docker 与镜像仓库"),
                        I18n.t("Portal 与会话解耦，替换播放器不影响业务")),
                List.of(I18n.t("无 GPU 时依赖 llvmpipe + x264 软编码，CPU 占用高"),
                        I18n.t("Xvfb/Wine/ALSA/Pulse 组合配置复杂，排障成本高"),
                        I18n.t("每游戏独立 WINEPREFIX 与声卡 slot，磁盘与配置量随游戏增长")),
                List.of("Ubuntu 22.04+ / Debian 12+",
                        I18n.t("Python 3.10+ 与 venv"),
                        I18n.t("Wine 9.0+（32 位前缀）"),
                        I18n.t("snd-aloop 内核模块"),
                        I18n.t("每个游戏一个空闲 TCP 端口（默认 9002 起）"),
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
                new EnvPermission(I18n.t("docker 组"), docker,
                        docker ? I18n.t("当前账号已在 docker 组") : I18n.t("不在 docker 组，需要提权后重新登录")),
                new EnvPermission("Docker daemon", daemon,
                        daemon ? I18n.t("可访问") : I18n.t("不可访问或无 docker")),
                new EnvPermission(I18n.t("APT 提权"), account.elevated(),
                        account.elevated() ? I18n.t("sudo 可用") : I18n.t("无 sudo")));

        return new EnvScheme("docker-selkies", "Docker Selkies", "rejected",
                I18n.t("未采用：账号无 docker 组，GHCR 拉取慢；与当前 Selkies 功能相同"),
                I18n.t("把 Selkies 与依赖打进容器，宿主机只保留 X11/Wine/音频挂载。隔离性与可复现性好，但国内拉取 GHCR 慢，且需要 docker 组权限。"),
                List.of(I18n.t("环境隔离，依赖不污染宿主机"),
                        I18n.t("镜像版本固定，换机复现简单"),
                        I18n.t("可同时运行多套串流实例")),
                List.of(I18n.t("当前账号不在 docker 组，需要提权并重新登录"),
                        I18n.t("国内拉取 GHCR 镜像慢，首次部署耗时长"),
                        I18n.t("需要处理 X11 socket、声卡设备与 Pulse socket 的挂载")),
                List.of("Docker Engine 24+",
                        I18n.t("账号在 docker 组"),
                        I18n.t("可访问 GHCR 或国内镜像")),
                List.of(new EnvGroup(I18n.t("部署方式"), List.of(
                        new EnvItem(I18n.t("镜像"), I18n.t("Selkies 官方 Docker 镜像（GHCR）"), "rejected"),
                        new EnvItem(I18n.t("网络"), I18n.t("国内拉取 GHCR 缓慢，首次部署耗时长"), "rejected"),
                        new EnvItem(I18n.t("权限"), I18n.t("账号不在 docker 组，需额外授权"), "rejected"))),
                        new EnvGroup(I18n.t("对比"), List.of(
                                new EnvItem(I18n.t("功能"), I18n.t("与当前 pip 版 Selkies 一致"), "planned"),
                                new EnvItem(I18n.t("结论"), I18n.t("保留为可选方案，当前不部署"), "planned")))),
                List.of(
                        new EnvCommand(I18n.t("拉取镜像"),
                                "docker pull ghcr.io/selkies-project/selkies:latest",
                                I18n.t("国内网络可能很慢")),
                        new EnvCommand(I18n.t("运行示例"),
                                "docker run --rm --network host -e DISPLAY=:2 "
                                        + "-v /tmp/.X11-unix:/tmp/.X11-unix "
                                        + "ghcr.io/selkies-project/selkies:latest",
                                I18n.t("参考命令；音频与 Pulse socket 还需额外挂载"))),
                perms,
                List.of(
                        new EnvCommand(I18n.t("加入 docker 组"),
                                "sudo usermod -aG docker $USER",
                                I18n.t("需要 sudo；执行后重新登录或 newgrp")),
                        new EnvCommand(I18n.t("当前 shell 生效"), "newgrp docker", I18n.t("只影响当前终端"))),
                List.of(new EnvCommand(I18n.t("恢复权限"),
                        "sudo gpasswd -d $USER docker", I18n.t("部署完成后移除 docker 组权限"))));
    }

    private EnvScheme xpraScheme(AccountInfo account) {
        return new EnvScheme("xpra", "xpra + Wine", "planned",
                I18n.t("备选：Portal 只依赖会话状态，播放器可替换，业务代码无需改动"),
                I18n.t("用 xpra 的 HTML5 客户端替代 Selkies。Portal 侧只改 /play/:id 的 iframe 地址，会话仍然由 systemd 管理。适合弱网与断线重连场景。"),
                List.of(I18n.t("HTML5/WebSocket，无需 WebCodecs"),
                        I18n.t("断线重连和带宽自适应更成熟"),
                        I18n.t("Portal 业务代码几乎不改")),
                List.of(I18n.t("延迟与音频链路弱于 WebRTC，未在本机实测"),
                        I18n.t("xpra 与 Wine 的剪贴板、输入法兼容性需重新验证"),
                        I18n.t("需要额外维护 xpra 服务与端口")),
                List.of("Ubuntu 22.04+", I18n.t("apt 可安装 xpra-html5"), I18n.t("空闲 TCP 端口")),
                List.of(new EnvGroup(I18n.t("状态"), List.of(
                        new EnvItem(I18n.t("部署"), I18n.t("未部署"), "planned"),
                        new EnvItem(I18n.t("接入点"), I18n.t("/play/:id 的 iframe 播放器"), "planned"))),
                        new EnvGroup(I18n.t("取舍"), List.of(
                                new EnvItem(I18n.t("优势"), I18n.t("HTML5/WebSocket，断线重连与带宽占用更友好"), "planned"),
                                new EnvItem(I18n.t("代价"), I18n.t("延迟与音频链路弱于 WebRTC，未做实测"), "planned")))),
                List.of(
                        new EnvCommand(I18n.t("安装 xpra"),
                                "sudo apt install -y xpra xpra-html5", I18n.t("参考命令；未在本机执行")),
                        new EnvCommand(I18n.t("启动示例"),
                                "xpra start :2 --bind-tcp=0.0.0.0:9002 --html=on "
                                        + "--start-child=\"wine game.exe\"",
                                I18n.t("端口与 Wine 命令需按游戏调整"))),
                List.of(new EnvPermission(I18n.t("APT 提权"), account.elevated(),
                        account.elevated() ? I18n.t("sudo 可用") : I18n.t("当前账号无 sudo"))),
                List.of(new EnvCommand(I18n.t("临时提权"), "sudo -v", I18n.t("用于 apt 安装"))),
                List.of(new EnvCommand(I18n.t("回收提权"), "sudo -k", I18n.t("部署完成后清掉凭据"))));
    }

    private EnvScheme windowsVmScheme(AccountInfo account) {
        boolean kvm = Files.isReadable(Path.of("/dev/kvm"));
        boolean libvirt = account.groups().contains("libvirt");
        List<EnvPermission> perms = List.of(
                new EnvPermission("/dev/kvm", kvm, kvm ? I18n.t("当前账号可读") : I18n.t("不存在或不可读")),
                new EnvPermission(I18n.t("libvirt 组"), libvirt, libvirt ? I18n.t("已在 libvirt 组") : I18n.t("不在 libvirt 组")),
                new EnvPermission(I18n.t("APT 提权"), account.elevated(),
                        account.elevated() ? I18n.t("sudo 可用") : I18n.t("无 sudo")));

        return new EnvScheme("windows-vm", "Windows VM", "planned",
                I18n.t("退路：Wine 兼容失败时改用 KVM 装 Windows；服务器无 GPU，性能更差"),
                I18n.t("用 KVM 运行完整 Windows，再在虚拟机内跑游戏与串流端。兼容性最好，但服务器无 GPU，软渲染性能明显下降。"),
                List.of(I18n.t("兼容性最好，不依赖 Wine"),
                        I18n.t("可直接使用 Windows 原生驱动与工具链"),
                        I18n.t("与现有 Portal 解耦，虚拟机内自成体系")),
                List.of(I18n.t("无 GPU，QEMU 软渲染性能差"),
                        I18n.t("每台虚拟机独立占用内存与 CPU，并发能力低"),
                        I18n.t("需要额外的 Windows 授权与虚拟机镜像维护")),
                List.of(I18n.t("CPU 支持 VT-x / AMD-V"), I18n.t("/dev/kvm 可读"), "libvirt + QEMU",
                        I18n.t("Windows 安装镜像与授权"), I18n.t("≥ 8 GB 空闲内存")),
                List.of(new EnvGroup(I18n.t("状态"), List.of(
                        new EnvItem(I18n.t("部署"), I18n.t("未部署"), "planned"),
                        new EnvItem(I18n.t("触发条件"), I18n.t("Wine 无法运行目标游戏时启用"), "planned"))),
                        new EnvGroup(I18n.t("限制"), List.of(
                                new EnvItem(I18n.t("图形"), I18n.t("无 GPU，只能软件渲染"), "planned"),
                                new EnvItem(I18n.t("资源"), I18n.t("每台虚拟机独立占用内存与 CPU"), "planned")))),
                List.of(
                        new EnvCommand(I18n.t("安装虚拟化组件"),
                                "sudo apt install -y qemu-kvm libvirt-daemon-system virtinst",
                                I18n.t("参考命令；未在本机执行")),
                        new EnvCommand(I18n.t("创建虚拟机示例"),
                                "virt-install --name gal-win --memory 8192 --vcpus 4 "
                                        + "--disk size=60 --cdrom win.iso --os-variant win10",
                                I18n.t("镜像、内存与磁盘按实际情况调整"))),
                perms,
                List.of(
                        new EnvCommand(I18n.t("加入虚拟化组"),
                                "sudo usermod -aG libvirt,kvm $USER",
                                I18n.t("需要 sudo；执行后重新登录或 newgrp")),
                        new EnvCommand(I18n.t("当前 shell 生效"), "newgrp libvirt", I18n.t("只影响当前终端"))),
                List.of(new EnvCommand(I18n.t("恢复权限"),
                        "sudo gpasswd -d $USER libvirt && sudo gpasswd -d $USER kvm",
                        I18n.t("部署完成后移除虚拟化组权限"))));
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
