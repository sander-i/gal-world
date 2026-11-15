# 门户界面优化方案（设置拆分 / 首页布局 / 主题系统）

> 状态：**待评审**
> 编写日期：2026-11-03
> 关联：`docs/OPTIMIZATION-PLAN.md`（性能）
> 范围：`portal/frontend` 全部页面 + `SettingsController` 拆分

---

## 1. 现状盘点

| 项 | 事实 | 位置 |
|---|---|---|
| 设置页规模 | 1075 行单文件，9 个分区，纵向滚动 | `SettingsView.vue` |
| 设置后端 | 单一 `Map<String,String>`，19 个键，无分组、无鉴权 | `SettingsService.java` |
| 运维接口 | 全部挂在 `/api/settings` 下，与用户偏好同权 | `SettingsController.java:14` |
| 首页布局 | Hero 横幅 + 两行筛选 + `auto-fill minmax(250px,1fr)` 网格 | `LibraryView.vue` / `styles.css:112` |
| 主题 | `:root` 仅有 22 个变量；`styles.css` 另有 112 处硬编码色值（实施时实测 165 处，含 `rgba` 与渐变） | `styles.css:1-27` |
| 元素库 | Element Plus 深色变量在 `main.js` 硬加 `html.dark` | `main.js` |

**关键判断**：设置页里混了三类东西——用户偏好（切页方式、CG 声音）、内容策略（上传上限、轮换规则）、系统运维（环境探针、一键部署、下载源、派生图清理）。第三类会改变服务端行为、磁盘占用甚至系统状态，不该和「换页方式」并排放在一个用户能随手改的页面上。

---

## 2. 目标

1. **设置页瘦身**：只保留「改了只影响我自己看」的项。
2. **新增后台管理页 `/admin`**：集中放会改变服务端状态 / 磁盘 / 系统配置的项，独立入口 + 独立鉴权。
3. **首页支持大游戏量**：10–100+ 款游戏时，无需纵向滚动即可浏览与操作。
4. **主题系统**：内置 3–5 套主题 + 用户自定义主题（可导入导出），全站配色走 CSS 变量。

---

## 3. 设置拆分

### 3.1 按「影响面」分组

| 设置项 | 影响范围 | 归属 |
|---|---|---|
| `switchMode` | 仅前端跳转行为 | **设置页** |
| `cgSound` | 仅播放器默认值 | **设置页** |
| `defaultMediaMode` | 仅详情页默认展示 | **设置页** |
| `rotationEnabled` / `coverRotation*` / `cgRotation*` | 展示行为 | **设置页** |
| `idleStopMinutes` | 会停止服务端会话 | **设置页**（保留，但标注「会停止游戏进程」） |
| `coverMaxCount` / `cgMaxCount` / `imageMaxMb` / `videoMaxMb` | 改变上传校验，影响磁盘 | **后台管理** |
| `imageVariantEnabled` | 触发全库派生图回填/清理，影响磁盘与 CPU | **后台管理** |
| `artifactNexusBase` / `artifactMirrors` | 改变下载源，影响供应链 | **后台管理** |
| 环境探针（`/api/environment`） | 只读取系统状态 | **后台管理** |
| 一键部署（`/api/deploy/*`） | 执行系统命令、装驱动、改防火墙 | **后台管理** |
| 派生图清理（`/api/settings/image-variants`） | 删除文件 | **后台管理** |
| 版本检查（`/api/update/*`） | 只读 | **后台管理** |
| 「关于」 | 只读 | 设置页底部保留一版简短版本号 |

`idleStopMinutes` 有歧义：用户理解为「省电」，实际会 `systemctl stop` 游戏会话。保留在设置页，但描述改为「离开游玩页 N 分钟后自动停止游戏进程（会结束游戏，进度可能丢失）」。

### 3.2 后端拆分

新增 `AdminSettingsController`，把运维类键从 `SettingsController` 摘出来：

```
GET    /api/admin/status        环境 + 系统信息 + 版本（合并原 /api/environment、/api/health、/api/update/check）
GET    /api/admin/settings      只返回运维类键
POST   /api/admin/settings      只接受运维类键（白名单）
DELETE /api/admin/image-variants
GET    /api/admin/image-variants
POST   /api/admin/deploy/*      一键部署（沿用现有 DeployController 路径）
```

