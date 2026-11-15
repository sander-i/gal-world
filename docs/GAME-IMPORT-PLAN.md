# 游戏接入入口设计

日期：2026-11-04
状态：待评审
影响面：新增 `portal/src/main/java/com/galworld/service/GameImportService.java`、`portal/src/main/java/com/galworld/web/GameImportController.java`、`portal/frontend/src/views/GameImportView.vue`；修改 `api.js`、`router.js`、`LibraryView.vue`。不改变串流链路与后端既有接口。

## 1. 背景与问题

门户里出现一款新游戏，目前只有一条路：

1. 用 scp/sftp 把游戏文件传到 `~/galworld/games/<id>/`。
2. SSH 登录服务器，执行 `~/galworld/bin/galworld add <id> --name ... --exe ...`。
3. 手工执行 `WINEPREFIX=... wineboot -u` 初始化前缀。
4. 手工拷贝中文字体到 `drive_c/windows/Fonts/`。
5. 在门户点启动，验证画面与声音。

门户本身没有任何接入入口：列表页没有扫描按钮，`api.js` 里定义了 `api.rescan()` 但没有任何视图调用它；`POST /api/games/scan` 只是把已存在的 `game.env` 同步进库。`GameScanService.syncAll()` 只认 `games/<id>/game.env`，没有 env 的目录对门户完全不可见。

结果：新游戏接入必须有人懂 shell、记得字体和 wineboot 这两步，否则游戏启动后中文全是方框、或前缀缺失导致启动失败。

## 2. 目标与非目标

**目标**

1. 游戏库页面提供「＋ 接入游戏」入口，进入独立的接入向导。
2. 自动发现 `~/galworld/games/` 下尚未注册的目录，列为候选。
3. 向导内完成：选目录 → 选 exe / 填名称与分辨率 → 自动分配显示号、端口、回环通道 → 写 `game.env` → 初始化 wine 前缀 → 装中文字体 → 首次启动 → 健康验证。
4. 执行过程分步、实时回传日志（SSE），失败可重试，可回滚注册。
5. 不破坏现有扫描、启动、串流、心跳与媒体逻辑。

**非目标**

- 不做浏览器上传压缩包（本期只接入已存在于服务器上的目录）。
- 不做游戏元数据自动抓取（SearchGal 仍为后续项）。
- 不改 `galworld` CLI；Windows 部署形态不支持本流程（返回明确提示）。
- 不做批量接入，一次一个。

## 3. 现状事实（实现依据）

| 事实 | 位置 |
|---|---|
| 扫描器只认 `games/<id>/game.env`；无 env 的目录被忽略 | `GameScanService.syncAll()` |
| `writeEnv(Game)` 可把 DB 里的游戏写回 `game.env` | `GameScanService.writeEnv()` |
| 显示号/端口/槽位规则：display 从 2 起的最小空闲，`port = 9000 + display`，slot 从 0 起最小空闲 | `deploy/bin/galworld` 的 `next_free_display` / `next_free_slot` |
| 启动走 `systemctl --user start galworld-game@<id>.service` | `SessionService.start()` |
| 会话状态与端口就绪探测 | `SessionService.status()` + `SessionController` 的 `streamReady` 判定 |
| 步骤 + SSE + 取消的现成范式 | `DeployRun`、`DeployService`、`DeployController` |
| 前端 SSE 用 fetch + 自定义头（EventSource 不支持头） | `api.js` 部署日志流 |
| 写接口令牌（可选启用） | `WriteSecurity`，头 `X-GalWorld-Write-Token` |

## 4. 交互设计

### 4.1 入口

`LibraryView` 顶栏（「全部游戏 / 公司维护 / 设置 / 管理」一组）最前面加：

```html
<router-link to="/games/import" class="ghost-btn">＋ 接入游戏</router-link>
```

新路由 `/games/import`，视图 `GameImportView.vue`，独立整页，不套首页 Hero。

### 4.2 三步向导

向导用单一 `step` 状态量：`pick` → `configure` → `running` → 终态（`done` / `failed`）。不做浏览器前进/后退步进，提供「上一步」按钮。

