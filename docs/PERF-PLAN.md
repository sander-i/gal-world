# 前端页面加载速度优化方案

> 状态：**已实施**（2026-11-18），实测结果见 §8
> 编写日期：2026-11-18
> 分支：`feat/perf-optimize`
> 关联：`docs/LOADING-UX-PLAN.md`（骨架屏与视觉加载态）——本文只管**速度**，不管观感

---

## 0. 实测状态说明

**§1–§4 的耗时数字是编写方案时的代码推断；§8 是实施后在本机实测的结果**
（Windows 开发机 + H2 文件库 + 40 个构造出来的游戏，非生产实例）。

体积数字来自已构建产物 `portal/src/main/resources/static/assets/`，是实测值。

基线测量方法（改动前后用同一套命令）：

```bash
# API 耗时中位数（各跑 20 次）
for i in $(seq 20); do
  curl -s -o /dev/null -w '%{time_total}\n' 'http://127.0.0.1:8088/api/recommend?limit=10'
done | sort -n | awk '{a[NR]=$1} END{print "p50="a[int(NR/2)], "p90="a[int(NR*0.9)]}'

# 查询次数：application.yml 打开
#   logging.level.org.hibernate.SQL: DEBUG
# 然后数 /api/games?limit=40 一次请求打印了多少条 select
```

---

## 1. 首屏体积（实测）

| 文件 | 原始 | gzip |
|---|---|---|
| `index-*.js` | 491 928 B | 164 571 B |
| `index-*.css` | 434 473 B | 61 523 B |

拆解：

- CSS 里 **361 KB 是 Element Plus 全量样式**（`element-plus/dist/index.css` 实测 361 441 B），
  而全站只用两个组件：`ElMessage`、`ElMessageBox`（`src/feedback.js:8`）。
- JS 是**单个 bundle**：`router.js:7-17` 全部静态 import，
  `AdminView`（1728 行）、`PlayView`（1070 行）、`GameEditView`（945 行）都打进首屏。

### 1.1 gzip 未开启

`application.yml` 无 `server.compression` 配置，Spring Boot 默认关闭。
上表的 gzip 数字**现在没有生效**——实际传输就是 491 KB + 434 KB。

### 1.2 改法

**A. 开启 gzip**

```yaml
server:
  compression:
    enabled: true
    mime-types: application/json,application/javascript,text/css,text/html,text/plain,image/svg+xml
    min-response-size: 1024
```

媒体文件是预压缩的，不在 mime-types 里，不会二次压缩。

**B. Element Plus 按需引入**

`main.js:9` 删 `import 'element-plus/dist/index.css'`，改：

```js
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-message.css'
import 'element-plus/theme-chalk/el-message-box.css'
import 'element-plus/theme-chalk/el-overlay.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
```

实测这几个文件合计约 19.5 KB（7 856 + 3 898 + 4 778 + 183 + 2 946）。

**C. 路由懒加载**

`router.js` 除首页外全部改成 `() => import(...)`：

```js
{ path: '/admin', name: 'admin', component: () => import('./views/AdminView.vue') },
```

配合 `vite.config.js`：

```js
build: {
  rollupOptions: {
    output: {
      manualChunks: {
        'vendor-vue': ['vue', 'vue-router'],
        'vendor-ep': ['element-plus'],
      },
    },
  },
}
```

**验收**：构建后 CSS 总量 < 120 KB，首屏 chunk（不含 vendor）< 200 KB。

---

## 2. 后端 N+1（最大瓶颈）

### 2.1 推荐排序在 Comparator 里查库

`RecommendService.compare()`（`service/RecommendService.java:86`）每调用一次做：

```java
Instant lx = lastPlayed(x.getId(), now);               // 1 次查询（:107）
Instant ly = lastPlayed(y.getId(), now);               // 1 次查询
long sx = playtime.summary(x.getId()).totalSeconds();  // 5 次查询
long sy = playtime.summary(y.getId()).totalSeconds();  // 5 次查询
```