**鉴权**：所有 `/api/admin/**` 复用 `DeploySecurity`（`X-GalWorld-Deploy-Token` + 来源网段）。前端在后台页面首次访问时要求输入令牌，存 `sessionStorage`（不是 `localStorage`——关浏览器即失效）。

**兼容**：`/api/settings` 的 `all()` 仍返回全部键（`GameEditView` 读上传上限依赖它），但 `POST /api/settings` 增加白名单，拒绝运维类键（返回 400「该设置在后台管理中修改」）。这样旧前端不会静默改掉运维项。

### 3.3 前端拆分

```
views/SettingsView.vue        保留 ~350 行：切换方式、CG 声音、空闲停止、轮换规则、游戏页展示、主题、关于
views/AdminView.vue           新增：上传限制、图片预加载、下载源、环境探针、一键部署、版本更新
components/settings/
  SettingSection.vue          统一分区外壳（标题 + 说明 + 内容槽）
  SettingToggle.vue           seg 三态开关（现有多处重复）
  SettingNumber.vue           数字输入 + 范围提示 + 失焦保存
  settings-nav.js             导航项定义（两个页面共用渲染逻辑）
```

后台页顶部加醒目提示条：「此页面的修改会影响所有用户与服务器状态」。

---

## 4. 首页与列表页分离

### 4.1 问题拆解

10 款游戏时，首屏被 Hero（约 300px 高）+ 两行筛选（约 120px）吃掉，网格只剩一行可见。用户要滚动才能找到第 5 款之后的游戏。

更根本的问题是**首页承担了两件事**：推荐今天玩什么、以及浏览全部库存。两者的最优布局冲突——推荐要克制、浏览要密集。

### 4.2 方案：首页推荐 10 款，全部游戏独立列表页

**A. 页面分工**

| 页面 | 路由 | 职责 | 布局 |
|---|---|---|---|
| 首页 | `/` | 推荐 | Hero + 「为你推荐」固定 10 款 |
| 全部游戏 | `/games` | 浏览 / 筛选 / 搜索 | 筛选面板 + 密度切换 + 无限滚动 |
| 设置 | `/settings` | 个人偏好 + 主题 | 不变 |
| 后台 | `/admin` | 运维 | 不变 |

首页不再放筛选、密度切换、全量网格。顶栏「全部游戏」与首页「为你推荐」右侧的「全部游戏 →」都指向 `/games`。

**B. 首页：Hero + 2×5 固定网格**

```
┌────────────────────────────────────────┐
│  Hero（高度固定 190px）                 │
└────────────────────────────────────────┘
为你推荐  按推荐算法排序的前 10 款   全部游戏 →
┌─────┐┌─────┐┌─────┐┌─────┐┌─────┐
│  1  ││  2  ││  3  ││  4  ││  5  │
└─────┘└─────┘└─────┘└─────┘└─────┘
┌─────┐┌─────┐┌─────┐┌─────┐┌─────┐
│  6  ││  7  ││  8  ││  9  ││ 10  │
└─────┘└─────┘└─────┘└─────┘└─────┘
```

网格固定 5 列（`repeat(5, minmax(0, 1fr))`），响应式降到 4 / 3 / 2 列。不随视口宽度漂移，保证「第一行 5 个、第二行 5 个」的节奏稳定。

**C. 推荐顺序由后端决定，前端只按数组顺序渲染**

新增 `GET /api/recommend?limit=10`：

```json
[{
  "gameId": "demo-game",
  "name": "示例游戏",
  "covers": [{ "url": "/media/demo-game/cover-0.png", "video": false }],
  "tags": ["奇幻", "悬疑"],
  "companies": [{ "id": 1, "name": "示例会社", "website": "" }],
  "state": "STOPPED",
  "totalSeconds": 152340,
  "reason": "最近游玩"
}]
```

前端不排序、不去重、不补位。返回不足 10 条就少显示，不拿别的游戏凑数。返回空数组时首页显示一行「还没有游戏，运行 `galworld add` 注册」。

当前占位实现可复用 `HeroService` 的逻辑（最近游玩 > 时长最长 > 新加入），后续替换为自定义推荐算法时**只改后端一个类**，前端与接口契约不动。

**D. Hero 档位简化**

