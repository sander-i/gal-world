# 更新日志（Changelog）

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)，与 `portal/pom.xml` 的
`<version>` 保持一致，tag 名为 `v<version>`。

## [Unreleased]

### 新增

- **语言包市场直链安装**：后台「语言包」新增「从地址安装」，填 Crowdin / Weblate 导出链接、
  GitHub / Gitee raw 或静态文件直链即可，服务器拉取后按扩展名走与上传相同的导入链路。
  只接受 http / https 公网地址，回环与私网（含 CGNAT、保留段、IPv6 ULA）一律拒绝，
  重定向自己跟且每跳重新校验，体积上限 2 MB。内网部署用
  `galworld.i18n.market-allow-hosts` 或 `market-allow-private` 放行
- **远程更新源可配置**：管理页新增「远程更新源」，可改发布接口根地址、仓库路径与仓库网页根地址
  （也可用 `GALWORLD_UPDATE_API_BASE` / `GALWORLD_UPDATE_REPO` / `GALWORLD_UPDATE_WEB_BASE`）。
  接口兼容 GitHub / Gitee 的 Release API，连不上默认站时可指向 Gitee、自建 Gitea / GitLab 或镜像；
  Portal 把当前地址写进 `update-request.json` 的 `releaseApi`，更新器进程据此走同一来源，
  不再依赖自身环境变量
- **存档切换**：详情页「存档管理与切换」可把任意备份切换为当前存档；切换前自动快照当前进度
  （`pre-restore`），切错可再切回。游戏运行中拒绝切换
- **备份描述**：备份时可填写标签与描述，列表展示并支持行内编辑（sidecar 元数据，老备份自动兼容）
- **原始存档留底**：游戏接入完成后自动把自带的存档备份为「原始」类型；重复接入不重复创建
- **游戏打包下载**：新增两步式下载。先异步打包游戏目录（可轮询进度），完成后再下载 zip；
  不含门户生成的 `game.env` 与 `wineprefix/`，产物保留 24 小时后自动清理

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

[Unreleased]: https://gitee.com/sander-o/gal-world/compare/v0.0.1...master
[0.0.1]: https://gitee.com/sander-o/gal-world/releases/tag/v0.0.1