**第一步：选择目录**

- 顶部说明：「把游戏解压到服务器 `~/galworld/games/<目录名>/` 后点刷新」。
- 卡片列表，每项显示：目录名（即游戏 id）、体积、最后修改时间、找到的 exe 数量。
- 「刷新」按钮重新拉取候选。
- 空态：给出 scp 示例一行；无候选时仍可看说明。
- 选中卡片后进入下一步。

**第二步：确认配置**

- 名称：默认取目录名；可改。
- 可执行文件：下拉，来自递归枚举结果，默认选中体积最大的 `.exe`；下方显示相对路径与体积。
- 分辨率：宽 / 高，默认 `1280 × 720`。
- 帧率：默认 `30`，允许 `"30"` 或 `"30-60"` 形式（与 `game.env` 的 `FRAMERATE` 一致）。
- 显示号 / 端口 / 回环槽位：只读，由后端试算并展示，标注「自动分配」。
- 底部「开始接入」。

**第三步：执行**

- 步骤清单，每步状态：待执行 / 执行中 / 成功 / 失败 / 跳过。
- 实时日志窗（等宽字体，自动滚到底，可暂停滚动）。
- 成功：显示「进入游戏」（跳 `/play/<id>`）、「查看详情」。
- 失败：高亮失败步骤与错误摘要，提供「重试」「回滚注册」。
- 任何阶段可「取消」。

### 4.3 结果语义

- 重试：从失败步骤继续，已注册的步骤幂等重放（写 `game.env` 与入库可重复执行）。
- 回滚注册：停止服务、删除 `game.env`、删除 DB 记录；**保留游戏文件与 wineprefix**（前缀重建成本高，重试可直接复用）。
- 回滚后候选列表应重新出现该目录。

## 5. 接口设计

所有接口挂在 `/api/games/import`，全部方法都显式调用 `WriteSecurity.check(req)`（包括 GET），因为候选接口会暴露服务器目录结构。

### 5.1 `GET /api/games/import/candidates`

响应：

```json
{
  "gamesDir": "~/galworld/games",
  "supported": true,
  "warnings": ["permission-denied: ~/galworld/games/locked"],
  "candidates": [
    {
      "id": "sample-game",
      "sizeBytes": 4823456789,
      "mtime": "2026-11-03T21:04:11Z",
      "exes": [
        { "path": "sample-game.exe", "sizeBytes": 3121152 },
        { "path": "sub/tool.exe", "sizeBytes": 120832 }
      ]
    }
  ]
}
```

规则：

- 只列 `gamesDir` 的**直接子目录**，排除以 `.` 开头与以 `.meta` 结尾的目录，排除已有 `game.env` 的目录。
- `supported=false` 表示当前平台不是 Linux，前端直接显示不支持提示。
- 目录不可读时跳过并在 `warnings` 里返回原因（不阻断整页）。

### 5.2 `GET /api/games/import/slots`

返回试算结果，供第二步展示：

```json
{ "displayNum": 3, "port": 9003, "loopbackSlot": 2 }
```

无空闲时返回 409 `{"message":"没有空闲的显示号/槽位"}`。

### 5.3 `POST /api/games/import`

请求：

```json
{
  "id": "sample-game",
  "name": "示例游戏",
  "exe": "sample-game.exe",
  "width": 1280,
  "height": 720,
  "framerate": "30"
}
```

响应 `202`：

```json
{ "runId": "gi-20261104-153012-ab12", "state": "QUEUED" }
```

并发：同时只允许一个进行中的接入 run，已有则返回 `409` 并带上现有 `runId`。

### 5.4 `GET /api/games/import/runs/{runId}`

一次性快照，供 SSE 断线重连或页面刷新后恢复：

