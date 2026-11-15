# Gal World

自建云游戏平台：把 Windows 视觉小说（galgame）部署到 Linux 或 Windows 主机，通过浏览器串流游玩。
门户负责游戏库、元数据、会话、媒体、存档与运维，串流链路由 Selkies（Linux）或 Sunshine（Windows）承担。

**版本**：`0.0.1`（版本号唯一来源是 `portal/pom.xml` 的 `<version>`；`application.yml`
通过 `@project.version@` 注入，其余位置不重复声明）。

项目以 [MIT 许可证](LICENSE) 开源。它是按个人需求长出来的自建方案，不是通用商业产品；
欢迎自建部署、提 issue 与 PR，但不承诺开箱即用或向后兼容。

## 界面

![游戏库：公司 / 标签筛选与卡片快捷启停](docs/images/portal-library.png)

| 游戏详情：左侧信息与操作、右侧 CG 大屏 | 游戏编辑：元数据、封面与 CG 管理 |
|---|---|
| ![游戏详情](docs/images/portal-game.png) | ![游戏编辑](docs/images/portal-edit.png) |

截图取自本地运行的门户，游戏、公司与封面均为示例数据（占位图，不含任何作品素材）。

## 能力

- **浏览器游玩**：Linux 走 Selkies 2.1（WebRTC/WebSocket），Windows 走 Sunshine + Moonlight Web
- **多游戏并存**：Linux 下每游戏独立 wine prefix、独立 Xvfb 显示、独立 ALSA 子设备、独立音频链与端口
- **断线不掉**：关闭浏览器不终止游戏会话，回来继续
- **游戏库门户**：Hero 推荐位、为你推荐、公司 / 汉化组 / 标签筛选、搜索、卡片快捷启停
- **接入向导**：浏览器上传 zip / 7z 或整个文件夹（分片、可续传），也可选服务器已有目录；
  自动注册、初始化 wine 前缀、安装中文字体、首次启动验证
- **元数据**：公司 / 汉化组（别名、网址、简介）、标签（别名、颜色），拖拽排序、整维度启停
- **媒体管理**：封面、图标、宣传 CG（图片或视频），多图轮换、拖拽排序
- **图片预加载**：按显示尺寸生成派生图，浏览器按 `srcset` 选档，先出画面再补清晰度
- **游玩体验**：心跳计时、空闲自动停止、画面校准（自动 / 手动）、启动进度、时延显示、崩溃遥测
- **游玩时长统计**：只在游玩页可见时计时（20 秒心跳），挂机不计
- **存档管理与切换**：备份可填标签与描述，任意备份一键切换为当前存档；切换前自动快照。
  五道安全保护（运行中拒绝切换、切换前快照、同分区临时目录、重命名替换 + 回滚、归档路径校验）；
  接入游戏时自动留一份「原始」存档
- **游戏打包下载**：详情页两步操作，先把游戏目录打包成 zip，完成后再下载；不含门户生成的
  `game.env` 与 `wineprefix/`
- **一键部署 / 彻底卸载**：后台按预置步骤执行环境部署或卸载（Linux / Windows），实时回传日志，危险步骤二次确认。
  卸载会清到只剩门户可运行，切换其它环境方案时不留残留：
  Linux 删 Wine / Selkies venv / 音频与游戏单元 / 元件缓存，purge xvfb、PulseAudio、python3-venv、
  中文字体与全部 i386 包并撤销 i386 架构；Windows 删 Moonlight Web、Sunshine 服务与残留目录、
  虚拟声卡驱动与元件缓存。两端都保留门户本体、数据库、证书、游戏与存档，Linux 另留 JDK、Windows 留 Java
- **远程更新**：Gitee Release 资产 + 独立 updater，SHA256 校验、停服备份、健康检查、失败回滚
- **主题**：四套内置主题 + 自定义主题编辑器（本机保存）
- **配置快照**：`backup.sh` 带健康校验，故障时先恢复再排查
- **数据导出**：`GET /api/export` 导出游戏、标签、设置与会话数的 JSON

## 架构

```text
浏览器
  │
  ├── HTTPS :8088 ──> Portal（Spring Boot + Vue，单 jar）
  │                     ├── H2 文件库
  │                     ├── 会话控制 ──> systemd user unit / 本机进程
  │                     └── 媒体、存档、备份、更新
  │
  └── HTTPS :9002/:9003 ──> Selkies（Linux）
                              │ X11 采集
                           Xvfb :2 / :3
                              │
                           wine（每游戏独立 WINEPREFIX）
                              │ ALSA hw:0,0,<slot>
                        snd-aloop 虚拟声卡
                              │ 采集 hw:0,1,<slot>
                     PulseAudio source gal_<id>_in
                              │ module-loopback
                     PulseAudio sink gal_<id>
                              │ monitor
                        pcmflux → Opus → 浏览器
```

