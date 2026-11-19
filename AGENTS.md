# AGENTS.md

面向 AI 编码代理的项目说明。人类读者请先看 `README.md`（架构、目录、快速开始），
环境搭建见 `docs/ENVIRONMENT.md`。

## 这是什么

Gal World 是**自建云游戏平台**：把 Windows 视觉小说（galgame）部署到 Linux 或 Windows 主机，
通过浏览器串流游玩。

两个进程角色：

- **Portal**（Spring Boot + Vue，打成一个 jar）——游戏库、元数据、会话、媒体、存档、部署与更新。
- **串流链路**——Linux 走 Selkies + Wine + PulseAudio + snd-aloop；Windows 走 Sunshine + Moonlight Web。

Portal 与串流链路解耦：Portal 负责起停与编排，实际画面/音频由串流端承担。

版本号唯一来源是 `portal/pom.xml` 的 `<version>`，`application.yml` 用 `@project.version@`
注入，别处不要硬编码。

## 仓库结构

```
portal/                       Spring Boot 后端 + Vue 前端（单 jar 交付）
  pom.xml                     版本号唯一来源 + spring-boot-starter-parent
  src/main/java/io/github/sander_i/
    domain/                   JPA 实体
    repo/                     仓储
    service/                  业务编排（游戏导入、会话、存档、部署、媒体…）
    web/                      控制器与 API
    security/                 鉴权、访问令牌、部署接口的 CIDR 限制
    update/                   远程更新（读 release manifest、校验、调用更新器）
    deploy/                   一键部署/卸载的步骤编排
    platform/                 平台差异（Linux / Windows）
  src/main/resources/
    application.yml           配置；galworld.version 用 @project.version@
    static/                   前端构建产物（npm run build 生成；gitignore，不入库）
  frontend/                   Vue 3 + Element Plus + Vite
    src/                      页面与组件
    scripts/check-theme-vars.mjs  主题令牌门禁
deploy/                       部署脚本
  release.sh                  打包发布物 + 生成 release-manifest.json
  portal-deploy.sh            本机构建 → 上传 jar → 重启 → 健康检查
  bin/                        目标机上的运行脚本（保活、音频、游戏启停、更新器）
  win/                        Windows 安装/卸载/更新脚本
  systemd/                    systemd user unit 模板
deploy.sh                     Linux 一键引导（系统依赖 → JDK → 证书 → 服务 → 健康检查）
uninstall.sh                  彻底卸载
tools/                        元件构建与诊断脚本
docs/                         环境搭建与各模块方案（接入、媒体、界面、优化）
```

## 铁律（改动前先读）

1. **版本号只写一处。** `portal/pom.xml` 的 `<version>` 是唯一来源；`application.yml`
   用 `@project.version@`、`release.sh` 从 pom 读。不要在任何脚本、文档或
   `frontend/package.json` 里重复当前版本；前端要展示版本就读 `/api/health`。
   发布流程里的 `MIN_FROM_VERSION`（升级下限）是兼容性策略值，不是产品版本。

2. **`deploy.sh` 只做基础环境。** 系统依赖、JDK、目录、jar、证书、systemd、linger、防火墙、
   健康检查。Wine / Selkies / Sunshine / 音频链一律走页面「一键部署」，不要塞进 `deploy.sh`。

3. **每游戏资源隔离。** Linux 下每个游戏有独立 `WINEPREFIX`、独立 `Xvfb` 显示号、
   独立 ALSA 子设备槽位、独立 PulseAudio source/sink。新增游戏相关逻辑必须保持这套隔离，
   不要引入跨游戏的共享端口或共享音频设备。

4. **存档恢复有五道保护**（见 README）：运行中拒绝恢复、恢复前快照、同分区临时目录、
   重命名替换 + 回滚、归档路径校验。修改恢复路径时要逐条确认仍然成立。

5. **卸载要能退干净。** `deploy.sh` 装的东西，卸载必须能删；两端都保留门户本体、数据库、
   证书、游戏与存档。改卸载脚本时确认没有漏删也没有多删。

