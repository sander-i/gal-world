# 安全策略（Security Policy）

[English](SECURITY.md) | 简体中文

## 这个项目的安全模型

Gal World 是**单用户自建工具**，设计前提是「只跑在自己的机器或内网里，由本人操作」，
不是多租户服务。因此有些默认值看起来宽松：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `galworld.write.token` | **空 = 不校验** | 所有非 GET 的 `/api/**` 需要请求头 `X-GalWorld-Write-Token`；留空则不校验写操作 |
| `galworld.deploy.token` | 空 | 启动时随机生成并打印到日志；`/api/deploy/**`、`/api/admin/**` 用它 |
| `galworld.deploy.allowed-cidrs` | `127.0.0.1/32,192.168.0.0/16,10.0.0.0/8` | 部署接口只接受这些来源网段 |
| `galworld.deploy.allow-sudo-password` | `false` | 是否允许在页面里输入 sudo 密码 |
| `server.ssl.key-store-password` | `changeit` | 仅本地自签证书；**对外提供服务时必须改** |
| H2 数据库 | 用户名 `sa`、空密码 | 文件库，落在 `~/galworld/data/portal/`，靠文件系统权限保护 |

**结论：不要把门户端口直接暴露到公网。** 需要远程访问时请走 VPN / 带认证的反向代理，
并至少配置 `GALWORLD_WRITE_TOKEN` 与 `GALWORLD_DEPLOY_TOKEN`、收窄 `GALWORLD_DEPLOY_CIDRS`。
部署后运行 `~/galworld/bin/selfcheck.sh` 可以看到当前实际生效的值。

其它需要注意的点：

- **元件下载**：默认优先读本机 Nexus（`127.0.0.1:8081`），失败时回退到 `gh-proxy.com`
  / `ghfast.top` 这类第三方代理，全程做 SHA256 校验。不信任第三方代理时把
  `GALWORLD_ARTIFACT_MIRRORS` 置空，只走自有 Nexus。
- **元数据来源**：更新清单与 Release 资产来自管理页「远程更新源」配置的接口
  （默认取 `GALWORLD_UPDATE_API_BASE` / `GALWORLD_UPDATE_REPO` / `GALWORLD_UPDATE_WEB_BASE`），
  由 `ReleaseClient` 解析。换托管平台或改用镜像时在管理页改，不要改源码；下载全程校验 SHA256。
- **外部组件**：Wine、Selkies、Sunshine、Moonlight Web、VB-Cable 由部署流程在目标机安装，
  以独立进程运行，不链接进本项目。它们自身的安全问题请找上游。

## 支持的版本

只对最新一次发布（当前 `0.0.1`）提供安全修复，不为历史 tag 回补补丁。

## 报告漏洞

**请不要在公开 issue 里贴可利用细节或 PoC。**

1. 新建一个 issue，标题以 `[SECURITY]` 开头，正文只写「发现一个安全问题，希望私下沟通」，
   不要包含复现步骤、影响范围或任何凭据；
2. 维护者会回复一个私下渠道，后续细节在那里沟通。

<!-- 发布前可选：在这里补一行你自己的安全联系邮箱，给不便开 issue 的报告者 -->

请一并说明：受影响的版本、部署形态（Linux / Windows）、以及是否是默认配置下可复现。
默认配置依赖「单用户内网」假设，若问题只在「已按上文加固」的部署里成立，请特别标注。

## 不属于安全问题的范围

- 需要本机管理员权限、或需要已经能读写 `~/galworld/` 才能实施的操作；
- 上游组件（Wine、Selkies、Sunshine、Moonlight、VirtualBox/驱动等）自身的漏洞；
- 「默认没开写操作鉴权」本身——这是上面写明的设计取舍，加固方法已给出；
- 缺少速率限制、缺少验证码之类的公网防护能力——本项目不面向公网部署。
