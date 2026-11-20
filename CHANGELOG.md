# 更新日志（Changelog）

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)，与 `portal/pom.xml` 的
`<version>` 保持一致，tag 名为 `v<version>`。

## [Unreleased]

## [0.0.4] - 2026-11-20

### 新增

- **帧率可配**：`FRAMERATE` 支持 `60,30-60` 三段式（初始值 + 范围），
  Ctrl 快速跳过对话时不再被锁在 30 fps；接入校验接受该格式
- **码率控制模式可配**：`game.env` 可写 `RATE_CONTROL_MODE=cbr`，
  用 QP 上限压住快速跳过时的瞬时峰值（默认仍是 `crf`，保持既有画质行为）

### 改进

- **串流桥接改为有界队列**：门户 → 浏览器方向超预算时丢最旧的视频帧，
  让客户端的 `LOST_FRAME` / `REQUEST_KEYFRAME` 自愈。此前队列无界，
  浏览器消化不过来时延迟会无限累积
- **窗口自动放大**：游戏窗口面积不足画布一半时按画布放大
  （Kirikiri 等引擎按自身默认值开窗，忽略 Xvfb 屏幕大小）
- 去掉首页假滚动条，滚动条样式统一走主题变量

### 修复

- **JDK 在线升级导致门户子进程失效**：`unattended-upgrades` 在门户运行中替换
  `openjdk-21-jre-headless` 后，内存镜像与磁盘上的 `jspawnhelper` 版本不匹配，
  所有 `fork()` 失败，`systemctl` 调不到、会话状态永远是 `UNKNOWN`、
  启动进度条卡 55%。三层修复：新增 `JdkDriftWatchdog` 检测并退出让 systemd
  重启；前端对 `UNKNOWN` 超时判失败并给出可操作提示；部署脚本写 apt 黑名单
  从源头挡住自动升级 JDK
- **画布自动校准越缩越差**：`CanvasPlanner` 假设「画布 ≈ 窗口 + 24 最优」，
  读到小窗口后把画布缩小，窗口并不因此变大，结果画布从 1008×616 掉到
  520×352、画质持续下降。新增 `keep-large` 分支，窗口不足画布一半时保持大画布

### 升级说明

无需迁移数据库。已部署实例建议重新执行一次 `deploy.sh` 或页面「一键部署」，
让 apt 黑名单与新的 `run-game.sh` 生效；不重新部署也不会出错，只是缺少
JDK 自动升级豁免与窗口自动放大。

## [0.0.3] - 2026-11-19

### 新增

- **首屏骨架**：`index.html` 在 Vue 挂载前渲染启动骨架；新增通用骨架块，首页、列表、详情、
  设置与管理表格按真实布局显示加载态；卡片图标延迟加载
- **请求并行**：设置只请求一次并在页面间共享；首页 Hero 与推荐并行；列表元数据与首页列表并行；
  详情页存档、时长、设置与下载查询并行
- **页面跳转反馈**：点击缩放、悬停预取路由 chunk、80 ms 延迟进度条、180 ms 新页淡入、
  骨架按视觉行错峰入场
- **滚动恢复**：浏览器前进/后退补 `scrollBehavior`；返回游戏列表时在首屏数据渲染后
  再对齐离开时的位置
- **主题**：默认主题改为「素纸」；自定义主题支持在卡片上单独删除；`theme-color` 跟随当前主题

### 改进

- 版本号只保留在 `portal/pom.xml`；`package.json`、README 与发布脚本不再重复维护
- 设置读取改为模块级缓存，设置页保存后立即覆盖缓存

### 修复

- 预取逻辑拆到独立模块，打断 router ↔ 视图的循环依赖
- 进度条状态改为普通对象并由组件注入响应式代理，修复进度条不更新的问题
- 滚动恢复不再读取 vue-router 内部 `history.state`
- 进度条淡出期间开始新导航时不再出现宽度回跳
- 正在使用的自定义主题拒绝删除，避免界面在用户没预期时换色

### 升级说明

无需迁移数据库或执行额外步骤。已保存主题的本机不受默认主题变更影响；正在使用的
自定义主题需先切换到其它主题才能删除。

## [0.0.2] - 2026-11-18

### 新增

- **多语言界面**：内置 English / 简体中文 / 繁體中文 / 日本語；后台支持安装外部语言包，
  可导入 .po、XLIFF、CSV、properties、Android XML、.arb、.strings 等格式
- **语言包市场直链安装**：后台「语言包」可从 Crowdin / Weblate 导出链接、GitHub / Gitee raw
  或静态文件直链安装；只允许公网 HTTP(S)，逐跳校验重定向，文件上限 2 MB
- **按语言配置元数据**：公司、标签、汉化组可分别设置多语言名称与简介
- **远程更新源可配置**：管理页可修改 Release API 地址、仓库路径与网页根地址；支持 GitHub、
  Gitee、自建 Gitea / GitLab 或镜像
- **存档切换与描述**：可将备份切换为当前存档，切换前自动快照当前进度；备份支持标签和描述
- **原始存档留底**：接入完成后自动备份游戏自带存档，重复接入不重复创建
- **游戏打包下载**：异步打包游戏目录并显示进度；不含门户生成的 `game.env` 与 `wineprefix/`，
  产物保留 24 小时
