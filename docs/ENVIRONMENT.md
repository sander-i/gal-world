# 环境信息与部署方案

> 更新：2026-11-02
> 适用：Linux 与 Windows 两种部署形态

门户的「设置 → 环境信息」不是写死的文案，而是后端按当前操作系统实时探测的结果。
本文说明它能看什么、Windows 侧怎么部署、以及出问题怎么回滚。

---

## 1. 页面结构

`GET /api/environment` 返回三块：

| 字段 | 内容 |
|---|---|
| `os` | 系统类型、名称、版本、架构、主机名 |
| `account` | 当前账号与 `checks[]` 权限检查项 |
| `schemes` | 一组部署方案，含前置条件、组件状态、命令、权限、回滚 |

前端只按 `os.kind` 显示徽标，不假设平台字段；Linux 与 Windows 的差异全部在方案集里。

页面顶部可切换「当前系统 / Linux / Windows」预览另一平台：`GET /api/environment?os=linux|windows`
只返回该平台的方案说明、命令与回滚步骤，`groups`（组件状态）与 `permissions`（权限检查）为空，
`preview=true` 表示不可探测。一键部署与卸载面板只在当前系统显示。

### 平台分流

| 平台 | 探测实现 | 方案集 |
|---|---|---|
| Linux | `platform/LinuxEnvProbe` | Selkies + Wine（当前）、Docker Selkies、xpra、Windows VM |
| Windows | `platform/WindowsEnvProbe` | Sunshine + Moonlight Web（当前）、VNC + noVNC、RDP + Guacamole、WSL2 + Docker Selkies |

`web/EnvironmentController` 只做分发，不包含平台细节。

---

## 2. Linux 方案（现状，未改动）

沿用原有链路：Xvfb 提供虚拟显示，Wine 跑游戏，snd-aloop + PulseAudio 采集声音，
Selkies 通过 WebRTC 推流；会话由 `systemctl --user` 管理，每游戏一个单元。

前置条件：Ubuntu 22.04+、Python 3.10+、Wine 9.0+、`snd-aloop`、每游戏一个空闲端口、
`loginctl enable-linger`。

**wow64 Wine（可选，大型 32 位作品需要）**：Wine 9.0 的旧式 WoW64 把 32 位游戏跑在
32 位 Linux 进程里，地址空间只有 4 GiB，其中 Wine 自身预留约 2.5 GiB。大型 32 位
galgame（如 SenrenBanka）在 D3D 初始化时会顶满，wined3d 申请 64 MiB GL buffer 失败后
空指针崩溃，表现为 "has encountered a serious problem and needs to close"。
一键部署的「构建 wow64 Wine」步骤会用 `--enable-archs=i386,x86_64` 从源码编一份，
装到 `~/.local/wine11`；需要的游戏在该游戏的 `game.env` 里写：

```
WINE_HOME=/home/<user>/galworld/.local/wine11
```

`run-game.sh` 的 wine 选择顺序是 `game.env` 的 `WINE_HOME` → `~/.local/wine` → 系统 wine。

> **wow64 对 Kirikiri 自绘 UI 的汉化补丁有兼容问题**：`tenshi-reboot` 切到 wow64 后
> 地址空间问题解决（ELF32 → ELF64，`VmSize` 2.6 → 14.5 GB），但鼠标点击游戏内
> 自绘按钮完全无响应，无法游玩。已回退。详见 `docs/WINE-RUNTIME-NOTES.md` §2。

**子目录 exe 的资源补齐（`game-assets.sh`）**：汉化补丁常把启动程序放在
`补丁/<补丁名>/` 下，而 `data.xp3`、`plugin/` 等资源留在游戏根目录。
Kirikiri 等引擎按 **exe 所在目录**找资源，缺 `plugin/extrans.dll` 时会在
`initialize.tjs` 抛异常后直接退出——日志里只有一句 `Exit code: 0`，
看起来像正常关闭，很难定位。

`run-game.sh` 在 wine 启动前调用 `game-assets.sh <workdir> <exedir>`，
把工作目录顶层缺失的条目以**符号链接**补进 exe 目录（不复制，避免上百 MB
重复占用与版本漂移）。脚本幂等，已有条目一律不动；exe 就在工作目录时
什么也不做；exe 不在工作目录之下（`game.env` 的 `WORKDIR` 配错）时只告警。

详见 `deploy/bin/game-assets.sh` 的文件头注释（运维手册不随仓库分发）。

---

## 3. Windows 方案

### 3.1 当前采用：Sunshine + Moonlight Web

**形态**：Portal 与游戏同在这台 Windows 上，游戏以原生 exe 直接运行（不经 Wine），
Sunshine 作为 Windows 服务捕获桌面并硬件编码，浏览器端用 Moonlight Web 解码播放。

**前置条件**

| 项 | 要求 | 说明 |
|---|---|---|
| 系统 | Windows 10 1903+ / Windows 11（x64） | ARM64 为实验支持 |
| 显卡 | 支持硬件编码（NVENC / QSV / AMF） | 无硬件编码时画质与延迟明显变差 |
| 权限 | 管理员一次 | 安装服务、配置防火墙、创建证书 |
| 端口 | TCP 47984 / 47989 / 48010；UDP 47998–48000 | Sunshine 的 GameStream 端口 |
| Web UI | TCP 47990 | 首次配置账号与配对 |
| 音频 | 虚拟声卡（VB-Audio Virtual Cable 或等价） | 没有则游戏声音采集不到 |
| 客户端 | Moonlight Web（浏览器） | Portal 的 `/play/:id` 指向它 |

**部署步骤**

