/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import io.github.sander_i.platform.OsKind;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 步骤命令目录：把 {@link DeployStep#command()} 里的 token 映射成真正的命令行。
 *
 * 为什么用 token 而不是直接写命令：
 * - 步骤表只声明「做什么」，命令集中在这里审查，避免前端可影响的命令文本；
 * - 平台差异只在此处出现，步骤表保持平台无关。
 *
 * 尚未实现的 token 一律抛 {@link UnsupportedOperationException}，
 * 引擎会把它转成 FAILED 并在日志里说明，不会静默成功。
 */
@Component
public class DeployCommands {

    /** Linux：token → shell 行（逐行 sh -c 执行） */
    private final Map<String, List<String>> linux = Map.ofEntries(
            Map.entry("preflight-linux", List.of(
                    "uname -sr",
                    "df -h --output=avail / | tail -1",
                    "systemctl --user is-system-running || true",
                    "command -v apt-get >/dev/null && echo apt-get: 可用 || echo apt-get: 缺失",
                    "command -v python3 >/dev/null && python3 -V || echo python3: 缺失"
            )),
            // 提权统一由 DeployService.runLinux 加 sudo -n；命令行里禁止再写 sudo，
            // 否则会变成 sudo -n sh -c "sudo -n ..."，且 $USER 在 sudo 下是 root。
            Map.entry("apt-packages", List.of(
                    // 部署的 wine 是 32 位二进制，缺 i386 架构会 "cannot execute"
                    "dpkg --add-architecture i386",
                    "apt-get update",
                    "apt-get install -y x11-utils xvfb xdotool pulseaudio pulseaudio-utils"
                            + " python3-venv fonts-wqy-microhei",
                    // wine 运行游戏本体所需的 32 位运行库（xdotool/X11/ALSA/GL 一套）
                    "apt-get install -y libc6:i386 libstdc++6:i386 libfreetype6:i386"
                            + " libfontconfig1:i386 libx11-6:i386 libxext6:i386 libxrender1:i386"
                            + " libxi6:i386 libxcursor1:i386 libxrandr2:i386 libxinerama1:i386"
                            + " libxfixes3:i386 libxcomposite1:i386 libgl1:i386"
                            + " libasound2t64:i386 libgnutls30t64:i386 libpulse0:i386",
                    // 禁止 unattended-upgrades 在门户运行中替换 JDK：内存镜像与磁盘上的
                    // jspawnhelper 版本不匹配会让所有 fork() 子进程失败，systemctl 调不到、
                    // 会话状态永远 UNKNOWN、启动进度条卡 55%（详见 docs/JDK-DRIFT.md）。
                    // 独立文件不覆盖发行版 50/52 配置；手工升级不受影响。
                    "printf '%s\\n'"
                            + " '// Gal World: 禁止自动升级 JDK（docs/JDK-DRIFT.md）'"
                            + " 'Unattended-Upgrade::Package-Blacklist {'"
                            + " '    \"openjdk-21-jre-headless$\";'"
                            + " '    \"openjdk-21-jre$\";'"
                            + " '};'"
                            + " > /etc/apt/apt.conf.d/51galworld-jdk-autoupgrade",
                    "chmod 644 /etc/apt/apt.conf.d/51galworld-jdk-autoupgrade"
            )),
            Map.entry("enable-snd-aloop", List.of(
                    "echo snd-aloop > /etc/modules-load.d/snd-aloop.conf",
                    "modprobe snd-aloop"
            )),
            Map.entry("add-audio-group", List.of("usermod -aG audio ${user}")),
            Map.entry("enable-linger", List.of("loginctl enable-linger ${user}")),
            Map.entry("create-venv", List.of(
                    "python3 -m venv \"${root}/venv\"",
                    // wine-fonts.sh 用 fontTools 把中文字体改写成 Tahoma/MS Shell Dlg；
                    // 装不上只影响字体改写，不阻断整个部署
                    "\"${root}/venv/bin/pip\" install -q fonttools"
                            + " || echo 'WARN: fontTools 安装失败，wine 中文字体改写会跳过'"
            )),
            Map.entry("install-wine", List.of(
                    "mkdir -p \"${root}/.local/wine\"",
                    "tar -xf \"${artifact:linux.wine}\" -C \"${root}/.local/wine\" --strip-components=1"
            )),
            // wow64 wine：32 位 PE 跑进 64 位进程，绕开旧式 WoW64 的 4 GiB 地址空间上限
            // （大型 32 位 galgame 会在 wined3d 分配 GL buffer 时空指针崩溃）。
            // 只装编译依赖，构建本体交给 build-wow64-wine.sh，便于重复执行与排查。
            Map.entry("build-wine-wow64-deps", List.of(
                    "apt-get install -y build-essential bison flex gcc-multilib"
                            + " gcc-mingw-w64-x86-64 gcc-mingw-w64-i686"
                            + " libx11-dev libxext-dev libxrender-dev libxfixes-dev libxi-dev"
                            + " libxcursor-dev libxrandr-dev libxinerama-dev libxcomposite-dev"
                            + " libfreetype-dev libfontconfig-dev libgnutls28-dev libasound2-dev"
                            + " libpulse-dev libgl1-mesa-dev libvulkan-dev libudev-dev libxml2-dev"
                            + " libxslt1-dev libpng-dev libjpeg-dev libkrb5-dev libcups2-dev"
                            + " libdbus-1-dev libsane-dev libgphoto2-dev libpcap-dev libpcsclite-dev"
                            + " libsdl2-dev libwayland-dev libxkbcommon-dev libxkbregistry-dev"
                            + " libosmesa6-dev ocl-icd-opencl-dev libncurses-dev libavcodec-dev"
                            + " libavformat-dev libavutil-dev libgstreamer1.0-dev"
                            + " libgstreamer-plugins-base1.0-dev gettext autoconf"
            )),
            Map.entry("build-wine-wow64", List.of(
                    "bash \"${root}/bin/build-wow64-wine.sh\" --prefix \"${root}/.local/wine11\""
            )),
            // wheel 只带 selkies 本体，依赖（prometheus_client 等）不在包里：
            // 先按离线装，缺依赖时再走索引补全，保证离线环境不因联网失败而中断。
            Map.entry("install-selkies", List.of(
                    "\"${root}/venv/bin/pip\" install --no-index \"${artifact:linux.selkies}\""
                            + " || \"${root}/venv/bin/pip\" install \"${artifact:linux.selkies}\"")),
            // 脚本与单元由 DeployAssets 从 jar 内复制，这里只做赋权、注册与启动
            Map.entry("install-pulse-scripts", List.of(
                    "chmod +x \"${root}/bin/pulse-daemon.sh\" \"${root}/bin/pulse-setup.sh\" \"${root}/bin/selfcheck.sh\"",
                    "systemctl --user daemon-reload",
                    "systemctl --user enable galworld-pulse.service",
                    "systemctl --user start galworld-pulse.service || true"
            )),
            Map.entry("install-game-units", List.of(
                    "chmod +x \"${root}/bin/run-game.sh\" \"${root}/bin/stop-game.sh\"",
                    "systemctl --user daemon-reload"
            )),
            // 只安装并启用单元，不重启门户自身（避免部署过程中把自己杀掉）
            Map.entry("install-portal-unit", List.of(
                    "systemctl --user daemon-reload",
                    "systemctl --user enable galworld-portal.service"
            )),
            Map.entry("install-portal-scripts", List.of(
                    "chmod +x \"${root}/bin/updater.sh\" \"${root}/bin/backup.sh\""
                            + " \"${root}/bin/galworld\" \"${root}/bin/gen-stream-cert.sh\""
            )),
            Map.entry("run-selfcheck", List.of("bash \"${root}/bin/selfcheck.sh\"")),
            Map.entry("uninstall-linux", List.of(
                    "systemctl --user stop 'galworld-pulse.service' 'galworld-game@*.service' 2>/dev/null || true",
                    "systemctl --user disable galworld-pulse.service galworld-game@.service 2>/dev/null || true",
                    "systemctl --user reset-failed 2>/dev/null || true",
                    "rm -f \"${root}\"/data/session-intent/*.started",
                    "pkill -f '[r]un-game.sh' || true",
                    "rm -f \"$HOME/.config/systemd/user/galworld-pulse.service\"",
                    "rm -f \"$HOME/.config/systemd/user/galworld-game@.service\"",
                    "rm -f \"$HOME/.config/pulse/default.pa\" \"$HOME/.config/pulse/daemon.conf\"",
                    "systemctl --user daemon-reload 2>/dev/null || true",
                    "rm -f \"${root}/bin/pulse-daemon.sh\" \"${root}/bin/pulse-setup.sh\"",
                    "rm -f \"${root}/bin/selfcheck.sh\" \"${root}/bin/run-game.sh\"",
                    "rm -f \"${root}/bin/stop-game.sh\" \"${root}/bin/wine-fonts.sh\"",
                    "rm -f \"${root}/bin/game-assets.sh\"",
                    "rm -f \"${root}/bin/build-wow64-wine.sh\"",
                    // 元件缓存属于运行时依赖：留着会在切换方案时被误用成旧版本
                    "rm -rf \"${root}/data/artifacts\"",
                    // wow64 是从源码编出来的运行时，体积大且能重建，一并清掉
                    "rm -rf \"${root}/.local/wine\" \"${root}/.local/wine11\" \"${root}/venv\""
            )),
            // 不关 linger：门户本身是 --user 单元，没有 linger 会随最后一个登录会话被停掉，
            // 「卸载保留门户」的承诺就落空了。linger 属于门户的运行前提，不是游戏运行时组件。
            Map.entry("uninstall-linux-sys", List.of(
                    "rm -f /etc/modules-load.d/snd-aloop.conf",
                    "modprobe -r snd-aloop 2>/dev/null || true",
                    "gpasswd -d '${user}' audio 2>/dev/null || true"
            )),
            // 彻底卸载：只留门户能跑的部分（JDK、jar、data、linger）。
            // 这里的行只用于页面预览，真正执行的是 galworld-sudo uninstall-packages。
            Map.entry("uninstall-linux-packages", List.of(
                    "rm -f /etc/apt/apt.conf.d/51galworld-jdk-autoupgrade",
                    "apt-get purge -y x11-utils xvfb xdotool pulseaudio pulseaudio-utils"
                            + " python3-venv fonts-wqy-microhei",
                    "apt-get purge -y --allow-remove-essential $(dpkg -l | awk '/^ii .*:i386/ {print $2}')",
                    "dpkg --remove-architecture i386",
                    "apt-get autoremove -y"
            ))
    );

    /**
     * Linux 提权步骤：token → 固定提权入口的子命令。
     *
     * 提权不再走 {@code sudo -n sh -c "<整行>"}：白名单放行 sh 等于放行任意 root 命令。
     * 改为由 deploy.sh 安装的 {@code /usr/local/sbin/galworld-sudo} 只实现这几个固定动作，
     * sudoers 也只放行对应子命令，于是页面不需要 sudo 密码，权限边界仍然是「只能做这些」。
     *
     * 脚本本体在仓库根 deploy.sh 的 heredoc 里（随引导脚本一起发到机器上），
     * 这里只维护 token → 子命令的映射；新增 ADMIN 步骤必须同时补映射，否则回退到密码路径。
     */
    private final Map<String, String> linuxSudo = Map.of(
            "apt-packages", "packages",
            "build-wine-wow64-deps", "wine-deps",
            "enable-snd-aloop", "snd-aloop",
            "add-audio-group", "audio-group",
            "enable-linger", "linger",
            "uninstall-linux-sys", "uninstall-sys",
            "uninstall-linux-packages", "uninstall-packages");

    /**
     * 期望的提权脚本版本。脚本头部写 {@code # galworld-sudo-version: N}。
     *
     * 远程更新只替换 jar，磁盘上的包装脚本不会跟着变；改了命令表却用旧脚本时，
     * 表现是「页面点了报需要密码」。状态接口把这个版本号带出去，页面提示重跑 deploy.sh。
     */
    public static final int SUDO_WRAPPER_VERSION = 3;

    /** 该 token 是否有固定提权入口；没有则返回 null，由调用方回退到 sudo 密码路径 */
    public String sudoSubcommand(String token) {
        return linuxSudo.get(token);
    }

    /** Windows：token → PowerShell 行（写入 .ps1 后 -File 执行） */
    private final Map<String, List<String>> windows = Map.of(
            "preflight-windows", List.of(
                    "$PSVersionTable.PSVersion.ToString()",
                    "(Get-CimInstance Win32_OperatingSystem).Caption",
                    "'freeGB=' + [math]::Round((Get-PSDrive C).Free / 1GB, 1)",
                    "if (Get-Command winget -ErrorAction SilentlyContinue) { 'winget: 可用' } else { 'winget: 缺失' }"
            ),
            "init-dirs", List.of(
                    "'${root}\\lib','${root}\\data','${root}\\logs','${root}\\games','${root}\\moonlight-web' | ForEach-Object { New-Item -ItemType Directory -Force -Path $_ | Out-Null }",
                    "'目录已就绪'"
            ),
            "add-firewall", List.of(
                    "New-NetFirewallRule -DisplayName 'GalWorld Portal' -Direction Inbound -Protocol TCP -LocalPort 8088 -Action Allow -ErrorAction SilentlyContinue",
                    "New-NetFirewallRule -DisplayName 'Sunshine TCP' -Direction Inbound -Protocol TCP -LocalPort 47984,47989,48010 -Action Allow -ErrorAction SilentlyContinue",
                    "New-NetFirewallRule -DisplayName 'Sunshine UDP' -Direction Inbound -Protocol UDP -LocalPort 47998-48000 -Action Allow -ErrorAction SilentlyContinue"
            ),
            "install-sunshine", List.of(
                    "Start-Process msiexec.exe -ArgumentList '/i','\"${artifact:windows.sunshine}\"','/qn' -Wait",
                    "'Sunshine 安装完成'"
            ),
            "install-vbcable", List.of(
                    "Expand-Archive -Force '${artifact:windows.vbcable}' \"${root}\\data\\vbcable\"",
                    "Start-Process \"${root}\\data\\vbcable\\VBCABLE_Setup_x64.exe\" -ArgumentList '-i','-h' -Wait"
            ),
            "install-moonlight", List.of(
                    "Expand-Archive -Force '${artifact:windows.moonlight-web}' \"${root}\\moonlight-web\"",
                    "'Moonlight Web 已解压'"
            )
    );

    /**
     * Windows 后半段 token：Map.of 最多 10 对，这里单列一张表，resolve 时两张都查。
     */
    private final Map<String, List<String>> windowsExtra = Map.of(
            // 自签名 PKCS12：密码与 Linux 侧一致（changeit），门户通过 GALWORLD_KEYSTORE 读取
            "gen-cert", List.of(
                    "$dir = '${root}\\data\\certs'; New-Item -ItemType Directory -Force -Path $dir | Out-Null",
                    "$cert = New-SelfSignedCertificate -DnsName 'localhost' -CertStoreLocation 'Cert:\\CurrentUser\\My' -NotAfter (Get-Date).AddYears(10)",
                    "$pwd = ConvertTo-SecureString -String 'changeit' -AsPlainText -Force",
                    "Export-PfxCertificate -Cert $cert -FilePath (Join-Path $dir 'galworld.p12') -Password $pwd | Out-Null",
                    "'证书已导出: ' + (Join-Path $dir 'galworld.p12')"
            ),
            // Sunshine 配置：把串流音频固定到 VB-Cable
            "configure-sunshine", List.of(
                    "$conf = Join-Path $env:ProgramFiles 'Sunshine\\config\\sunshine.conf'",
                    "New-Item -ItemType Directory -Force -Path (Split-Path $conf) | Out-Null",
                    "$cur = if (Test-Path $conf) { (Get-Content $conf) | Where-Object { $_ -notmatch '^\\s*virtual_sink\\s*=' } } else { @() }",
                    "Set-Content -Path $conf -Value (@($cur) + 'virtual_sink = CABLE Input') -Encoding UTF8",
                    "'已写入 ' + $conf"
            ),
            // 配对：需要 Sunshine Web UI 账号（SUNSHINE_USER / SUNSHINE_PASS），缺省直接报错
            "pair", List.of(
                    "$u = $env:SUNSHINE_USER; $p = $env:SUNSHINE_PASS",
                    "if (-not $u -or -not $p) { throw '缺少 SUNSHINE_USER / SUNSHINE_PASS（Sunshine Web UI 账号）' }",
                    "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12",
                    "[Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }",
                    "$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($u + ':' + $p))",
                    "$pin = -join (1..4 | ForEach-Object { Get-Random -Minimum 0 -Maximum 10 })",
                    "Invoke-RestMethod -Method Post -Uri 'https://localhost:47990/api/pin' -Headers @{ Authorization = $auth } -ContentType 'application/json' -Body (@{ pin = $pin; name = 'GalWorld' } | ConvertTo-Json) | Out-Null",
                    "'配对 PIN 已提交: ' + $pin"
            ),
            "register-autostart", List.of(
                    "$java = (Get-Command java.exe).Source",
                    "$tr = `\"$java`\" -jar `\"${root}\\lib\\gal-world-portal.jar`\"",
                    "schtasks /Create /TN 'GalWorldPortal' /TR $tr /SC ONLOGON /RL HIGHEST /F",
                    "$moon = '${root}\\moonlight-web\\moonlight-web.exe'",
                    "if (Test-Path $moon) { $tr2 = `\"$moon`\"; schtasks /Create /TN 'GalWorldMoonlightWeb' /TR $tr2 /SC ONLOGON /RL HIGHEST /F }",
                    "'计划任务已注册'"
            ),
            "verify-windows", List.of(
                    "$bad = 0",
                    "if (Get-Service SunshineService -ErrorAction SilentlyContinue) { 'PASS SunshineService' } else { 'FAIL SunshineService'; $bad++ }",
                    "if (Get-NetTCPConnection -LocalPort 47989 -State Listen -ErrorAction SilentlyContinue) { 'PASS 47989' } else { 'FAIL 47989'; $bad++ }",
                    "$c = New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1','${portalPort}'); 'PASS portal ${portalPort}' } catch { 'FAIL portal ${portalPort}'; $bad++ } finally { $c.Dispose() }",
                    "'FAIL=' + $bad",
                    "if ($bad -gt 0) { exit 1 }"
            ),
            "uninstall-windows", List.of(
                    "Get-Process -Name 'moonlight-web' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue",
                    "$sessions = Join-Path '${root}' 'data\\sessions'",
                    "if (Test-Path -LiteralPath $sessions) { Get-ChildItem -LiteralPath $sessions -Filter *.pid | ForEach-Object { $rec = $_; $pl = Select-String -LiteralPath $rec.FullName -Pattern '^pid=(\\d+)' -ErrorAction SilentlyContinue | Select-Object -First 1; if ($pl) { $pv = [int]$pl.Matches.Groups[1].Value; $el = Select-String -LiteralPath $rec.FullName -Pattern '^exe=(.+)$' -ErrorAction SilentlyContinue | Select-Object -First 1; $exe = if ($el) { Split-Path $el.Matches.Groups[1].Value -Leaf } else { '' }; $proc = Get-Process -Id $pv -ErrorAction SilentlyContinue; if (-not $proc) { Remove-Item -LiteralPath $rec.FullName -Force -ErrorAction SilentlyContinue } elseif ($exe -and ($proc.ProcessName -eq ($exe -replace '\\.exe$','') -or ($proc.ProcessName + '.exe') -eq $exe)) { Stop-Process -Id $pv -Force -ErrorAction SilentlyContinue; Remove-Item -LiteralPath $rec.FullName -Force -ErrorAction SilentlyContinue } else { '跳过 PID ' + $pv + '：进程已不是当初启动的 exe' } } } }",
                    "Remove-Item -LiteralPath '${root}\\moonlight-web' -Recurse -Force -ErrorAction SilentlyContinue",
                    "if (Test-Path -LiteralPath '${root}\\moonlight-web') { throw 'moonlight-web 目录仍存在（可能有进程占用）' }",
                    // 元件缓存属于运行时依赖：切方案前清掉，避免旧元件被复用
                    "Remove-Item -LiteralPath '${root}\\data\\artifacts' -Recurse -Force -ErrorAction SilentlyContinue",
                    "'运行时组件已清理'"
            ),
            "uninstall-windows-sys", List.of(
                    "Unregister-ScheduledTask -TaskName 'GalWorldMoonlightWeb' -Confirm:$false -ErrorAction SilentlyContinue",
                    "$conf = Join-Path $env:ProgramFiles 'Sunshine\\config\\sunshine.conf'",
                    "if (Test-Path $conf) { Set-Content -Path $conf -Value ((Get-Content $conf) | Where-Object { $_ -notmatch '^\\s*virtual_sink\\s*=' }) -Encoding UTF8 }",
                    "Stop-Service SunshineService -Force -ErrorAction SilentlyContinue",
                    "$keys = @('HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*','HKLM:\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*')",
                    "$app = Get-ItemProperty $keys -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -like 'Sunshine*' } | Select-Object -First 1",
                    "if ($app -and $app.UninstallString) { $code = ([regex]::Match($app.UninstallString, '\\{[0-9A-Fa-f-]+\\}')).Value; if ($code) { $p = Start-Process msiexec.exe -ArgumentList '/x', $code, '/qn', '/norestart' -Wait -PassThru; if ($p.ExitCode -ne 0) { throw ('msiexec 卸载失败，exit=' + $p.ExitCode) } } else { '未找到静默卸载入口，跳过: ' + $app.UninstallString } } else { 'Sunshine 未安装，跳过' }",
                    "Get-NetFirewallRule -DisplayName 'Sunshine TCP' -ErrorAction SilentlyContinue | Remove-NetFirewallRule",
                    "Get-NetFirewallRule -DisplayName 'Sunshine UDP' -ErrorAction SilentlyContinue | Remove-NetFirewallRule",
                    "'Sunshine 与防火墙规则已清理'"
            ),
            // 彻底卸载：清到只剩门户能跑。虚拟声卡是驱动，卸载后要重启才真正消失
            "uninstall-windows-packages", List.of(
                    "$vb = Join-Path '${root}' 'data\\vbcable\\VBCABLE_Setup_x64.exe'",
                    "$vbDone = $false",
                    "if (Test-Path -LiteralPath $vb) { $p = Start-Process -FilePath $vb -ArgumentList '-u','-h' -Wait -PassThru; 'VB-Cable 卸载 exit=' + $p.ExitCode; $vbDone = ($p.ExitCode -eq 0) }",
                    // 回退：安装包不在（目录被清过）时按设备找 INF，用 pnputil 直接删驱动包。
                    // 只认 ROOT\\MEDIA\\ 上的真实设备：CABLE 还会以 SWD\\MMDEVAPI 音频端点出现，
                    // 它们的 INF 是系统自带的 audioendpoint.inf，误删会动到 Windows 组件。
                    "if (-not $vbDone) {",
                    "  $devs = Get-PnpDevice -ErrorAction SilentlyContinue | Where-Object { $_.FriendlyName -like '*CABLE*' -and $_.InstanceId -like 'ROOT\\MEDIA\\*' }",
                    "  if (-not $devs) { '未发现可卸载的 CABLE 驱动设备' } else {",
                    "    foreach ($d in $devs) {",
                    "      $prop = Get-PnpDeviceProperty -InstanceId $d.InstanceId -KeyName 'DEVPKEY_Device_DriverInfPath' -ErrorAction SilentlyContinue",
                    "      $inf = ''",
                    "      if ($prop) { $inf = $prop.Data }",
                    "      if ($inf -eq '' -or $inf -eq 'audioendpoint.inf') { '跳过 ' + $d.InstanceId + '：没有独立驱动包'; continue }",
                    "      '移除设备: ' + $d.FriendlyName + ' (' + $d.InstanceId + ')'",
                    "      pnputil /remove-device $d.InstanceId",
                    "      '删除驱动包: ' + $inf",
                    "      pnputil /delete-driver $inf /uninstall /force",
                    "    }",
                    "  }",
                    "}",
                    "Remove-Item -LiteralPath (Join-Path '${root}' 'data\\vbcable') -Recurse -Force -ErrorAction SilentlyContinue",
                    "$sdir = Join-Path $env:ProgramFiles 'Sunshine'",
                    "if (Test-Path -LiteralPath $sdir) { Remove-Item -LiteralPath $sdir -Recurse -Force -ErrorAction SilentlyContinue }",
                    "if (Test-Path -LiteralPath $sdir) { throw 'Sunshine 目录仍在（服务可能未停，先重启再卸载）' }",
                    "'系统依赖已清理；虚拟声卡驱动需重启后完全生效'"
            )
    );

    /**
     * @throws UnsupportedOperationException 该 token 尚未实现
     */
    public List<String> resolve(OsKind os, String token) {
        List<String> lines;
        if (os == OsKind.WINDOWS) {
            lines = windows.get(token);
            if (lines == null) {
                lines = windowsExtra.get(token);
            }
        } else {
            lines = linux.get(token);
        }
        if (lines == null) {
            throw new UnsupportedOperationException("步骤命令尚未实现: " + token);
        }
        return lines;
    }
}