`Comparator` 被调用 O(n log n) 次。50 款游戏约 280 次比较 × 12 次查询 ≈ **3 360 次查询**，
只为返回 10 条。`lastPlayed()` 内部还会被重复调用（同一游戏在不同比较里重复查）。

`HeroService.pick()`（`service/HeroService.java:68`、`:86-87`）同样：
`all.stream().map(g -> sessions.findFirst...)` 每游戏一次，
`playtime.summary()` 在 filter 和 max 里各调一次（`:86`、`:87`），即每游戏 10 次查询。

**改法**：排序前一次性批量取。

```java
// PlaySessionRepository 加两条聚合（已有 sumAllClosedSeconds 先例）
@Query("select s.gameId, max(s.endedAt) from PlaySession s " +
       "where s.endedAt is not null group by s.gameId")
List<Object[]> lastEndedByGame();

@Query("select s.gameId, coalesce(sum(s.seconds), 0) from PlaySession s " +
       "where s.endedAt is not null group by s.gameId")
List<Object[]> closedSecondsByGame();
```

`recommend()` 入口处调一次，把结果装进 `Map<String, Instant>` 和 `Map<String, Long>`，
`compare()` 改成纯内存比较。`HeroService` 同样。

### 2.2 playtime.summary 每游戏 5 次查询

`PlaytimeService.summary()`（`service/PlaytimeService.java:97`）：

1. `repo.sumClosedSeconds(gameId)`
2. `repo.findByGameIdAndEndedAtIsNull(gameId)`
3. `todaySeconds()` → `repo.findByGameIdOrderByStartedAtDesc(gameId)`
   （**把该游戏全部历史记录拉进内存遍历**，`:116`）
4. `repo.countByGameIdAndEndedAtIsNotNull(gameId)`
5. `repo.findFirstByGameIdAndEndedAtIsNotNullOrderByEndedAtDesc(gameId)`

40 条列表 = 200 次查询，其中 40 次是全量拉取。

**改法**：加一个批量版本，一条聚合拿全部游戏的 closed/live/today/last：

```java
public Map<String, PlaytimeSummary> summaryByGame(Collection<String> gameIds);
```

列表页和推荐页用它；`PlaytimeController` 的单游戏接口保持原样。

### 2.3 列表页每页全量扫磁盘

`GameController.list():80`：

```java
scan.syncAll();
```

`GameScanService.syncAll()`（`service/GameScanService.java:58`）会：

- 遍历 `games` 目录
- 每个游戏读一次 `game.env` 并 `repo.save()`
- 每个游戏调 `mediaSync.reconcile()`（`GameMediaService.java:300`）：
  扫媒体目录 → 查库 → 可能写库 → `renumberRecords()` → `syncCoverCache()`

**用户每翻一页、每改一次筛选都全量重扫**。

**改法**：加时间节流。

```java
private volatile long lastScanAt = 0;
private static final long SCAN_TTL_MS = 5_000;

private void scanIfStale() {
    long now = System.currentTimeMillis();
    if (now - lastScanAt < SCAN_TTL_MS) return;
    lastScanAt = now;
    scan.syncAll();
}
```

`POST /api/games/scan` 强制全量。列表接口改用 `scanIfStale()`。

### 2.4 每个游戏 fork 一个 systemctl

`GameController.toView()`（`:225`）→ `sessions.status()` →
`SystemdSessionManager.status()`（`service/SystemdSessionManager.java:58`）→
`systemctl --user is-active <unit>`，**每次都是外部进程**。

40 条列表 = 40 次 fork+exec。`CommandRunner.DEFAULT_TIMEOUT` 是 3 秒
（`platform/CommandRunner.java:30`），最坏情况单页卡 120 秒。

**改法**：批量查一次。

```bash
systemctl --user list-units 'galworld-game@*' --all --no-legend --plain
```

