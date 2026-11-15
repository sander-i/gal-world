/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import io.github.sander_i.platform.OsKind;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 步骤表：代码内定义，不来自数据库、不来自前端。
 *
 * 与 docs/ONE-CLICK-DEPLOY.md 的表格一一对应；新增步骤必须同时补
 * {@link StepProbe} 的实现，否则页面会显示 UNKNOWN 并每次都执行。
 */
@Component
public class DeploySteps {

    private static final Duration D_SHORT = Duration.ofSeconds(60);
    private static final Duration D_MED = Duration.ofMinutes(5);
    private static final Duration D_LONG = Duration.ofMinutes(20);
    /** 源码编译 Wine 的步骤：16 核机器约 30–60 分钟，留足余量 */
    private static final Duration D_BUILD = Duration.ofMinutes(120);

    private final List<DeployStep> all;

    public DeploySteps() {
        this.all = List.of(
                // ================= Windows =================
                new DeployStep("win.preflight", "系统前置检查",
                        "检查 Windows 版本、架构、磁盘空间与端口占用",
                        OsKind.WINDOWS, List.of(), DeployStep.Kind.PREFLIGHT,
                        DeployStep.RunAs.USER, false, D_SHORT, null,
                        List.of("preflight-windows"), false,
                        "Windows 版本或磁盘空间不满足要求", "win.preflight"),

                new DeployStep("win.dir", "初始化目录",
                        "创建 lib / data / logs / games / moonlight-web",
                        OsKind.WINDOWS, List.of("win.preflight"), DeployStep.Kind.CONFIG,
                        DeployStep.RunAs.USER, false, D_SHORT, null,
                        List.of("init-dirs"), false,
                        "无法创建目录，检查磁盘权限", "win.dir"),

                new DeployStep("win.cert", "生成门户证书",
                        "自签名 PKCS12，供 Portal HTTPS 使用",
                        OsKind.WINDOWS, List.of("win.dir"), DeployStep.Kind.CONFIG,
                        DeployStep.RunAs.ADMIN, false, D_SHORT, null,
                        List.of("gen-cert"), false,
                        "证书生成失败，检查证书存储或改用已有 p12", "win.cert"),

                new DeployStep("win.firewall", "放行端口",
                        "8088 与 Sunshine 端口组（TCP 47984/47989/48010、UDP 47998-48000）",
                        OsKind.WINDOWS, List.of("win.dir"), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.ADMIN, true, D_SHORT, null,
                        List.of("add-firewall"), false,
                        "需要管理员权限；已存在同名规则时跳过", "win.firewall"),

                new DeployStep("win.sunshine", "安装 Sunshine",
                        "从元件仓库取 MSI 并静默安装，注册 SunshineService",
                        OsKind.WINDOWS, List.of("win.preflight"), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.ADMIN, true, D_MED, "windows.sunshine",
                        List.of("install-sunshine"), false,
                        "安装失败：检查安装包完整性（SHA256）与管理员权限", "win.sunshine"),

                new DeployStep("win.sunshine-conf", "配置虚拟声卡输出",
                        "写入 sunshine.conf 的 virtual_sink，串流时切到 CABLE Input",
                        OsKind.WINDOWS, List.of("win.sunshine"), DeployStep.Kind.CONFIG,
                        DeployStep.RunAs.ADMIN, false, D_SHORT, null,
                        List.of("configure-sunshine"), false,
                        "配置写入失败，检查 C:\\Program Files\\Sunshine 的写权限", "win.sunshine-conf"),

                new DeployStep("win.vbcable", "安装虚拟声卡",
                        "从元件仓库取 VB-Cable 驱动包并静默安装；需要重启后设备才出现",
                        OsKind.WINDOWS, List.of("win.preflight"), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.ADMIN, true, D_MED, "windows.vbcable",
                        List.of("install-vbcable"), true,
                        "驱动安装失败：确认已通过 SHA256 校验且系统允许安装未签名驱动", "win.vbcable"),

                new DeployStep("win.moonlight", "部署 Moonlight Web",
                        "解压浏览器端串流网关并生成配置",
                        OsKind.WINDOWS, List.of("win.dir"), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.USER, false, D_MED, "windows.moonlight-web",
                        List.of("install-moonlight"), false,
                        "解压失败：检查压缩包完整性", "win.moonlight"),

                new DeployStep("win.pair", "配对 Sunshine 与 Moonlight",
                        "写入 Moonlight 默认用户并完成设备配对",
                        OsKind.WINDOWS, List.of("win.sunshine-conf", "win.moonlight"),
                        DeployStep.Kind.EXEC, DeployStep.RunAs.USER, false, D_SHORT, null,
                        List.of("pair"), false,
                        "配对失败：确认 Sunshine Web UI 账号已设置", "win.pair"),

                new DeployStep("win.autostart", "注册开机自启",
                        "GalWorldPortal 与 GalWorldMoonlightWeb 计划任务",
                        OsKind.WINDOWS,
                        List.of("win.cert", "win.firewall", "win.sunshine", "win.moonlight"),
                        DeployStep.Kind.EXEC, DeployStep.RunAs.ADMIN, true, D_SHORT, null,
                        List.of("register-autostart"), false,
                        "计划任务注册失败：需要管理员权限", "win.autostart"),

                new DeployStep("win.verify", "端到端验证",
                        "服务状态、端口、环境信息四组健康度",
                        OsKind.WINDOWS,
                        List.of("win.sunshine", "win.moonlight", "win.firewall"),
                        DeployStep.Kind.CHECK, DeployStep.RunAs.USER, false, D_SHORT, null,
                        List.of("verify-windows"), false,
                        "验证未通过：按日志里 FAIL 的条目逐项排查", "win.verify"),

                new DeployStep("win.uninstall", "卸载串流运行时",
                        "结束游戏会话、删除 Moonlight Web 与虚拟声卡配置；保留门户、游戏与存档",
                        OsKind.WINDOWS, List.of(), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.USER, true, D_MED, null,
                        List.of("uninstall-windows"), false,
                        "卸载不完整：按日志里失败的条目手工清理", "win.uninstall"),

                new DeployStep("win.uninstall-admin", "卸载 Sunshine 与防火墙规则",
                        "停服务、按注册表 UninstallString 卸载 MSI、删除 Sunshine 端口规则",
                        OsKind.WINDOWS, List.of("win.uninstall"), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.ADMIN, true, D_MED, null,
                        List.of("uninstall-windows-sys"), false,
                        "需要管理员权限；Sunshine 未安装时自动跳过", "win.uninstall-admin"),

                // 与 Linux 对齐：卸载到只剩门户能跑，切方案时不留残留
                new DeployStep("win.uninstall-packages", "卸载系统依赖",
                        "卸载虚拟声卡驱动并清理 Sunshine 残留目录；保留 Java、门户、游戏与存档",
                        OsKind.WINDOWS, List.of("win.uninstall-admin"), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.ADMIN, true, D_MED, null,
                        List.of("uninstall-windows-packages"), false,
                        "需要管理员权限；虚拟声卡驱动卸载后需重启一次才彻底移除", "win.uninstall-packages"),

                // ================= Linux =================
                new DeployStep("lin.preflight", "系统前置检查",
                        "发行版、内核、磁盘、systemd --user、apt 可用性",
                        OsKind.LINUX, List.of(), DeployStep.Kind.PREFLIGHT,
                        DeployStep.RunAs.USER, false, D_SHORT, null,
                        List.of("preflight-linux"), false,
                        "系统版本或磁盘空间不满足要求", "lin.preflight"),

                new DeployStep("lin.packages", "安装基础包",
                        "Xvfb、PulseAudio、x11-utils、xdotool、python3-venv、文泉驿字体，"
                                + "以及 wine 需要的 i386 运行库",
                        OsKind.LINUX, List.of("lin.preflight"), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.ADMIN, true, D_MED, null,
                        List.of("apt-packages"), false,
                        "需要 sudo；APT 源不可达时检查网络或换镜像", "lin.packages"),

                new DeployStep("lin.snd-aloop", "启用 snd-aloop",
                        "写 /etc/modules-load.d 并 modprobe，提供虚拟声卡",
                        OsKind.LINUX, List.of("lin.preflight"), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.ADMIN, true, D_SHORT, null,
                        List.of("enable-snd-aloop"), false,
                        "需要 sudo；内核无 snd-aloop 模块时无法启用", "lin.snd-aloop"),

                new DeployStep("lin.audio-group", "加入 audio 组",
                        "usermod -aG audio，使 wine 能访问 /dev/snd（需重新登录生效）",
                        OsKind.LINUX, List.of("lin.preflight"), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.ADMIN, true, D_SHORT, null,
                        List.of("add-audio-group"), false,
                        "需要 sudo；加入后需重新登录或重启门户，否则服务进程仍拿不到 audio 组", "lin.audio-group"),

                new DeployStep("lin.linger", "启用 linger",
                        "loginctl enable-linger，允许用户服务开机自启",
                        OsKind.LINUX, List.of("lin.preflight"), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.ADMIN, false, D_SHORT, null,
                        List.of("enable-linger"), false,
                        "需要 sudo", "lin.linger"),

                new DeployStep("lin.wine", "安装 Wine",
                        "从元件仓库取预编译包，解压到 <root>/.local/wine；游戏会话启动时前置进 PATH",
                        OsKind.LINUX, List.of("lin.packages"), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.USER, false, D_MED, "linux.wine",
                        List.of("install-wine"), false,
                        "解压失败：检查包完整性（SHA256）与磁盘空间", "lin.wine"),

                new DeployStep("lin.wine-wow64-deps", "安装 wow64 Wine 编译依赖",
                        "build-essential、mingw-w64、X11/ALSA/GL 等开发库，仅供构建 wow64 Wine 使用",
                        OsKind.LINUX, List.of("lin.packages"), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.ADMIN, true, D_LONG, null,
                        List.of("build-wine-wow64-deps"), false,
                        "需要 sudo；APT 源不可达时检查网络或换镜像", "lin.wine-wow64-deps"),

                new DeployStep("lin.wine-wow64", "构建 wow64 Wine",
                        "从源码用 --enable-archs=i386,x86_64 编译，装到 <root>/.local/wine11；"
                                + "32 位游戏改跑 64 位进程，绕开旧式 WoW64 的 4 GiB 地址空间上限。"
                                + "需要的游戏在 game.env 里写 WINE_HOME 指过来（首次约 30–60 分钟，已存在则跳过）",
                        OsKind.LINUX, List.of("lin.wine-wow64-deps", "lin.gameunit"),
                        DeployStep.Kind.EXEC, DeployStep.RunAs.USER, false, D_BUILD, null,
                        List.of("build-wine-wow64"), false,
                        "编译失败：检查依赖是否装全、磁盘空间与源码下载；可重复执行", "lin.wine-wow64"),

                new DeployStep("lin.python-venv", "创建 Python venv",
                        "python3 -m venv ${root}/venv",
                        OsKind.LINUX, List.of("lin.packages"), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.USER, false, D_SHORT, null,
                        List.of("create-venv"), false,
                        "venv 创建失败：检查 python3-venv 是否安装", "lin.python-venv"),

                new DeployStep("lin.selkies", "安装 Selkies",
                        "从元件仓库取 wheel，pip 安装进 venv",
                        OsKind.LINUX, List.of("lin.python-venv"), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.USER, false, D_MED, "linux.selkies",
                        List.of("install-selkies"), false,
                        "pip 安装失败：检查 venv 与 wheel 完整性", "lin.selkies"),

                new DeployStep("lin.pulse", "部署音频脚本",
                        "写 pulse-daemon.sh / pulse-setup.sh 与 galworld-pulse 单元",
                        OsKind.LINUX, List.of("lin.audio-group", "lin.snd-aloop"),
                        DeployStep.Kind.CONFIG, DeployStep.RunAs.USER, false, D_SHORT, null,
                        List.of("install-pulse-scripts"), false,
                        "写入失败：检查 ${root}/bin 权限", "lin.pulse"),

                new DeployStep("lin.gameunit", "部署游戏单元模板",
                        "galworld-game@.service 与 run-game.sh / stop-game.sh",
                        OsKind.LINUX, List.of("lin.preflight"), DeployStep.Kind.CONFIG,
                        DeployStep.RunAs.USER, false, D_SHORT, null,
                        List.of("install-game-units"), false,
                        "写入失败：检查 ~/.config/systemd/user 权限", "lin.gameunit"),

                new DeployStep("lin.portal-unit", "部署门户单元",
                        "安装 galworld-portal.service（引导脚本已装过时探测为 READY）",
                        OsKind.LINUX, List.of("lin.preflight"), DeployStep.Kind.CONFIG,
                        DeployStep.RunAs.USER, false, D_SHORT, null,
                        List.of("install-portal-unit"), false,
                        "写入失败：检查 ~/.config/systemd/user 权限", "lin.portal-unit"),

                // 这几个脚本以前只能靠开发机的 deploy/portal-deploy.sh scp 上去，
                // 新装（clone + deploy.sh）的机器上根本没有，远程更新与备份会直接报错。
                new DeployStep("lin.portal-scripts", "部署门户运维脚本",
                        "安装 updater.sh / backup.sh / galworld / gen-stream-cert.sh 到 ${root}/bin；"
                                + "远程更新、备份恢复与串流证书都靠它们",
                        OsKind.LINUX, List.of("lin.preflight"), DeployStep.Kind.CONFIG,
                        DeployStep.RunAs.USER, false, D_SHORT, null,
                        List.of("install-portal-scripts"), false,
                        "写入失败：检查 ${root}/bin 权限", "lin.portal-scripts"),

                new DeployStep("lin.selfcheck", "全栈自检",
                        "执行 selfcheck.sh，输出写入部署日志",
                        OsKind.LINUX,
                        List.of("lin.packages", "lin.wine", "lin.selkies", "lin.pulse",
                                "lin.gameunit", "lin.linger"),
                        DeployStep.Kind.CHECK, DeployStep.RunAs.USER, false, D_LONG, null,
                        List.of("run-selfcheck"), false,
                        "自检未通过：按 FAIL 条目排查，处置步骤见 README「自检与恢复」", "lin.selfcheck"),

                new DeployStep("lin.uninstall", "卸载运行时组件",
                        "停用并删除 Wine、Selkies venv、音频与游戏单元；保留门户、游戏与存档",
                        OsKind.LINUX, List.of(), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.USER, true, D_MED, null,
                        List.of("uninstall-linux"), false,
                        "卸载不完整：按日志里失败的条目手工清理", "lin.uninstall"),

                new DeployStep("lin.uninstall-admin", "回滚系统级配置",
                        "移除 snd-aloop 加载项与 audio 组关系；保留 linger，门户依赖它常驻",
                        OsKind.LINUX, List.of("lin.uninstall"), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.ADMIN, true, D_SHORT, null,
                        List.of("uninstall-linux-sys"), false,
                        "需要 sudo；audio 组被移出后需重新登录", "lin.uninstall-admin"),

                // 切环境前的最后一步：把运行时依赖清到只剩门户能跑
                new DeployStep("lin.uninstall-packages", "卸载系统依赖",
                        "purge xvfb / PulseAudio / python3-venv / 中文字体与全部 i386 包，撤销 i386 架构；"
                                + "保留 JDK、门户、游戏与存档",
                        OsKind.LINUX, List.of("lin.uninstall-admin"), DeployStep.Kind.EXEC,
                        DeployStep.RunAs.ADMIN, true, D_LONG, null,
                        List.of("uninstall-linux-packages"), false,
                        "卸载失败：按日志里的包名手工 apt-get purge", "lin.uninstall-packages")
        );
    }

    public List<DeployStep> all() {
        return all;
    }

    public List<DeployStep> forOs(OsKind os) {
        return all.stream().filter(s -> s.os() == os).toList();
    }

    public DeployStep find(String id) {
        return all.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    /** 卸载步骤与安装步骤共用一套引擎；页面按这个标记单独分组。 */
    public static boolean isUninstall(DeployStep step) {
        return step.id().contains(".uninstall");
    }

    /**
     * 依赖闭包校验：requires 里出现未定义的 id 时直接报错，
     * 避免页面出现「永远 BLOCKED」的步骤。
     */
    public void validate() {
        for (DeployStep s : all) {
            for (String dep : s.requires()) {
                if (find(dep) == null) {
                    throw new IllegalStateException(
                            "步骤 " + s.id() + " 依赖未定义的 " + dep);
                }
            }
        }
    }
}
