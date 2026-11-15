# 参与贡献（Contributing）

[English](CONTRIBUTING.md) | 简体中文

先说明这个项目的定位，能省掉后面很多误会：

Gal World 是按个人需求长出来的自建方案，**不是通用产品**，不承诺开箱即用、不承诺向后兼容、
也不保证每个平台组合都有人验证过。欢迎 issue 与 PR，但维护者优先处理自己用得上的场景。

## 可以提什么

- 在**你自己跑通的环境**上遇到的 bug，附复现步骤、日志与版本号；
- 文档错误、命令跑不通、链接失效；
- 平台适配（发行版差异、显卡/声卡组合、Wine 版本行为）；
- 让部署更稳、更可回滚的改动。

## 提之前做两件事

1. 先搜现有 issue，这类自建项目重复问题很多；
2. 确认不是「已知限制」。`docs/OPERATIONS.md` 的「已知限制」表里列了画质、并发、HTTPS 等已经写明的边界；
   安全相关的设计取舍见 [`SECURITY.md`](SECURITY.md)。

## 开发环境

| 项 | 要求 |
|---|---|
| JDK | 17+（后端编译目标 17） |
| Maven | 可选，仓库带 Maven Wrapper（`./mvnw`），首次运行会自动下载 |
| Node | 20+（只构建前端时需要） |

跑测试**不需要**游戏本体、不需要 Wine、不需要图形环境：后端测试用 mock，
前端测试是纯 Node 单测。

## 构建与测试

```bash
# 一次跑完：后端测试 + 前端测试 + 主题门禁（推荐，CI 用的也是这套）
bash tools/ci.sh

# 单独跑
cd portal && ../mvnw -B test                    # 后端测试
cd portal/frontend && npm ci && npm test        # 前端单测
cd portal/frontend && npm run check:theme       # 主题变量门禁
```

打包（会先构建前端再打 jar）：

```bash
cd portal/frontend && npm ci && npm run build
cd .. && ../mvnw -DskipTests package            # 产物：portal/target/gal-world-portal-<version>.jar
```

## 改代码前请先读

- `README.md` 的「架构」：Portal 与串流链路的分工；
- `docs/ENVIRONMENT.md`：平台差异与依赖；
- `AGENTS.md`：架构边界与几条硬规则（版本号唯一来源、每游戏资源隔离、存档恢复五道保护、
  前端产物不入库、第三方依赖要登记）。这份文件是写给 AI 编码代理的，但规则同样约束人。

几条最容易踩的：

1. **版本号只写 `portal/pom.xml` 的 `<version>` 一处**，不要在脚本或文档里硬编码；
2. **不要硬编码版本号或内网地址**，走环境变量（清单见 `deploy.sh` 头部）；
3. **新增前端页面只用主题变量**，写死颜色会让 `check:theme` 失败；
4. **新增第三方依赖要同步 `THIRD-PARTY-NOTICES.md` 与 `licenses/`**——构建期的
   `license-maven-plugin` 只生成报告，漏登记不会让构建失败，必须人工核对；
5. **不要提交游戏本体、汉化补丁、游戏素材**：版权属于原作者与汉化组，仓库只放平台代码。

## 提交与 PR

- 一个提交只做一件事，提交信息说清「为什么」而不只是「改了什么」；
- PR 描述里写：复现/动机、你实际跑过的验证（贴 `tools/ci.sh` 或 `mvn test` 结果）、
  涉及系统状态的改动要写清风险；
- 动到 `deploy.sh`、`deploy/win/*.ps1`、`uninstall.sh` 这类会改系统状态的脚本时，
  请说明测过的操作系统与版本；
- 不要为了「跑通」而放宽 `minFromVersion`、跳过 SHA256 校验或关掉鉴权。

## 许可

项目以 [MIT](LICENSE) 发布。你提交的贡献按「inbound = outbound」处理：
**提交即表示你同意该贡献以 MIT 许可证随本项目分发**，不需要额外签署 CLA，
但请确保代码是你自己写的、或来自与本项目许可证兼容的来源。