Windows 形态把最后一段换成 Sunshine 捕获桌面并硬件编码（NVENC / QSV / AMF），
浏览器通过 Moonlight Web 解码播放；Portal 与游戏同机运行，游戏是原生 exe，不经 Wine。

**技术栈**

| 层 | 选型 |
|---|---|
| 后端 | Java 17、Spring Boot 3.3.5、Spring Data JPA、H2 文件模式 |
| 前端 | Vue 3.5、Vue Router 4、Element Plus 2.14、Vite 6 |
| Linux 串流 | Ubuntu 24.04、Selkies 2.1、Wine 9.0、PulseAudio 16.1、Xvfb、snd-aloop |
| Windows 串流 | Sunshine、Moonlight Web、VB-Audio Virtual Cable |
| 编码 | 服务器无 GPU：llvmpipe 软渲染 + x264 软编码；Windows 走硬件编码 |

## 目录

```text
portal/
├── src/main/java/io/github/sander_i/   后端：web / service / domain / platform / deploy / update
├── src/test/java/                后端测试
└── frontend/                     Vue 3 前端源码（构建产物进 src/main/resources/static/）

deploy/
├── bin/                          服务器端脚本（run-game.sh、galworld CLI、backup.sh、updater.sh 等）
├── systemd/                      systemd user 单元
├── win/                          Windows 部署脚本（install / galworld / update-portal / uninstall）
├── openbox/                      窗口管理器配置（备选方案，当前未启用）
├── portal-deploy.sh              本机构建 → 上传 jar → 重启 → 健康检查
└── release.sh                    构建并发布 Gitee Release

docs/
├── ENVIRONMENT.md                环境探测与 Windows 部署方案
├── GAME-IMPORT-PLAN.md           游戏接入方案
└── *.md                          其余设计与优化方案

tools/
├── artifacts/                    第三方元件清单与同步脚本（Nexus + 镜像回退 + SHA256）
└── diagnostics/                  排障用的一次性脚本（音频逐跳验证、冷启动模拟等）

preview/                          静态界面原型（纯 HTML，无需构建）
demo/                             演示素材
deploy.sh                         Linux 一键引导脚本（系统依赖 → JDK → 证书 → 服务 → 健康检查）
uninstall.sh                      彻底卸载脚本（连门户、数据、游戏与提权入口一起清）
```

## 快速开始

### 环境要求

| 项 | 要求 |
|---|---|
| JDK | 17+（后端编译目标 17） |
| Node | 20+（前端构建） |
| Maven | 3.9+ |
| Linux | Ubuntu 22.04+ / 24.04，Wine 9.0、PulseAudio、snd-aloop、Xvfb、Selkies |
| Windows | Windows 10 1903+ / 11（x64），Sunshine、Moonlight Web、虚拟声卡 |
| 权限 | Linux 需 `loginctl enable-linger`；Windows 安装需管理员一次 |

### 构建

```bash
# 前端构建进 portal/src/main/resources/static/
cd portal/frontend
npm ci
npm run build

# 打单 jar
cd ..
mvn -DskipTests package
# 产物：portal/target/gal-world-portal-<version>.jar（版本取自 portal/pom.xml）
```

### Linux 部署

```bash
git clone https://gitee.com/sander-o/gal-world.git
cd gal-world

# deploy.sh 自动找 portal/target 下的 jar；也可 --jar 指定
./deploy.sh
```

`deploy.sh` 覆盖基础环境的一次性准备：系统依赖（apt）→ JDK → 目录 → jar → 自签证书 →
systemd user 单元 → linger → 防火墙 → 健康检查。可重复执行，已装好的部分会跳过。
系统依赖只在 Debian/Ubuntu 上自动安装，其它发行版会提示后跳过；已自行准备好环境时可用
`--skip-deps` 跳过这一步。Wine / Selkies / 音频链等中间件仍由后台「环境信息 → 一键部署」执行。

访问 `https://<host>:8088/`。`deploy.sh` 会把一个固定部署令牌写进 systemd 单元，
脚本结束时会打印出来（重启不变）：

```bash
部署令牌: 7f3c...
```

想自己指定就用 `--token <令牌>` 或环境变量 `GALWORLD_DEPLOY_TOKEN`；
只有绕过 `deploy.sh` 手工起 jar 时，令牌才会随机生成并只出现在启动日志里：