```powershell
# 1. 目录、证书、防火墙、自启（管理员 PowerShell）
.\deploy\win\install.ps1 -Jar ..\portal\target\gal-world-portal-<version>.jar

# 2. 安装 Sunshine（从 LizardByte/Sunshine Releases 取安装包）
msiexec /i Sunshine-Windows-AMD64-installer.msi /qn
Start-Service SunshineService

# 3. 虚拟声卡（VB-Cable），装完系统里出现 CABLE Input / CABLE Output
VBCABLE_Setup_x64.exe -i -h

# 4. 让 Sunshine 串流时切到虚拟声卡，本机扬声器静音，结束后自动恢复
Add-Content 'C:\Program Files\Sunshine\config\sunshine.conf' 'virtual_sink = CABLE Input (VB-Audio Virtual Cable)'
Restart-Service SunshineService

# 5. Moonlight Web（浏览器端串流网关）
#    解压 moonlight-web-x86_64-pc-windows-gnu.zip 到 %USERPROFILE%\galworld\moonlight-web
cd $env:USERPROFILE\galworld\moonlight-web
.\web-server.exe config generate
#    注册自启：schtasks 或计划任务，工作目录为本目录，执行 web-server.exe

# 6. 首次配置 https://localhost:47990 设置 Sunshine 账号密码；
#    打开 http://localhost:8080 注册 Moonlight Web 管理员 → 添加主机 127.0.0.1:47989 → 配对
#    配对 PIN 显示在 Moonlight Web 页面，填到 Sunshine 的 PIN 页完成配对

# 7. 把门户指向串流页（stream.html 的 hostId/appId 来自 Moonlight Web 的地址栏）
#    在门户启动参数里加：
#    --galworld.stream-url=http://localhost:8080/stream.html?hostId=<id>&appId=<id>
```

**部署后检查**：设置页「环境信息」的四个分组

- **串流**：`sunshine.exe` 是否存在、`SunshineService` 是否 Running、47990 是否监听、串流端口是否有监听
- **编码**：显卡型号与推断的编码器（NVIDIA→NVENC、Intel→QSV、AMD→AMF）
- **音频**：声音设备里是否有 CABLE / VB-Audio
- **网络**：是否存在名称含 Sunshine 的防火墙规则

**权限与回滚**

```powershell
# 提权
Start-Process powershell -Verb RunAs

# 回滚：停服务、删防火墙规则、卸载
Stop-Service SunshineService
Get-NetFirewallRule -DisplayName 'Sunshine*' | Remove-NetFirewallRule
Get-Package '*Sunshine*' | Uninstall-Package
```

也可以在门户「管理 → 环境信息 → 卸载环境 → 一键卸载」完成同样的运行时清理（保留游戏与存档）。

### 3.2 候选：VNC + noVNC

部署最简单，不需要 GPU；但无硬件编码、画面更新慢、音频基本不可用。
仅用于排障，不作为游玩方案。

### 3.3 候选：RDP + Apache Guacamole

Windows 原生会话，剪贴板与多显示器支持好；RDP 为办公场景优化，游戏画面与音频体验差。
用于远程维护。

### 3.4 不采用：WSL2 + Docker Selkies

复用 Linux 串流链路，但 WSLg 的音频与 GPU 直通限制多，延迟不可控。保留记录，不部署。

---

## 4. 会话管理（Windows）

`SessionService` 按平台选择后端：

| 平台 | 实现 | 启停方式 | 状态判定 |
|---|---|---|---|
| Linux | `SystemdSessionManager` | `systemctl --user start/stop` | `is-active` |
| Windows | `WindowsProcessSessionManager` | `ProcessBuilder` 直接拉起 exe；`taskkill /T /F` 停止 | pid 文件 + `ProcessHandle` |

Windows 侧的约定：

- 会话记录写在 `data\sessions\<id>.pid`（pid / exe / startedAt）。
- 进程是 Portal 的子进程，但 Portal 退出不会连带结束它；重启 Portal 后靠 pid 文件恢复识别。
- 进程不存在且没有「用户主动停止」标记 → 判定为崩溃（`FAILED`），与 systemd 的 `failed` 语义一致。
  该状态会一直保持到用户重启或主动停止，刷新页面不会丢失。
- 端口：所有游戏共用 Moonlight Web。`galworld.stream-url` 未配置时，`/play/:id` 按
  `https://<当前主机名>:<PORT>/` 拼接；配置后统一指向该地址。

---

## 5. 排障

| 现象 | 检查 |
|---|---|
| 环境信息页显示「后端不可用」 | 门户是否在跑：`Get-ScheduledTask GalWorldPortal` / `netstat -ano \| findstr :8088` |
| 串流分组显示异常 | `Get-Service SunshineService`；`https://localhost:47990` 是否可打开 |
| 音频分组警告 | 声音设备里是否有虚拟声卡；游戏内是否选到了该设备 |
| 网络分组警告 | `Get-NetFirewallRule -DisplayName 'Sunshine*'` |
| 启动后立刻变「异常」 | 看 `data\logs\<id>.log`；确认 exe 路径与工作目录正确 |
| 停止后仍显示运行中 | pid 被复用；`tasklist /FI "PID eq <pid>"` 核对，必要时删除 `data\sessions\<id>.pid` |

---

## 6. 相关文件

| 路径 | 作用 |
|---|---|
| `portal/src/main/java/io/github/sander_i/platform/` | 平台抽象、探测实现、命令执行器 |
| `portal/src/main/java/io/github/sander_i/service/Session*.java` | 会话门面与两套后端 |
| `portal/frontend/src/views/SettingsView.vue` | 环境信息页渲染 |
| `deploy/win/install.ps1` | Windows 首次部署 |
| `deploy/win/galworld.ps1` | Windows 游戏注册 CLI |
| `deploy/win/uninstall.ps1` | 卸载（默认保留游戏与存档） |
