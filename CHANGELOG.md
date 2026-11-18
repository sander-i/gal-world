# 更新日志（Changelog）

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)，与 `portal/pom.xml` 的
`<version>` 保持一致，tag 名为 `v<version>`。

## [Unreleased]

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

[Unreleased]: https://gitee.com/sander-o/gal-world/compare/v0.0.2...master
[0.0.2]: https://gitee.com/sander-o/gal-world/releases/tag/v0.0.2
[0.0.1]: https://gitee.com/sander-o/gal-world/releases/tag/v0.0.1