```json
{
  "runId": "gi-...",
  "state": "RUNNING",
  "steps": [
    { "id": "validate", "title": "校验参数与目录", "state": "SUCCESS" },
    { "id": "allocate", "title": "分配显示号 / 端口 / 槽位", "state": "SUCCESS" },
    { "id": "register", "title": "写入 game.env 并入库", "state": "SUCCESS" },
    { "id": "wineboot", "title": "初始化 Wine 前缀", "state": "RUNNING" },
    { "id": "fonts", "title": "安装中文字体", "state": "PENDING" },
    { "id": "launch", "title": "启动游戏", "state": "PENDING" },
    { "id": "verify", "title": "验证串流就绪", "state": "PENDING" }
  ],
  "lines": ["[validate] ok", "..."],
  "error": ""
}
```

### 5.5 `GET /api/games/import/runs/{runId}/events`

SSE，`text/event-stream`：

```
event: log
data: {"line":"WINEPREFIX=~/galworld/games/sample-game/wineprefix"}

event: step
data: {"id":"wineboot","state":"RUNNING","title":"初始化 Wine 前缀"}

event: done
data: {"state":"SUCCESS","runId":"gi-..."}
```

复用 `DeployRun.addListener(SseEmitter)` 的推送方式；`done` 后立即关闭 emitter。

### 5.6 `POST /api/games/import/runs/{runId}/cancel`

终止当前子进程，停止服务，若已注册则回滚注册。响应 `{"state":"CANCELLED"}`；run 已终态返回 `409`。

### 5.7 `POST /api/games/import/runs/{runId}/retry`

以上次请求参数从失败步骤重建 run（新 runId），返回 `202 {runId}`；run 未失败返回 `409`。

### 5.8 `POST /api/games/import/runs/{runId}/rollback`

停止服务、删 `game.env`、删 DB 行；幂等。响应 `{"state":"ROLLED_BACK"}`。

错误码约定：`400` 参数非法、`403` 令牌错误、`404` run 不存在、`409` 状态冲突/无空闲槽位/已注册、`501` 平台不支持。

## 6. 后端实现

### 6.1 新增类

| 类 | 职责 |
|---|---|
| `service/GameImportService` | 候选发现、参数校验、run 编排（新建/查找/取消/重试/回滚） |
| `service/GameExeScanner` | 纯函数：递归枚举并排序 exe 候选 |
| `service/GameSlotAllocator` | 纯函数：由现有 display/port/slot 集合算出下一个可用值 |
| `web/GameImportController` | 路由、DTO record、异常映射（照 `DeployController` 风格，record 内联） |

run 状态直接复用 `DeployRun`（`deploy` 包内的通用容器：id、日志行、SSE 监听者、终态、取消），`stepId` 传 `"game-import"`，不重复造轮子。

### 6.2 校验规则

- `id`：`^[a-z0-9][a-z0-9._-]{1,31}$`，且 `gamesDir/<id>` 是已存在的目录、不是符号链接。
- 目录内不能已有 `game.env`，DB 里不能已有同 id。
- `name`：trim 后非空，长度 ≤ 120。
- `exe`：相对路径，用 `gameDir.resolve(exe).toRealPath()` 校验，结果必须以 `gameDir.toRealPath()` 为前缀（拦截 `../` 与符号链接逃逸），且是常规文件、扩展名 `.exe`（大小写不敏感）。
- `width`/`height`：`640..3840`、`480..2160`。
- `framerate`：`^\d{1,3}(-\d{1,3})?$`。

### 6.3 exe 枚举规则

- 递归深度 ≤ 3（相对游戏目录），最多返回 200 条。
- 只收 `.exe`（大小写不敏感）。
- 排除文件名包含 `setup`、`install`、`unins`、`uninst`、`vcredist`、`dxsetup`、`dxwebsetup`、`crash`、`report` 的文件。
- 跳过符号链接目录与以 `.` 开头的目录。
- 按体积降序；前端默认选第一个。

### 6.4 槽位分配规则

与 CLI 完全一致，保证两种入口不互相冲突：

- 已用集合来自 `repo.findAll()`（执行前先 `scan.syncAll()`）。
- display：从 2 递增，取第一个未被使用的值，上限 63；超限报 `NO_SLOT`。
- port：`9000 + display`；若该端口已在用，继续下一个 display。
- slot：从 0 递增的最小空闲值。
- 分配结果在 `register` 步骤内再次校验（防止两个并发请求拿到同一值；服务内串行化）。

