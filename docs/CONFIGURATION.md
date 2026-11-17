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

三种方式，都无需重启：

1. 把语言包文件放进 `<GALWORLD_ROOT>/data/i18n/`；
2. 「后台管理 → 语言包」上传文件；
3. 「后台管理 → 语言包」填地址，由服务器拉取（见下面的「从地址安装」）。

三种都支持下面的通用格式，按扩展名自动识别。

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

### 通用格式支持

| 格式 | 扩展名 | 键的来源 | 需要 keyMap |
|---|---|---|---|
| 本项目 JSON | `.json` | `messages` 的键（中文原文） | 否 |
| gettext | `.po` / `.pot` | `msgid` 即中文原文 | 否 |
| XLIFF 1.2 | `.xlf` / `.xliff` | `<source>` 即中文原文 | 否 |
| CSV / TSV | `.csv` / `.tsv` | 两列「原文,译文」；表头含 `source` 时按表头定位 | 否 |
| Java properties | `.properties` | 符号键 | 是 |
| Android | `.xml` | `<string name>` 符号键 | 是 |
| Flutter | `.arb` | 符号键 | 是 |
| Apple | `.strings` | 符号键 | 是 |
| i18next / vue-i18n | `.json`（嵌套） | 展开成 `nav.library` 符号键 | 是 |

**源文即键**的格式（.po / XLIFF / CSV）能直接导入，这是 Crowdin、Weblate、
Lokalise、Transifex 共同支持的导出格式，推荐走这条路。

**符号键**格式的键是 `nav.library` 这类符号名，与项目的中文原文键对不上，
必须提供 keyMap：

```json
{ "nav.library": "← 游戏库", "error.notFound": "Release 不存在: {0}" }
```

后台管理页有「加载源文清单」按钮，列出全部源文键，用于人工建映射表。

### 从地址安装（语言包市场）

市场里的语言包通常就是一个下载直链——Crowdin / Weblate / Transifex 的导出链接、
GitHub / Gitee raw、自建仓库的静态文件。后台管理页填地址即可，服务器拉取后
按扩展名识别格式，走与上传完全相同的导入链路。

接口形态：

```json
POST /api/admin/i18n
{ "url": "https://raw.githubusercontent.com/org/i18n/main/ko.po",
  "id": "ko", "keyMap": {} }
```

`id` 可省略，依次由 `Content-Disposition` 文件名、URL 末段推断。

**安全约束**（这是个服务端发起任意请求的接口，按 SSRF 面处理）：

- 只接受 `http` / `https`
- 解析出的地址必须是公网地址；回环、私网（10/8、172.16/12、192.168/16）、
  链路本地（169.254/16）、CGNAT（100.64/10）、保留段（0/8、192.0.0/24、
  198.18/15、240/4）、IPv6 ULA（fc00::/7）一律拒绝
- 重定向自己跟，最多 3 跳，**每一跳都重新校验**（否则公网 302 到 `127.0.0.1` 就绕过校验）
- 体积上限与上传一致（2 MB），超限即断流
- 与上传同权：需要部署令牌 + 允许网段

内网部署要从局域网拉包时，二选一：

```yaml
galworld:
  i18n:
    market-allow-private: true          # 全局放开地址检查
    market-allow-hosts: packs.corp.lan  # 或：只认清单内的主机（非空时跳过地址检查）
    market-timeout: 20s
```

`market-allow-hosts` 非空时只认清单里的主机名，此时不再做地址检查——
清单本身即运维的显式放行。

### 导出

后台管理页可按语言导出 `json` / `po` / `xliff` / `csv` / `properties`，
交给译者在对应工具里编辑后导回。导出的 PO 与 XLIFF 都能原样再导入（往返无损）。

### 导入结果的提示

导入后页面显示：导入条数、跳过条数、跳过的键示例、未翻译的键示例、
以及**占位符编号不一致**的键。最后一项最关键——译文的 `{1}` 对不上原文的 `{0}` 时，
页面上会直接显示原始占位符，所以单独列出来。占位符不一致不会导致导入失败，
其余几百条仍可用。

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
| `tools/i18n-e2e-import.py` | 起服务后验证各格式导入、keyMap、导出往返 |
| `tools/i18n-e2e-market.py` | 起服务后验证从地址安装（市场直链） |

`i18n-extract*.py` / `i18n-keys.py` / `i18n-fixkey.py` 是抽取与改键的辅助脚本，
功能已被 `i18n-chunk.py` 覆盖，保留作参考。

