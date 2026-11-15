# 配置参考

完整配置项见 `portal/src/main/resources/application.yml`；设置页 / 管理页修改的值存在 H2 `setting` 表。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `GALWORLD_ROOT` | `~/galworld` | 安装根目录（games / data / lib） |
| `GALWORLD_PORTAL_PORT` | `8088` | 门户端口 |
| `GALWORLD_TLS` | `false` | 是否启用 HTTPS；`deploy.sh` 默认设为 `true` |
| `GALWORLD_KEYSTORE` | 空 | PKCS12 证书路径 |
| `GALWORLD_KEYSTORE_PASSWORD` | `changeit` | 证书密码；生产环境必须覆盖 |
| `GALWORLD_KEY_ALIAS` | `galworld` | 证书别名 |
| `GALWORLD_STREAM_URL` | 空 | Windows 下固定的 Moonlight Web 串流地址 |
| `GALWORLD_WRITE_TOKEN` | 空 | 写接口令牌；留空不校验 |
| `GALWORLD_DEPLOY_TOKEN` | 空 | 部署 / 后台令牌；留空启动时随机生成并打印日志 |
| `GALWORLD_DEPLOY_CIDRS` | `127.0.0.1/32,192.168.0.0/16,10.0.0.0/8` | 部署接口允许的网段 |
| `GALWORLD_DEPLOY_ALLOW_SUDO_PASSWORD` | `false` | 是否允许页面输入 sudo 密码 |
| `GALWORLD_UPDATE_ENABLED` | `true` | 是否允许远程更新 |
| `GALWORLD_UPDATE_ALLOW_OVERRIDE` | `false` | 管理页允许指定 tag / 版本覆盖更新（含降级） |
| `GALWORLD_UPDATE_API_BASE` | `https://gitee.com/api/v5/repos` | 更新源发布接口根地址 |
| `GALWORLD_UPDATE_REPO` | `sander-o/gal-world` | 更新源仓库路径 `owner/repo` |
| `GALWORLD_UPDATE_WEB_BASE` | `https://gitee.com` | 更新源仓库网页根地址 |
| `GALWORLD_UPDATE_TOKEN` | 空 | 私有仓库 / 镜像的只读令牌；Linux 更新器也可读 `~/.config/galworld-update.env` |
| `GALWORLD_ARTIFACT_NEXUS` | 本机 Nexus | 一键部署元件下载源 |
| `GALWORLD_ARTIFACT_USER` / `GALWORLD_ARTIFACT_PASS` | 空 | 元件仓库账号；留空匿名读取 |
| `GALWORLD_ARTIFACT_CANDIDATES` | 空 | 元件仓库候选地址，逗号分隔 |

凭据文件（均为 0600，由 systemd `EnvironmentFile` 读取）：

| 文件 | 内容 |
|---|---|
| `~/.config/galworld/artifacts.env` | 元件仓库账号密码 |
| `~/.config/galworld/creds.env` | `GALWORLD_KEYSTORE_PASSWORD=` 等证书口令 |
| `~/.config/galworld-update.env` | `GITEE_TOKEN=` 或 `UPDATE_TOKEN=` 更新只读令牌 |

## 必须 HTTPS

游玩页用 iframe 内嵌 Selkies，Selkies 客户端使用 WebCodecs，要求
`window.isSecureContext === true`。HTTP 父页面里嵌 HTTPS iframe 仍判为非安全上下文。