一次拿到全部 unit 状态，内存建 map。加 1–2 秒 TTL 缓存（前端本来 5 秒才轮询）。

Windows 侧 `WindowsProcessSessionManager.status()`（`:50`）读 pid 文件 + `alive()`，
同样值得加 TTL，但开销远小于 fork。

### 2.5 公司/汉化组每游戏全表扫描

`GameController.toView():232` 调 `companyService.ofGame(g.getId())`，
`CompanyService.ofGame()`（`:89`）每次都执行：

```java
List<Company> global = companies.findAllByOrderBySortOrderAscNameAscAliasAscWebsiteAsc();
```

40 条 = 40 次全表扫描。`LocalizationGroupService.ofGame()`（`:91`）同样。

**已有一半的解法**：`CompanyService.byGame(Collection)`（`:69`）和
`LocalizationGroupService.byGame(Collection)`（`:72`）已写好且**注释明确写着
「避免列表页 N+1 查询」**，但 `grep` 全仓库**没有任何调用者**。

`GameMediaService.byGames(List, kind)`（`:130`）同理，也无人调用。

**改法**：把列表接口切到这三个批量方法，`ofGame()` 只留给单游戏详情接口。
这是纯接线工作，没有新逻辑。

### 2.6 settings 每游戏查 7 次库

`GameController.toView()`（`:229-272`）里：

```
settings.coverRotationMode()      → repo.findById
settings.coverRotationInterval()  → repo.findById
settings.cgRotationMode()         → repo.findById
settings.cgRotationOrder()        → repo.findById
settings.cgRotationInterval()     → repo.findById
```

加上 `GameMediaService.itemsOf()`（`:87`）每 kind 一次 `settings.imageVariantEnabled()`，
共 7 次。40 条 = 280 次单键查询。

`SettingsService.get()`（`:289`）每次都是 `repo.findById(key)`，无缓存。

**改法**：`SettingsService` 加一个 `all()` 的短 TTL 缓存（`all()` 已有，`:279`），
`toView` 开头取一次 `Map<String, String>` 传下去。设置页保存时清缓存。

### 2.7 itemsOf 每游戏扫两次目录

`GameMediaService.itemsOf()`（`:86`）对每个 `(gameId, kind)` 调
`scanVariantWidths(dir)`（`:106`）——`Files.list()` 整个媒体目录。

`toView` 里 covers + cgs 各一次，40 条 = 80 次目录扫描。

**改法**：`byGames()` 已是批量取记录，但派生图宽度仍需扫目录。
给 `scanVariantWidths` 加 `gameId → 结果` 的短 TTL 缓存（媒体目录变动只在
上传/删除时发生，`GameMediaService` 自己知道什么时候该失效）。

### 2.8 列表页返回用不到的数据

`toView()` 同时取 `covers` 和 `cgs`，列表页（`GameListView.vue`）只用 covers。

**改法**：列表接口只返回 covers，`cgs` 留空列表；详情接口才返回两者。
响应体也小一截——这是白送的收益。

---

## 3. 持续轮询开销

`useSessionRefresh`（`use-session-refresh.js:15`）首页传 `intervalMs: 5000`
（`LibraryView.vue:84`），对**每个 gameId 各发一个** `/api/session`。
首页 10 个游戏 = 每 5 秒 10 个请求。

每个 `/api/session`（`SessionController.java:59`）在 Linux 上做三件重活：

| 操作 | 代码位置 | 开销 |
|---|---|---|
| `sessions.status()` | `:62` | fork `systemctl` |
| `runner.portOpen(port)` | `:68` | TCP connect，300 ms 超时 |
| `windowProbe.present()` | `:74` | fork `xwininfo`，2 秒超时 |

即每 5 秒 10 个 `systemctl` + 10 个 `xwininfo` + 10 个 TCP 探测，**持续不断**。

**改法**：