- **首页会话批量刷新**：新增 `/api/sessions?ids=`，运行中游戏状态按 1 秒轮询

### 性能优化

- 首页路由懒加载、Element Plus 样式按需引入：入口 JS 491 KB → 161 KB，CSS 434 KB → 110 KB
- 开启 gzip 与 HTTP/2；设置和语言包支持 ETag 缓存
- 列表、推荐、Hero 改用批量查询，减少数据库 N+1
- 游戏扫描跳过未变化的 `game.env` 与媒体目录；列表仅返回封面
- 全部游戏卡片显示启停进度；跨页面返回首页后状态刷新间隔缩为 1 秒

### 修复

- 修复语言切换后界面文案与提示词不刷新的问题
- 修复多语言文案变长时标签压字、筛选行错位
- 修复 SPA 路由缺失导致直接访问与刷新返回 404
- 修复前端 `t()` 多参数占位符残留
- 补齐管理页与文件选择器的翻译，并修复部署产物版本选择和页面标题语言

### 实测

本机 Windows + H2 + 40 款构造游戏：`GET /api/games?limit=40` 从 1004 条 SQL 降到 13 条，
推荐接口从 579 条降到 12 条。生产 Linux 实机验证了批量会话 RUNNING / STOPPED 状态映射。
完整环境、测量边界与取舍见 `docs/PERF-PLAN.md` §8。

## [0.0.1] - 2026-11-15

首个公开版本。功能范围以自用需求为准，Linux 与 Windows 两种部署形态。

### 修复

- **一键部署补上运维脚本**：新增「部署门户运维脚本」步骤，`updater.sh` / `backup.sh` /
  `galworld` / `gen-stream-cert.sh` 随 jar 落盘到 `~/galworld/bin/`。以前这些脚本只能靠
  开发机的 `deploy/portal-deploy.sh` 推送，按 README 新装的机器上缺 `updater.sh`，
  点「检查更新」会直接报“更新器不存在”
- `tools/diagnostics/persist-audit.sh` 的 `loginctl show-user <user>` 占位符导致 bash 语法错误，
  改为 `$(id -un)`
- `run-game.sh` 启动日志里的 `<host>` 字面量改为本机实际 IPv4（取不到则回退 `localhost`）
- 移除对未发布文档 `docs/OPERATIONS.md` 的引用

### 新增

- **门户**：游戏库、Hero 推荐位、为你推荐、公司 / 汉化组 / 标签筛选、搜索、卡片快捷启停
- **浏览器游玩**：Linux 走 Selkies + Wine + PulseAudio + snd-aloop；Windows 走 Sunshine + Moonlight Web
- **多游戏并存**（Linux）：每游戏独立 wine prefix、Xvfb 显示号、ALSA 子设备、音频链与端口
- **会话保持**：关闭浏览器不终止游戏，回到页面继续；心跳计时与空闲自动停止
- **接入向导**：浏览器分片上传 zip / 7z 或整个目录（可续传），或指定服务器已有目录；
  自动注册、初始化 wine 前缀、装中文字体、首次启动验证
- **元数据**：公司 / 汉化组（别名、网址、简介）、标签（别名、颜色），拖拽排序、整维度启停
- **媒体管理**：封面、图标、宣传 CG（图片或视频），多图轮换、拖拽排序、灯箱预览
- **图片预加载**：按显示尺寸生成派生图，浏览器按 `srcset` 选档
- **存档备份 / 恢复**：运行中拒绝恢复、恢复前快照、同分区临时目录、重命名替换 + 回滚、归档路径校验
- **游玩体验**：画面校准（自动 / 手动）、启动进度、时延显示、游玩时长统计、崩溃遥测
- **一键部署 / 彻底卸载**：后台按步骤执行并实时回传日志，破坏性步骤二次确认；两端都保留
  门户本体、数据库、证书、游戏与存档
- **远程更新**：Release 资产 + 独立 updater，SHA256 校验、停服备份、健康检查、失败回滚；
  可选覆盖更新（指定任意已发布 tag，允许降级）
- **主题**：四套内置主题 + 自定义主题编辑器（本机保存）
- **数据导出**：`GET /api/export` 导出游戏、标签、设置与会话数的 JSON

### 已知限制

见 `README.md` 的「已知限制」：Wine 下 4:3 渲染、无 GPU 时软渲染 + 软编码、
Wine 自绘标题栏、必须走 HTTPS、Windows 冒烟检查覆盖面小于 Linux。

[Unreleased]: https://gitee.com/sander-o/gal-world/compare/v0.0.4...master
[0.0.4]: https://gitee.com/sander-o/gal-world/releases/tag/v0.0.4
[0.0.3]: https://gitee.com/sander-o/gal-world/releases/tag/v0.0.3
[0.0.2]: https://gitee.com/sander-o/gal-world/releases/tag/v0.0.2
[0.0.1]: https://gitee.com/sander-o/gal-world/releases/tag/v0.0.1