6. **前端产物不入库，但打包前必须构建。** `portal/src/main/resources/static/` 在
   `.gitignore` 里；`deploy/release.sh` 会在打包前自动 `npm run build`。
   本机手动 `mvn package` 前必须先跑一次前端构建，否则打出的 jar 里前端是旧的或空的。

7. **第三方依赖要登记。** 新增依赖要同步 `THIRD-PARTY-NOTICES.md` 与 `licenses/`。
   构建期 `license-maven-plugin` 会生成 `META-INF/THIRD-PARTY-DEPENDENCIES.txt`，
   但 `failOnMissing=false`——**它不会因为漏登记而让构建失败**，必须人工核对。

## 常见改动怎么做

### 加一个后端 API

1. 实体放 `domain/`，仓储放 `repo/`，编排放 `service/`，控制器放 `web/`
2. 鉴权默认走 `security/` 现有过滤器；对外只读接口如需公开，用设置里的开关，不要绕过鉴权层
3. 写操作要有幂等或明确的前置状态检查——前端会重试

### 加一个页面

1. 页面放 `frontend/src/`，路由注册在现有 router 里
2. **界面文案一律走 `t()`**（`import { t } from '../i18n.js'`），模板里写
   `{{ t("中文原文") }}`；带变量的用 `{0}` 占位：`t("已删除 {0}", name)`。
   键就是中文原文，漏翻译只会回退中文，不会空白。新文案要同步补
   `portal/src/main/resources/i18n/*.json`（`tools/i18n-check.py` 会报缺失）。
   注意别把 `t` 用作 `v-for` 变量名，会遮蔽导入的翻译函数。
3. 主题只用 `check-theme-vars.mjs` 认可的 CSS 变量，不要写死颜色（门禁用例会失败）
4. 产物由 `npm run build` 写进 `portal/src/main/resources/static/`（gitignore，不入库）；
   `deploy/release.sh` 打包前会自动构建
5. **按钮只用全局 `.btn` 体系，别自造样式。** 尺寸三档（`styles.css` 的 `--ctl-h-*`）：
   `.btn` / `.btn.sm` = md（34px），`.btn.primary` = lg（40px，**仅每屏唯一主动作**），
   `.btn.compact` = sm（28px）。同一行按钮必须同尺寸：多按钮同排时用 `.btn primary sm`，
   裸 `.btn primary` 会比同排的 `.btn` 高 6px —— 这是历史 bug 的常见来源。
   行内容器 `.backup-bar` 已统一降到 md（含 `.btn.primary`），`.hero-left .actions` 与
   `.edit-grid .actions` 显式统一为 lg。

### 改部署步骤

1. 步骤编排在 `portal/src/main/java/io/github/sander_i/deploy/`，目标机脚本在 `deploy/bin/`
2. 破坏性步骤必须二次确认，并在页面上实时回传日志
3. 幂等：重复执行只更新 jar、按需重建证书、重启服务
4. **脚本两份副本必须逐字节相同。** `deploy/bin/<x>` 供 `deploy/portal-deploy.sh` scp，
   `portal/src/main/resources/deploy/linux/bin/<x>` 供 jar 安装（`DeployAssets`）；
   `GameAssetsScriptTest` 会校验，新增脚本还要同时登记到 `DeployAssets` 的清单，
   否则页面「一键部署」装不上，功能会在运行时才报错

### 加一条后端提示文案

1. 抛异常或返回 `message` 时用 `I18n.t("中文原文: {0}", arg)`，不要直接写字符串
2. 键与前端共用一套语言包；新键要补 `portal/src/main/resources/i18n/*.json`
3. **别用 message 文本做逻辑判断**（`msg.contains("...")`）——消息会按请求语言翻译。
   需要区分状态时用专门的异常子类，参考 `DeployService.ConfirmRequiredException`
4. 外部语言包可来自 .po / XLIFF / CSV / properties / Android XML / .arb / .strings，
   解析在 `i18n/LocaleImporter`。源文即键的格式可直接映射；符号键格式靠 keyMap
