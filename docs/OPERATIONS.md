# 部署与运维

## Linux 部署细节

```bash
git clone https://gitee.com/sander-o/gal-world.git
cd gal-world
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
由 systemd `EnvironmentFile` 读取，之后重跑不必再传。证书口令同样落在
`~/.config/galworld/creds.env`（0600），单元文件里不出现明文。

`deploy.sh` 还会装一个固定提权入口 `/usr/local/sbin/galworld-sudo` 与对应的
`/etc/sudoers.d/galworld-deploy` 白名单（只放行 packages / snd-aloop / audio-group / linger /
uninstall-sys / uninstall-packages 六个子命令），后台「一键部署」因此不需要在页面输 sudo 密码。
不想装就加 `--no-sudoers`，退回页面输密码的方式。

## 彻底卸载

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

## Windows 部署

```powershell
# 管理员 PowerShell
.\deploy\win\install.ps1 -Jar ..\portal\target\gal-world-portal-<version>.jar
.\deploy\win\galworld.ps1 add sample-game --name "示例游戏" --exe "D:\Games\sample-game\game.exe"
```

Sunshine、Moonlight Web、虚拟声卡的前置条件与配对步骤见 [`ENVIRONMENT.md`](ENVIRONMENT.md)。

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