首页固定展示 10 款，游戏总数不再影响首屏密度，原来的三档推导失去意义。改为两档：

| 档位 | 高度 | 说明 |
|---|---|---|
| 中（默认） | 190px | 现状 `--hero-medium-h` |
| 关闭 | — | 只在「为你推荐」上方留一条 72px 的「继续游玩 某某」横条 |

设置键 `homeHeroSize ∈ {medium, off}`，默认 `medium`。原方案里的 `auto`/`large`/`small` 删除。

**E. 列表页：筛选 + 密度 + 无限滚动**

- **筛选面板**：公司 / 标签两行，标签行默认折叠为「标签 ▾ (6)」；面板 `position: sticky; top: 0`，滚动时保持可见
- **密度切换**：`data-density` 三档（标准 250px / 紧凑 168px / 列表横行），存 `localStorage['galworld.density']`
- **无限滚动**：每批 40 条，`IntersectionObserver` 监听底部哨兵（`rootMargin: 200px`）。到底显示「已经到底了」
- **计数**：「共 N 款 · 已显示 M」

后端复用现有 `GET /api/games?tag=&company=&q=`，加 `offset` / `limit`：

```java
@GetMapping
public List<GameView> list(@RequestParam(required = false) String tag,
                           @RequestParam(required = false) String company,
                           @RequestParam(required = false) String q,
                           @RequestParam(defaultValue = "0") int offset,
                           @RequestParam(defaultValue = "40") int limit)
```

`limit` 上限 200，防止前端传大值拖垮服务。响应体保持数组，总数放在 `X-Total-Count` 响应头（避免改现有调用方解析结构）。

**F. 首屏验收**

1440×900、1600×1100 两个视口：首页 Hero + 10 张卡**全部在一个屏幕内，无纵向滚动**。列表页标准密度首批 12 张卡可见 ≥ 6 张。

**G. 虚拟滚动（不在本期）**

列表页 `auto-fill` 网格在 500 款以内 DOM 约 6000 节点，浏览器渲染无压力。`MediaRotator` 已用 `IntersectionObserver` 暂停不可见轮播，图片 `fetchpriority=low`，首屏不会随数量线性变慢。虚拟滚动留给 > 500 款。

---

## 5. 主题系统

### 5.1 现状问题（实施前快照）

- `:root` 里 22 个变量，但 `styles.css` 另有 **112 处硬编码色值**（`#12151a`、`#cfd8e6`、`#1f242d`…），换主题时这些不会跟着变，会花屏。
- `main.js` 硬写 `document.documentElement.classList.add('dark')`，Element Plus 永远深色。

> **实施结果（已完成）**：实际替换 165 处（含 `rgba()` 与渐变），`:root` 扩到 **76 个变量**。原计划的 112 处按 `#RRGGBB` 计，实施时把 `rgba(...)`、`linear-gradient(...)`、`element-plus` 覆盖块一并纳入，故数量更大。校验脚本从"grep 字面色值"改为 `scripts/check-theme-vars.mjs`：跳过 `:root` 变量定义块（变量表本身必须写色值），其余任何字面色值即失败。

### 5.2 方案

**A. 变量收敛（前置改造，必须先做）**

把硬编码按语义归类到变量。实际落地的 76 个变量分五组：

| 组 | 变量 |
|---|---|
| 基础层 | `--bg` `--surface` `--surface-2` `--surface-3` `--surface-deep` `--surface-hover` `--line` `--line-strong` |
| 文字层 | `--text` `--text-dim` `--muted` `--muted-soft` `--placeholder-ink` |
| 强调与状态 | `--accent` `--accent-hi` `--accent-lo` `--accent-dim` `--accent-ink` `--on-accent` `--ok` `--warn` `--err` `--err-deep` `--err-hi` `--err-ink` |
| 复合派生 | `--accent-ghost/-faint/-soft/-ring/-line*`（7 个）、`--ok-soft/-faint/-ghost/-line*`（5 个）、`--warn-soft/-faint/-line*`（4 个）、`--err-soft/-faint/-ghost/-line*`（6 个）、`--muted-soft-bg` `--muted-soft-line` `--cover-gradient` `--hero-body-gradient` `--glass-bg` `--overlay` |
| 媒体/灯箱（固定色，不跟主题） | `--media-canvas` `--media-glass` `--media-glass-strong` `--media-glass-soft` `--media-line` `--media-ink` `--media-ink-strong` `--dot-idle` `--lightbox-bg` `--lightbox-scrim` `--lightbox-btn` `--lightbox-btn-hover` |
| 尺寸 | `--radius` `--r-ctl` `--ctl-h-sm/-md/-lg` `--ctl-fs-sm/-md/-lg` `--shadow` `--shadow-lg` `--card-min` |