```bash
journalctl --user -u galworld-portal -n 200 --no-pager | grep '随机令牌'
```

把它填到「管理」页即可解锁环境信息、一键部署与远程更新。

元件仓库地址（Nexus）不指定时只探测本机 `127.0.0.1:8081`；`manifest.json` 能取到就写进单元，
探测不到会在结尾告警，用 `--artifact-nexus` 或 `GALWORLD_ARTIFACT_CANDIDATES` 指定即可。

Nexus 需要账号时，用 `--artifact-user` + `--artifact-pass`，或把密码写进文件后传
`--artifact-pass-file <path>`。账号密码统一落到 `~/.config/galworld/artifacts.env`（0600），
由 systemd `EnvironmentFile` 读取，之后重跑不必再传。

`deploy.sh` 还会装一个固定提权入口 `/usr/local/sbin/galworld-sudo` 与对应的
`/etc/sudoers.d/galworld-deploy` 白名单（只放行 packages / snd-aloop / audio-group / linger /
uninstall-sys / uninstall-packages 六个子命令），后台「一键部署」因此不需要在页面输 sudo 密码。
不想装就加 `--no-sudoers`，退回页面输密码的方式。

### 彻底卸载

```bash
./uninstall.sh --dry-run   # 先看会删什么
./uninstall.sh             # 执行，要求手打「销毁」确认
```

与后台「一键卸载环境」不同：页面卸载只清到门户还能跑（用于切环境），
`uninstall.sh` 连门户一起销毁——单元、`~/galworld`（含游戏与存档）、
`~/.config/galworld`、上传的 jar/脚本、系统运行时包与 i386 架构、linger、
ufw 放行、提权入口与 sudoers 白名单。不可逆。

`--keep-data` 保留 `data/` 与 `games/`，`--keep-packages` 跳过系统包卸载，
`--yes` 跳过交互确认。清理干净后脚本会删掉自己；有残留时保留，便于重跑排查。

### Windows 部署

```powershell
# 管理员 PowerShell
.\deploy\win\install.ps1 -Jar ..\portal\target\gal-world-portal-<version>.jar
.\deploy\win\galworld.ps1 add sample-game --name "示例游戏" --exe "D:\Games\sample-game\game.exe"
```

Sunshine、Moonlight Web、虚拟声卡的前置条件与配对步骤见 [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md)。

### 开发

```bash
# 后端 :8080（前端 dev 代理指向这里）
cd portal
GALWORLD_PORTAL_PORT=8080 mvn spring-boot:run

# 另开终端，前端 :5173
cd portal/frontend
npm ci
npm run dev
```

测试：

```bash
cd portal && mvn test
cd portal/frontend && npm test && npm run check:theme
```

## 门户

| 路径 | 内容 |
|---|---|
| `/` | 游戏库：Hero 推荐位、为你推荐、公司 / 汉化组 / 标签筛选、搜索 |
| `/games` | 全部游戏 |
| `/games/import` | 接入向导：上传压缩包 / 文件夹或选择服务器目录 |
| `/game/<id>` | 详情：启停 / 重启、时长统计、元数据、封面图标、存档管理与切换、游戏打包下载 |
| `/game/<id>/edit` | 编辑：名称、画布、公司 / 汉化组 / 标签、简介、封面 / 图标 / CG、轮换规则 |
| `/play/<id>` | 游玩：iframe 串流、心跳计时、画面校准、遥测 |
| `/settings` | 外观主题、功能开关、切换方式、画面校准、空闲停止、CG 声音、轮换、令牌、关于 |
| `/admin` | 访问令牌、上传限制、图片预加载优化、环境信息与一键部署、版本与检查更新 |
| `/companies` | 公司维护 |
| `/localization-groups` | 汉化组维护 |
| `/tags` | 标签维护 |

**必须 HTTPS**：游玩页用 iframe 内嵌 Selkies，而 Selkies 客户端用 WebCodecs，
要求 `window.isSecureContext === true`。HTTP 父页面里嵌 HTTPS iframe 仍判为非安全上下文。

### 游玩时长口径

只在**游玩页可见**时计时（20 秒心跳）。切到别的标签、最小化窗口、离开页面都会停止计时，
45 秒内自动结算。因此挂机不计入时长。

### 画面校准

Wine 会按游戏自身协商窗口大小，有时会顶到画布边缘导致画面被裁。
游玩页进入后（自动模式）或点右上角「校准分辨率」（手动模式）读取 `xwininfo` 窗口矩形，
把 `WIDTH/HEIGHT` 调整为约 `窗口 + 24`，最多 4 轮，取窗口面积最大的画布。
全局设置 →「画面校准」可切换自动 / 手动。