5. 后台线程（部署 / 接入 / 上传）提交任务时用 `I18n.runAs(locale, ...)` 固定语言，
   否则日志会落到站点默认语言

### 改更新流程

更新链是：`release.sh` 生成 `release-manifest.json`（含 `version` / `tag` / `sha256` /
`minFromVersion`）→ 上传 Release → 目标机更新器下载 → 校验 → 停服 → 备份 → 替换 → 健康检查 → 失败回滚。

`minFromVersion` 决定「低于哪个版本不能自动升级」，可用 `GALWORLD_MIN_FROM_VERSION` 抬高。

## 验证改动

```bash
# 后端测试
cd portal && mvn test

# 前端测试与主题门禁
cd portal/frontend && npm test && npm run check:theme

# 完整构建
cd portal/frontend && npm ci && npm run build
cd .. && mvn -DskipTests package
# 产物：portal/target/gal-world-portal-<version>.jar
```

`mvn` 需要 JDK 17。本机若无 JDK，`deploy/release.sh` 会用它内置的 `JAVA_HOME_LOCAL`。

## 提交信息

格式参照 Conventional Commits，subject 用中文：

```
<type>(<scope>): <一句话说清改了什么，50 字符内>

<body：为什么这么改、放弃了什么方案，不写“改了哪些文件”>
```

- **type**：`feat` / `fix` / `refactor` / `perf` / `docs` / `test` / `build` / `ci` / `chore` / `revert`
- **scope**：`portal` / `web` / `service` / `save` / `deploy` / `frontend` / `ci` / `docs`，
  或具体模块名（如 `release`）；跨多处时省略
- **subject**：不加句号；不用「更新了」「修改了」「优化了一下」这类空话；
  写**行为变化**而不是操作过程（「切换存档保留当前进度」而不是「改了 SaveService」）
- **body**：只在需要解释**为什么**时写。一句话能说清就不写 body。
  取舍、被否掉的方案、踩过的坑放这里 —— 这是后来排查时唯一能看到的上下文
- **一个提交只做一件事**。格式化、重命名、行为改动不要混在同一条里
- **不要写工具署名**：不加 `Co-Authored-By`、不加生成器标识；作者就是提交人

提交身份固定为仓库级配置，不要用全局身份提交：

```bash
git config user.name  sander
git config user.email <仓库约定的 noreply 邮箱>
```

## 要花钱 / 有风险的操作（执行前必须问用户）

| 操作 | 风险 |
|---|---|
| `deploy.sh` / `install.ps1` | 改系统状态：装系统包、写 systemd、开防火墙、装 sudoers 白名单 |
| `uninstall.sh` | 删服务、目录、证书；带 `--purge-data` 时连游戏与存档一起删 |
| 页面「一键部署」 | 装 Wine / Selkies / Sunshine / 音频链，耗时且改系统 |
| 存档恢复 | 覆盖现有存档；虽有快照，仍可能丢失运行中未保存的进度 |
| 发布 Release | 公开产物；`minFromVersion` 写错会让老安装永远无法升级 |

## 已知边界

- Linux 端无 GPU，走 llvmpipe 软渲染 + x264 软编码；性能结论不能套到有 GPU 的机器。
- 浏览器串流依赖 WebRTC/WebSocket，中间有反代时要确认支持 upgrade。
- H2 是文件库，多实例同时写会锁；不要试图并行跑两个 Portal 指向同一个数据目录。
- 更新器要求目标版本自带更新接口；不含更新器的老版本会被拒绝。

## 不要做的事

- 不要在 `deploy.sh` 里装 Wine / Selkies / Sunshine（属于页面「一键部署」）
- 不要硬编码版本号或内网地址（用环境变量，见 `deploy.sh` 头部的环境变量清单）
- 不要提交真实凭据、证书私钥、元件仓库账号
- 不要为了跑通而放宽 `minFromVersion` 或跳过 SHA256 校验