前四组跟随主题；**媒体/灯箱组是固定色**——它们压在图片或视频上，若随浅色主题变白会导致控件不可见，因此整个主题体系里唯一刻意不变量化的一组。

规则：`styles.css` 除 `:root` 定义块外不再出现字面色值。校验：`npm run check:theme`。

> **与原计划的偏差**：原计划写的是"112 处 → 0、22 个变量"，实际是"165 处 → 0、76 个变量"。原计划里的 `--info` 与 `--media-placeholder` 未落地——前者全站没有用到，后者由 `--cover-gradient` 覆盖。

**B. 主题数据结构**

```js
// theme.js
export const BUILTIN_THEMES = [
  { id: 'obsidian', name: '黑曜石', base: 'dark',  builtin: true, vars: {} },  // 空 = 用 :root 默认
  { id: 'midnight', name: '深海',   base: 'dark',  builtin: true, vars: deriveVars({...}) },
  { id: 'sakura',   name: '樱',     base: 'dark',  builtin: true, vars: deriveVars({...}) },
  { id: 'paper',    name: '素纸',   base: 'light', builtin: true, vars: deriveVars({...}) },
]
// 自定义主题：{ id: 'custom-<uuid>', name: string, base: 'dark'|'light', vars: {...} }
```

应用方式：`applyTheme` 逐项 `setProperty` 并记账（CSSStyleDeclaration 不把自定义属性暴露在 `Object.keys` 里，必须自己维护一个 `appliedKeys` 数组才能在下一次切换时清理），同时写 `documentElement.dataset.theme` / `dataset.themeBase`，并据 `base` 增删 `html.dark`、设置 `style.colorScheme`。

**浅色主题要点**：`html { color-scheme }` 必须跟随主题切换（`dark` / `light`），否则原生下拉、滚动条、日期面板仍为深色。Element Plus 同步 `html.dark` 类——已改由 `applyTheme` 统一管理，`main.js` 不再硬写。

**C. 自定义主题编辑器**

设置在设置页「外观主题」分区（它是用户偏好，不是运维项）：

- 从任一内置主题「另存为」→ 进入编辑面板
- 可编辑项收敛为 9 个语义色：`bg` `surface` `line` `text` `muted` `accent` `ok` `warn` `err`；其余主题变量由 `deriveVars` 按公式派生（`--accent-hi` = accent 与 text 的 `color-mix`，`--surface-2` = surface 与 text 的混合等），用户不需要调 76 个变量
- 固定色组（`--media-*` / `--lightbox-*`）不参与派生，始终沿用 `:root` 值
- 实时预览：右侧一个卡片 + 按钮 + 输入的迷你样例
- 导入 / 导出 JSON（一键复制，便于分享）
- 存储：`localStorage['galworld.theme']`（纯前端偏好，不进后端设置——主题不影响其他用户）
- 删除内置主题：禁止；删除自定义主题：允许，若正在使用则回退到 `obsidian`

**D. 首屏闪烁**

`index.html` 的 `<head>` 内加内联脚本，在任何 CSS 加载前读取 `localStorage` 并写入变量，避免「先黑后变」。

```html
<script>
  (function () {
    try {
      var t = JSON.parse(localStorage.getItem('galworld.theme') || 'null')
      if (!t || !t.vars) return
      var s = document.documentElement.style
      for (var k in t.vars) s.setProperty(k, t.vars[k])
      document.documentElement.dataset.theme = t.id || 'custom'
    } catch (e) {}
  })()
</script>
```

**E. 内置主题清单（4 套）**

| id | 名称 | 基调 | 用途 |
|---|---|---|---|
| `obsidian` | 黑曜石 | 现状深色 + 金强调 | 默认 |
| `midnight` | 深海 | 深蓝底 + 青强调 | 夜间 |
| `sakura` | 樱 | 暗紫底 + 粉强调 | 视觉偏好 |
| `paper` | 素纸 | 米白底 + 墨色文字 + 赭石强调 | 白天/投屏 |