### 媒体与轮换

每个游戏可上传多张封面、一个图标、多段宣传 CG（图片或短视频）。
封面与 CG 各自配置顺序 / 随机轮换与间隔；CG 可设置自动播放下一段、循环或停在当前。
视频默认静音，可在设置里改为默认有声。
图片会生成 640 / 1024 两档派生图（源图不超过 1024 时不降分辨率），由浏览器按显示尺寸选择。
默认上限：封面 8 个、CG 5 个、图片 8 MB、视频 120 MB，可在「管理 → 上传限制」调整。

## 配置

常用环境变量（完整项见 `portal/src/main/resources/application.yml`）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `GALWORLD_ROOT` | `~/galworld` | 安装根目录（games / data / lib） |
| `GALWORLD_PORTAL_PORT` | `8088` | 门户端口 |
| `GALWORLD_TLS` | `false` | 是否启用 HTTPS；`deploy.sh` 默认设为 `true` |
| `GALWORLD_KEYSTORE` | 空 | PKCS12 证书路径 |
| `GALWORLD_KEYSTORE_PASSWORD` | `changeit` | 证书密码 |
| `GALWORLD_KEY_ALIAS` | `galworld` | 证书别名 |
| `GALWORLD_STREAM_URL` | 空 | Windows 下固定的 Moonlight Web 串流地址 |
| `GALWORLD_WRITE_TOKEN` | 空 | 写接口令牌；留空不校验 |
| `GALWORLD_DEPLOY_TOKEN` | 空 | 部署 / 后台令牌；留空启动时随机生成并打印日志 |
| `GALWORLD_DEPLOY_CIDRS` | `127.0.0.1/32,192.168.0.0/16,10.0.0.0/8` | 部署接口允许的网段 |
| `GALWORLD_DEPLOY_ALLOW_SUDO_PASSWORD` | `false` | 是否允许页面输入 sudo 密码 |
| `GALWORLD_UPDATE_ENABLED` | `true` | 是否允许远程更新 |
| `GALWORLD_UPDATE_ALLOW_OVERRIDE` | `false` | 管理页允许指定 tag / 版本覆盖更新（含降级） |
| `GALWORLD_UPDATE_API_BASE` | `https://gitee.com/api/v5/repos` | 更新源发布接口根地址；连不上默认站时指向镜像或自建实例 |
| `GALWORLD_UPDATE_REPO` | `sander-o/gal-world` | 更新源仓库路径 `owner/repo` |
| `GALWORLD_UPDATE_WEB_BASE` | `https://gitee.com` | 更新源仓库网页根地址，用于拼发布页链接 |
| `GALWORLD_UPDATE_TOKEN` | 空 | 私有仓库 / 镜像的只读令牌；Linux 更新器也可从 `~/.config/galworld-update.env` 读取 |
| `GALWORLD_ARTIFACT_NEXUS` | 本机 Nexus | 一键部署元件下载源 |
| `GALWORLD_ARTIFACT_USER` / `GALWORLD_ARTIFACT_PASS` | 空 | 元件仓库账号；留空匿名读取，私有仓库再配置 |

设置项存在 H2 `setting` 表，由设置页 / 管理页修改。

## 部署与更新

```bash
# 开发机构建后推送到服务器（SSH 兜底路径）
bash deploy/portal-deploy.sh
bash deploy/portal-deploy.sh --build-only   # 只构建

# 构建并发布 Gitee Release
GITEE_TOKEN=<token> bash deploy/release.sh
bash deploy/release.sh --dry-run            # 只构建并生成 dist/，不发布
```

门户内「管理 → 版本与检查更新」可检查并执行远程更新。
更新器是独立进程，流程为：下载 → SHA256 / zip / 版本校验 → 停服 → 备份 jar 与 H2 →
原子替换 → 启动 → 健康冒烟 → 失败自动回滚。

`GALWORLD_UPDATE_ALLOW_OVERRIDE=true` 时管理页出现「覆盖更新」，可指定任意
已发布 Release 的 tag 或版本（如 `v<version>-pr7`）强制安装，允许降级与同版本重装。
默认关闭。目标版本必须自带更新接口，更新器会在停服前检查 jar，不含更新器的版本直接拒绝。

分支验证流程：`GALWORLD_RELEASE_TAG=v<version>-pr7 bash deploy/release.sh` 发布分支包，
再在管理页填该 tag 覆盖安装。