### 6.5 流水线

| 步骤 | 行为 | 失败处理 |
|---|---|---|
| `validate` | 6.2 全部校验 | 终态 FAILED，无副作用 |
| `allocate` | 6.4 | FAILED，无副作用 |
| `register` | 构造 `Game`（含 `onDisk=true`）→ `writeEnv()` → `repo.save()` | 已写一半时删除 `game.env` 并删 DB 行 |
| `wineboot` | `WINEPREFIX=<dir>/wineprefix wineboot -u`，用 `xvfb-run -a` 提供显示；超时 180s；输出实时进日志 | FAILED，提示可重试 |
| `fonts` | 拷 `/usr/share/fonts/truetype/wqy/wqy-microhei.ttc` 到 `<prefix>/drive_c/windows/Fonts/`；源不存在记 WARN 并继续（不阻塞） | 仅 WARN |
| `launch` | `sessions.start(id, port)` | FAILED |
| `verify` | 轮询 `sessions.status()` 每 2s，最长 120s：状态 `RUNNING` 且端口就绪（复用 `SessionController` 的 `streamReady` 判定） | 超时 FAILED，提示看诊断 |

字体路径可通过 `galworld.import.font` 覆盖，默认 `wqy-microhei.ttc`。

执行在线程池中串行（单线程执行器），`DeployRun` 的 `process` 字段用于取消时 kill。

### 6.6 回滚

`rollback()`：`sessions.stop()` → 删 `game.env` → 删 DB 行 → 清 media reconcile 缓存（若有）。不删游戏目录、不删 `wineprefix`、不删 `<id>.meta`。

回滚可重复执行；目录不存在或已删除也视为成功。

## 7. 前端实现

### 7.1 路由与入口

- `router.js` 新增 `{ path: '/games/import', name: 'game-import', component: GameImportView }`。
- `LibraryView.vue` 顶栏加链接（见 4.1）。

### 7.2 `GameImportView.vue`

结构：

- `script setup`，状态：`step`、`candidates`、`selected`、`form`、`runId`、`steps[]`、`lines[]`、`runState`、`error`。
- 拉候选、拉槽位试算、提交、SSE 订阅、取消/重试/回滚。
- 纯逻辑抽到 `src/game-import.js`（可单测）：`filterExes` 不用（后端已过滤）；需要的是 `canSubmit(form)`、`stepStateOf(steps, id)`、`runPhase(runState)`。

### 7.3 `api.js`

新增：

```js
importCandidates: () => req('/api/games/import/candidates'),
importSlots: () => req('/api/games/import/slots'),
importGame: (body) => req('/api/games/import', { method: 'POST', body: JSON.stringify(body) }),
importRun: (runId) => req(`/api/games/import/runs/${runId}`),
importRunEvents: (runId, token, onEvent) => /* fetch + ReadableStream，照 api.deployEvents */,
importCancel: (runId) => req(`/api/games/import/runs/${runId}/cancel`, { method: 'POST' }),
importRetry: (runId) => req(`/api/games/import/runs/${runId}/retry`, { method: 'POST' }),
importRollback: (runId) => req(`/api/games/import/runs/${runId}/rollback`, { method: 'POST' }),
```

GET 也需要令牌：`importCandidates` / `importSlots` / `importRun` 显式加 `writeHeaders()`。

### 7.4 样式

全部使用现有变量：卡片沿用 `.card` / `.ghost-btn` 体系；日志窗沿用 `--media-canvas` 与等宽字体；步骤状态用 `--ok` / `--warn` / `--err` 的既有点位。不新增字面色值（`check:theme` 必须通过）。

## 8. 边界与风险