---

## 6. 我额外建议的优化

按「收益/风险」排序。**全部 8 项已实施（2026-11-04）**，状态见下表。

| # | 问题 | 建议 | 状态 |
|---|---|---|---|
| 1 | 首页三处按钮都是文字链接（公司维护 / 设置），无后台入口 | 顶栏右侧改为图标组：搜索、主题切换、设置齿轮、管理盾牌（仅在有令牌时可见） | **已实施**（文字按钮版，未做图标组） |
| 2 | `MediaRotator` 在列表页每张卡都建 `IntersectionObserver` + 预加载队列，20 款以上时并发预加载次数线性上升 | 网格内卡片传 `enabled = rotationEnabled && 卡片可见`，并给预加载加全局并发上限（信号量 6） | **已实施**（并发上限 6） |
| 3 | 详情页「存档备份」面板在无存档时仍占大块空间 | 无存档时折叠成一行「暂无存档备份」+ 展开按钮 | **已实施** |
| 4 | 列表页公司筛选、标签筛选在 30+ 项时会撑成多行 | 超出 8 项时改为「前 8 个 + 更多 ▾」弹出面板 | **已实施**（行内折叠；选中项在折叠区外时自动展开） |
| 5 | 编辑页 936 行，三个媒体区（封面/图标/CG）逻辑重复 | 抽出 `MediaSection.vue`，三种类型传 `kind` 配置 | **已实施**（封面 + CG 抽出；图标单张、固定文件名，交互不同，未纳入） |
| 6 | 无 `/api/settings` 写入审计 | 运维类键的每次修改写 `log.info`（谁、哪个键、旧值 → 新值；不含令牌与 sudo 密码） | **已实施**（`AdminSettingsController.audit`，值未变不记） |
| 7 | 门户无任何登录；`/admin` 虽要令牌但 `/api/settings` 仍可被局域网任意改 | 若局域网不可信，给全部写接口加统一令牌中间件（配置项为空时保持现状，保证向后兼容） | **已实施**（`galworld.write.token`，默认空=不启用） |
| 8 | 首页推荐位与「继续游玩」可能推荐同一款游戏（Hero 用的也是 HeroService） | 推荐接口增加 `exclude` 参数，前端把 Hero 的 `gameId` 传进去，避免重复占位 | **已实施** |

### #7 的落地细节

- 配置项 `galworld.write.token`（环境变量 `GALWORLD_WRITE_TOKEN`），**留空时不校验**，升级上来的实例行为完全不变
- 拦截器只拦非 GET 的 `/api/**`；`/api/deploy/**` 与 `/api/admin/**` 自带部署令牌，跳过
- 前端令牌存 `localStorage['galworld.writeToken']`（本机访问凭据），与后台管理用 `sessionStorage` 的部署令牌分开存
- 上传走 XHR，单独补了请求头
- 配置启用后，403 时前端提示「该门户启用了写接口令牌，请在设置里填写」，而不是裸状态码

---

## 7. 验收标准

1. `npm run check:theme` 通过（`:root` 之外无字面色值）。
2. 设置页与后台页各自 ≤ 450 行（拆出组件后）。
3. `POST /api/settings` 带运维类键返回 400；`POST /api/admin/settings` 无令牌返回 403。
4. 首页在 1440×900 与 1600×1100 两个视口下：Hero + 10 张推荐卡**全部可见，无纵向滚动**。
5. 首页推荐顺序完全等于 `GET /api/recommend` 返回数组的顺序；接口返回 3 条时首页只显示 3 条，不补位。
6. 列表页首批 40 条（演示 12 条）在标准密度下首屏可见 ≥ 6 张卡；滚动到底自动加载下一批，加载完显示「已经到底了」。
7. 切换 4 套内置主题，所有页面无未着色区域（截图比对：卡片、按钮、输入、徽标、灯箱、设置页）。
8. 浅色主题下 `color-scheme: light` 生效，原生 `<select>` 展开为浅色。
9. 自定义主题刷新页面后仍生效，且无首屏闪黑（录屏或首帧截图验证）。
10. 既有前端测试 / 后端测试全部通过。