1. 加批量接口 `GET /api/sessions?ids=a,b,c`，一次返回全部，服务端复用 §2.4 的批量 unit 状态。
2. 首页只在**存在 RUNNING 游戏**时才轮询——`anyRunning` computed 已存在
   （`LibraryView.vue:53`），但只喂给了 `useLatency`，没控制 session 刷新。
   没有运行中的游戏时，状态不可能自己变化。

---

## 4. HTTP 层

| 项 | 现状 | 改法 |
|---|---|---|
| gzip | 未开 | `server.compression.enabled: true`（§1.1） |
| HTTP/2 | 未配置 | `server.http2.enabled: true`，仅 HTTPS 生效；`GALWORLD_TLS=true` 时可用 |
| `/api/settings` | 无缓存头 | 加 `ETag` + `Cache-Control: private, max-age=30` |
| `/api/i18n/<locale>` | 123 KB 每次刷新重下 | 加 `ETag` + `max-age=3600` |
| 入口 JS | 无 preload | `index.html` 加 `<link rel="modulepreload">` |

**注意**：`api.js:144` 给**所有** `req()` 请求加了 `cache: 'no-store'`。
对 `/api/games` 是对的（列表要实时），但 `/api/settings` 和 `/api/i18n/*`
被同一个函数带着一起不缓存。这两个应该单独走，不受 `no-store` 约束。

---

## 5. 收益排序与实施顺序

| 优先级 | 项 | 位置 | 预估收益 |
|---|---|---|---|
| P0 | 推荐/hero 排序去 N+1 | §2.1 | 首页 API 从数千次查询降到个位数 |
| P0 | 路由懒加载 + 手动分包 | §1.2C | 首屏 JS 减半 |
| P0 | 开启 gzip | §1.1 | 传输量降 3–7 倍 |
| P0 | EP 按需 CSS | §1.2B | CSS 434 → 约 112 KB |
| P1 | 接线已有的批量方法 | §2.5 | 去掉 80 次全表扫描 |
| P1 | settings 加缓存 | §2.6 | 去掉 280 次单键查询 |
| P1 | playtime 批量聚合 | §2.2 | 去掉 200 次查询 |
| P1 | `scan.syncAll` 节流 | §2.3 | 去掉每页磁盘 IO |
| P1 | systemctl 批量 | §2.4 | 去掉每页 40 次 fork |
| P2 | session 批量接口 + 按需轮询 | §3 | 去掉持续进程开销 |
| P2 | HTTP/2 + 缓存头 | §4 | 二次访问几乎零请求 |

P0 全是低风险改动（配置 + 接线），先做。P1 需要改后端查询，要有测试覆盖。

---

## 6. 实施注意

1. **`scan.syncAll()` 节流会改变行为**：命令行新加的游戏最多延迟 5 秒出现。
   这是可接受的取舍，但要在文档里写明，避免用户以为「加了游戏不显示」。
2. **`byGame` / `byGames` 三个批量方法已存在但无人调用**（§2.5），
   先确认它们的返回结构满足 `toView` 需要，不要重写。
3. **`SettingsService` 加缓存后**，设置页保存必须清缓存，否则改完设置刷新页面仍是旧值。
   `SettingsService` 已有 `ApplicationEventPublisher`（`:16` 的 import），
   保存后发事件即可。
4. **路由懒加载会改变 `SpaFallbackController` 的行为边界**：
   它按路径清单回退 `index.html`（`SpaFallbackController.java:30-38`），
   清单必须与 `router.js` 一致。新增路由时要同步改两处，否则直接访问 404。
5. **媒体文件不走 gzip**：图片/视频已压缩，加进 `mime-types` 只会浪费 CPU。

---

## 7. 验收

1. 基线测量先做（§0），改动后同样命令复测，p50 改善幅度写进提交信息。
2. `curl -H 'Accept-Encoding: gzip' -s -o /dev/null -w '%{size_download}' <入口 JS URL>`
   → 应约 165 KB 而非 491 KB。