**更新源可配置。**「管理 → 远程更新源」可改发布接口根地址、仓库路径与仓库网页根地址，
也可以直接设 `GALWORLD_UPDATE_API_BASE` / `GALWORLD_UPDATE_REPO` / `GALWORLD_UPDATE_WEB_BASE`。
接口只需兼容 GitHub / Gitee 的 Release API（`/repos/{owner}/{repo}/releases`），
所以连不上 GitHub 时可以指向 Gitee、自建 Gitea / GitLab 或镜像站。改完立即生效，
更新器进程会从 `update-request.json` 的 `releaseApi` 读到同一个地址，不需要重启或单独设环境变量。
私有仓库的只读令牌走 `GALWORLD_UPDATE_TOKEN`（Linux 更新器也接受
`~/.config/galworld-update.env` 里的 `GITEE_TOKEN=` 或 `UPDATE_TOKEN=`，文件权限需 600）。

注意：门户内更新只替换 jar 与 H2，不更新 `~/galworld/bin/*`。更新器脚本本身有改动时，
升级后在页面「管理 → 一键部署」跑一遍「部署门户运维脚本」，它会从 jar 里重新落盘
`updater.sh` / `backup.sh` / `galworld` / `gen-stream-cert.sh`；也可以直接在开发机执行
`bash deploy/portal-deploy.sh`。否则服务器上仍在跑旧脚本。

Windows 与 Linux 同级：下载并校验 jar → 停服 → 备份 jar 与 H2 → 原子替换 → 启动 →
按版本冒烟 → 失败自动回滚。更新器脚本随 jar 分发，运行时释放到 `data\update\`；
手工用法 `deploy\win\update-portal.ps1 -Jar <jar>` 保留。

## 自检与恢复

```bash
~/galworld/bin/selfcheck.sh      # 全栈自检
~/galworld/bin/backup.sh backup  # 配置快照（含健康校验）
~/galworld/bin/backup.sh restore # 故障时先跑这个

bash tools/diagnostics/m4-check.sh   # 门户验收（在仓库目录里跑，不随部署安装）
```

`selfcheck.sh` / `backup.sh` / `galworld` CLI 由页面「一键部署」的「部署音频脚本」
与「部署门户运维脚本」两步从 jar 装到 `~/galworld/bin/`；不想开页面时，
`bash deploy/portal-deploy.sh` 也会把 `deploy/bin/*` 一并推上去。

运维手册属于内部文档，不随仓库分发。

## 已知限制

| 项 | 现状 |
|---|---|
| 画质 | Wine 下按 4:3 渲染，低于 Windows 原生 16:9；服务器无 GPU，软渲染 + 软编码 |
| 窗口标题栏 | Wine 自绘 30px 无法去除，采用居中放置规避 |
| 并发 | 每路约 1–2 核编码；32 线程实测 2 路稳定 |
| HTTPS | 门户必须 HTTPS，否则游玩页黑屏并报 `This application requires a secure connection` |
| 远程更新 | Linux 与 Windows 均支持；Windows 无 journal 等价检查，冒烟只覆盖版本与三个 HTTP 接口 |
| 平台差异 | Linux 每游戏独立端口与音频链；Windows 共用 Moonlight Web 入口 |

## 文档

- [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md)：环境探测、Linux / Windows 部署方案
- [`docs/GAME-IMPORT-PLAN.md`](docs/GAME-IMPORT-PLAN.md)：游戏接入向导设计
- [`CONTRIBUTING.md`](CONTRIBUTING.md)：开发环境、构建与测试、提交约定
- [`SECURITY.md`](SECURITY.md)：安全模型、默认值与漏洞上报
- [`CHANGELOG.md`](CHANGELOG.md)：版本变更记录

运维手册、一键部署执行细节与远程更新实测记录属于内部文档，不随仓库分发。

## 许可

本项目以 [MIT 许可证](LICENSE) 开源，版权归 sander 所有。

游戏本体、汉化补丁、游戏内素材的版权归原作者与汉化组，不包含在本仓库中。
随发布 jar 分发的第三方组件（Spring、Hibernate、Logback、H2、Vue、Element Plus 等）
及其许可证全文见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) 与 [`licenses/`](licenses/)，
这两份文件也会打进 jar 的 `META-INF/` 并随 Release 资产一并提供。

部署期由脚本从上游获取、以独立进程运行的组件（Sunshine、Moonlight Web、Wine、Selkies、VB-Cable）
遵循各自许可证，不链接进本项目代码。本项目的元件镜像仅供部署者内网自用，不对外提供下载。

---

用 AI 编码代理（Claude Code / Codex 等）改动本项目时，先读 [`AGENTS.md`](AGENTS.md)：
里面写了架构边界、版本号唯一来源、哪些脚本会改系统状态。