| 场景 | 预期行为 | 说明 |
|---|---|---|
| 目录里没有 exe | 第二步下拉为空，「开始接入」禁用 | 提示用户检查解压是否完整 |
| exe 在深层子目录 | 深度 ≤3 内可见；超出需手工确认 | 超限时日志提示被截断 |
| 游戏目录含中文路径 | 允许；`game.env` 值原样写入 | 启动依赖 ASCII exe 名的问题沿用现有运维约定 |
| 同时两个接入请求 | 第二个返回 409 与现有 runId | 服务内串行 |
| wineboot 超时 | 步骤失败，可重试（复用已建 prefix） | 不自动回滚，用户决定 |
| 字体文件缺失 | WARN 后继续 | 不阻塞接入，但中文可能显示为方框 |
| 无空闲 display/slot | 提交前 409 | CLI 同样限制 |
| Windows 服务器形态 | 501 + 提示用 `deploy/win/install.ps1` | v1 只支持 Linux |
| 接入中途服务重启 | run 丢失，`game.env` 可能已写 | 列表页仍能扫描到游戏，可手动继续启动 |
| 目录被外部删除 | register 失败，候选刷新后消失 | 无残留 |

**风险等级：中低。** 写服务器目录与启动服务，但全部校验后执行，失败可回滚且不动游戏文件。

## 9. 测试与验收

### 9.1 自动检查

后端：

- `GameExeScannerTest`：嵌入、过滤关键字、深度限制、200 条上限、排序、符号链接跳过。
- `GameSlotAllocatorTest`：空集从 2/0 开始、空洞复用、端口冲突跳号、上限溢出。
- `GameImportValidatorTest`：id 正则、exe 逃逸（`../`、绝对路径、符号链接）、名称/分辨率/帧率边界。
- `GameImportServiceTest`（mock repo/sessions/runner）：register 失败清理、rollback 幂等、并发 409、retry 从失败步骤开始。
- 命令：`JAVA_HOME=/g/Program\ Files/Java/corretto-17.0.14 mvn -o -DskipTests=false test`

前端：

- `game-import.test.js`：`canSubmit`、`runPhase`、SSE 事件归并（log/step/done）。
- 门槛：`npm test`、`npm run check:theme`、`npm run build`。

### 9.2 手工矩阵

1. 正常目录（单个 exe）：接入成功，`/play/<id>` 可玩，画面与声音正常。
2. 多 exe 目录：默认选中最大文件，手动切换后可成功启动。
3. 非法目录名（含大写/空格/中文）：提交返回 400，页面显示原因。
4. 目录已有 `game.env`：不出现在候选；直接 POST 返回 409。
5. 无空闲槽位：提交前提示。
6. wineboot 失败（临时改名 wine 二进制模拟）：步骤失败、可重试、回滚后候选重现。
7. 中途取消：服务未残留，`game.env` 已删。
8. 页面刷新后重新打开 runId：状态与日志可恢复。
9. 未配置写令牌与已配置写令牌两种实例：权限行为正确。
10. 四套主题下向导可读。

### 9.3 验收标准

- 从「＋ 接入游戏」到游戏可玩，全程不需要 SSH。
- 接入失败不留下半注册状态（`game.env` 与 DB 一致）。
- 回滚后该目录重新出现在候选列表。
- `mvn test`、`npm test`、`check:theme`、`build` 全部通过。

## 10. 工作量

- 后端：`GameImportService`（约 300 行）、`GameExeScanner`（80 行）、`GameSlotAllocator`（70 行）、`GameImportController`（180 行）、测试（约 250 行）。
- 前端：`GameImportView.vue`（约 420 行）、`game-import.js`（约 60 行）、`api.js` +15 行、路由 + 顶栏 +5 行、测试（约 80 行）。
- 合计约 1.5–2 人日。

## 11. 后续可选

- ~~浏览器上传压缩包（zip/7z）后再走同一流水线。~~ 已实现：上传页支持
  zip/7z 压缩包与整个文件夹，分片 8 MiB、断点续传，解压后自动整理到
  `games/<id>/` 并进入「确认配置」（实现见 `GameUploadService`、`GameUploadLimits`）。
- 从 exe 元数据（版本信息）推断游戏名与公司。
- 接入完成后自动做一次备份快照。
- 批量接入（多目录一次提交）。