3. 构建后 `static/assets/` 下 CSS 总量 < 120 KB，首屏 chunk < 200 KB。
4. `/api/games?limit=40` 的 SQL 日志查询数 < 20 条（当前估算数百条）。
5. `/api/recommend?limit=10` 的 SQL 日志查询数 < 10 条（当前估算数千条）。
6. 列表页翻页时 `strace -f -e trace=execve` 无大量 `systemctl` 调用。
7. `mvn test` 与 `npm test` 通过。
8. 命令行新加游戏后 5 秒内出现在列表页（验证 §2.3 节流的取舍）。

---

## 8. 实施结果（2026-11-18）

环境：本机 Windows 开发机，H2 文件库，`GALWORLD_ROOT` 指向临时库，40 个构造游戏
（每个 1 张封面 + 1 个 CG + 派生图，无历史游玩记录），`org.hibernate.SQL=DEBUG`
统计日志行数，p50/p90 各跑 20 次。

| 指标 | 改动前 | 改动后 |
|---|---|---|
| 入口 JS | 491.93 KB（gzip 165.69 KB） | 160.98 KB（gzip 56.61 KB） |
| CSS 总量 | 434.47 KB（gzip 61.52 KB） | 109.76 KB（gzip ≈19 KB） |
| `GET /api/games?limit=40` | p50 92.8 ms，1004 条 SQL | p50 10.5 ms，13 条 SQL |
| 同上，翻到第二页（间隔 > 5 秒） | 每页重新全量扫盘 | 13 条 SQL（文件没变就不写库） |
| `GET /api/recommend?limit=10` | p50 19.0 ms，579 条 SQL | p50 7.3 ms，12 条 SQL |
| `GET /api/hero` | p50 7.3 ms，248 条 SQL | p50 3.8 ms，5 条 SQL |
| `GET /api/sessions?ids=g1,g2,g3` | 不存在（原本 3 个请求） | 1 条 SQL |
| `GET /api/i18n/<locale>` | 114 131 B，无缓存头 | gzip 28 501 B，ETag 命中回 304（0 B） |
| 入口 JS 传输 | 491 KB 未压缩 | gzip 生效（`text/javascript` 也要列入 mime-types） |

实测与方案的差异（都是踩到坑之后改的）：

1. **没有把 `element-plus` 放进 `manualChunks`。** 方案 §1.2C 的
   `'vendor-ep': ['element-plus']` 会让 tree-shaking 失效，整包 938 KB 打回首屏。
   只分 `vendor-vue`，EP 按引用自然摇树。
2. **ETag 必须是弱标签（`W/"..."`）。** Tomcat 的 `CompressionConfig` 见到强 ETag
   会跳过 gzip（强标签按未压缩字节算）。用 `ShallowEtagHeaderFilter` 也一样：
   它设置 Content-Length 后压缩失效。现在由控制器自己算弱 ETag 并处理 304。
3. **`/api/games` 的节流做了两层。** 只有 5 秒 TTL 不够：过期后的第一次请求依然
   全量重写 40 个游戏 + 重扫媒体目录（实测 293 条 SQL）。`GameScanService` 现在
   按 `game.env` 的「修改时间 + 大小」跳过未变化的游戏，并按媒体目录修改时间跳过
   `reconcile`，过期后的首次请求降到 13 条 SQL。取舍：外部直接改 `game.env`
   只要有 mtime/size 变化仍然会被发现；命令行新加的游戏最多延迟 5 秒出现。
4. **`SettingsService.get()` 也走快照缓存**（5 秒 TTL，保存时清空），
   否则只把 `toView` 改批量，其它调用点仍会按游戏逐个查设置。

验收对照：§7 的 1–8 项全部满足（第 6 项在 Linux 上由
`systemctl --user list-units 'galworld-game@*'` 一次取全部单元实现，本机 Windows
无法验证 fork 次数；Windows 后端仍按 pid 文件逐个判断）。

