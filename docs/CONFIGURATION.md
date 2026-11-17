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

## 国际化与语言包

界面与后端异常文案共用同一套语言包，键是**中文原文**，缺键时回退到原文，
所以漏翻译只会显示中文，不会出现空白或键名。

### 内置语言

随 jar 分发四套：`en`（默认）、`zh-CN`、`zh-TW`、`ja`。
源文件在 `portal/src/main/resources/i18n/<id>.json`。

### 新增语言（不改代码）

两种方式，都无需重启：

1. 把 JSON 放进 `<GALWORLD_ROOT>/data/i18n/<id>.json`；
2. 「后台管理 → 语言包」上传同一个 JSON。

```json
{
  "id": "ko",
  "name": "한국어",
  "messages": {
    "设置": "설정",
    "没有这个游戏: {0}": "게임이 없습니다: {0}"
  }
}
```

- `id` 决定文件名，只能用字母数字与连字符（2–16 位）
- `messages` 的键必须与源码里的中文原文逐字一致（含空格、标点、`{0}` 占位符）
- 占位符编号必须与原文一致，否则运行时会显示原始 `{n}`
- 外部包**整体替换**同 id 的内置包，不是逐键合并；没提供的键回退中文原文

删除外部包会恢复内置版本（如有），内置语言本身不可删。

### 语言选择顺序

浏览器本机选择（`localStorage` 的 `galworld.locale`）→ `?lang=` 临时覆盖 →
浏览器 `Accept-Language` → 站点默认语言（「设置 → 界面语言」，存 `setting` 表，默认 `en`）。

后端按请求头 `X-GalWorld-Locale` 决定异常文案语言，前端所有请求自动带上；
EventSource / 直链下载可用 `?lang=` 代替。

### 工具

| 脚本 | 用途 |
|---|---|
| `tools/i18n-chunk.py` | 从源码抽取全部键，写入 `tmp-i18n/keys.txt` |
| `tools/i18n-check.py <pack.json>` | 校验缺失键、多余键、占位符不一致 |
| `tools/i18n-wrap.py` / `i18n-wrap-fe.py` | 把中文字面量包进 `I18n.t()` / `t()`（改代码时用） |
| `tools/i18n-e2e.py` | 起服务后验证语言接口与异常语言 |

`i18n-extract*.py` / `i18n-keys.py` / `i18n-fixkey.py` 是抽取与改键的辅助脚本，
功能已被 `i18n-chunk.py` 覆盖，保留作参考。

