# Gal World 优化需求方案

> 状态：**已确认，开始实施**
> 编写日期：2026-11-02
> 关联：`README.md` 的「能力」与 `docs/ENVIRONMENT.md`
> 样例：`demo/optimization-preview.html`

---

## 1. 需求清单与影响面

| # | 需求 | 类型 | 影响层 | 复杂度 |
|---|---|---|---|---|
| 1 | 游戏页隐藏端口信息 | 前端展示 | 前端 | 低 |
| 2 | 公司分组（**多对多**，独立于标签）+ 首页按公司筛选 | 数据模型 + 前端 | 后端 + 前端 + DB | 中 |
| 3 | 「会话由 systemd 托管」文案润色 | 文案 | 前端 | 低 |
| 4 | 封面多图轮换（库页 + 详情页） | 数据模型 + 前端 | 后端 + 前端 | 中 |
| 5 | 宣传 CG 多 CG 轮换（不分档位） | 数据模型 + 前端 | 后端 + 前端 | 中高 |
| 6 | 游戏页右上角设置按钮 → 编辑页 | 新页面 + API 扩展 | 后端 + 前端 | 高 |
| 7 | 轮换规则：全局 + 单游戏定制，支持秒/分/时 | 设置 + 前端 | 后端 + 前端 | 中 |
| 8 | 媒体手动排序 | 数据模型 + 前端 | 后端 + 前端 | 中 |

---

## 2. 逐项方案

### 需求 1：游戏页隐藏端口信息

**现状**：详情页副标题显示 `1280×720 · 端口 :9002 · 状态 运行中`。

**改动**：

- 详情页移除 `端口 :9002`
- 库页卡片也移除端口（界面只留分辨率、时长）
- 端口改在**编辑页**（需求 6）里展示，作为技术信息

**涉及**：`GameView.vue` 副标题、`LibraryView.vue` 卡片 meta。

---

### 需求 2：公司分组（多对多）

**设计**：独立实体 `Company`，与 `Tag` 平级但语义不同 —— 标签是多对多自由分类，公司是**多对多开发方归属**（一个游戏可能由多家公司联合开发，一家公司下有多个游戏）。

```java
@Entity
class Company {
  Long id;
  String name;        // 唯一
  String nameJa;      // 原名（可选，如「まどそふと」）
  String logoPath;    // 公司 logo（可选）
}
```

`Game` 新增：

```java
String companyId;   // 单一归属，null 表示未分类
```

**API**：

```
GET    /api/companies                     列出（含每个公司下的游戏数）
POST   /api/companies                     新建
PUT    /api/companies/{id}                改名
DELETE /api/companies/{id}                删除（仅解除关联，游戏不受影响）
POST   /api/games/{id}/companies          设置游戏的公司集合
GET    /api/games?company=<name>          按公司筛选
```

**首页**：

- 标签栏上方新增一行「公司」筛选条
- 公司与标签**互斥**（选公司时清空标签，反之亦然），避免组合语义混乱
- 卡片上显示公司名（多家时用 `×` 连接，如「まどそふと × 白井木学园」）

**数据迁移**：现有两个游戏没有公司关联，用户在编辑页手动指定。

---

### 需求 3：文案润色

**现状**：

> 会话由 systemd 托管：离开页面不会中断游戏，回来可继续。

**问题**：`systemd` 是实现细节，与玩家无关。

**改为**：

> 离开页面不会中断游戏，随时回来都能继续。

（若需强调后台运行，可加一句「游戏在服务器上持续运行，不占用你的电脑」。）

---

### 需求 4：封面多图轮换

**现状**：单个 `coverPath`，固定文件名 `cover.<ext>`。

**设计**：封面改为**图片列表**，详情页与库页卡片按固定间隔轮换。

```java
@Entity
class GameMedia {
  Long id;
  String gameId;
  String kind;        // cover | cg
  String path;        // 相对 URL，含版本参数
  int sortOrder;      // 轮换顺序
  Instant createdAt;
}
```

**行为**：

- 保留 `Game.coverPath` 作为「主封面」（列表排序/OG 图/首帧）
- 多图存在时，详情页与卡片每 6 秒淡入淡出切换
- **尊重 `prefers-reduced-motion`**：系统开了「减少动态效果」就静止显示首图
- **页面不可见时暂停轮换**（`visibilitychange`），不空耗 CPU

**上限**：每游戏封面最多 8 张（避免列表页负担）。

---

### 需求 5：宣传 CG 多 CG 轮换

**现状**：三档（`cg` / `cg-high` / `cg-low`）各一个文件。

**设计**：每档改为**列表**，同一档位内多 CG 轮换；档位仍按网络/屏幕择优。

```
cg-high/  →  [cg-high-1.mp4, cg-high-2.mp4]
cg/       →  [cg-1.mp4, cg-2.mp4]
cg-low/   →  [cg-low-1.mp4]
```

**轮换策略**（关键，避免拖累核心功能）：

| 项 | 规则 |
|---|---|
| 播放一个 | 播完一个再切下一个，**不预加载**下一个视频 |
| 时长 | 图片 6 秒；视频播完即切（上限 30 秒） |
| 切档 | 换档位时立即切到该档第一项 |
| 失败 | 任一 CG 加载失败 → 跳过该条，继续下一条；全失败 → 退回封面 |
| 可见性 | 页面隐藏 / 滚出视野 → 暂停，回来从当前条继续 |
| 内存 | 同时只持有一个 `<video>` 元素，切换时复用并换 `src` |

**上限**：每档最多 5 个，单文件仍 120 MB（视频）/ 8 MB（图片）。

**存储路径**（与需求 6 一致）：

```
games/<id>.meta/media/cg-high-1.mp4
games/<id>.meta/media/cg-high-2.mp4
```

---

### 需求 6：游戏页右上角设置按钮 → 编辑页

**路由**：`/game/<id>/edit`

**入口**：详情页右上角齿轮按钮。

**编辑内容**：

| 字段 | 类型 | 说明 |
|---|---|---|
| 游戏名称 | 文本 | 写入 DB；**不同步 game.env**（见下） |
| 游戏分辨率 | 宽 × 高 | 需重启会话生效 |
| 简介 | 多行文本 | DB |
| 公司 | 下拉 + 可新建 | DB |
| 标签 | 多选 | DB |
| 封面 | 多图上传 / 删除 / 排序 | 文件 + DB |
| 图标 | 单图 | 文件 + DB |
| 宣传 CG | 分三档，各档多文件 | 文件 + DB |

**服务器路径展示**（只读，便于运维定位文件）：

```
游戏实体    ~/galworld/games/sample-game/sample-game.exe
封面        ~/galworld/games/sample-game.meta/media/cover-1.png
图标        ~/galworld/games/sample-game.meta/media/icon.png
宣传 CG     ~/galworld/games/sample-game.meta/media/cg-high-1.mp4
```

**API**：

```
GET    /api/games/{id}/edit          编辑视图（含绝对路径、全部媒体列表）
PATCH  /api/games/{id}               改名称/分辨率/简介/公司
POST   /api/games/{id}/media          上传（kind + 档位 + 自动编号）
DELETE /api/games/{id}/media/{mid}    删单个媒体
POST   /api/games/{id}/media/reorder  排序
```

---

## 3. 关键技术决策

### 3.1 游戏名称：DB 是唯一真相，不再写回 game.env

**现状问题**：`GameScanService.syncAll()` 每次列表都把 `GAME_NAME` 从 `game.env` 覆盖到 DB，所以在门户里改名会被下次扫描冲掉。

**方案**：

- 名称改存 DB，`game.env` 里的 `GAME_NAME` 降级为「初始值」
- 扫描时：若 DB 已有该游戏且名称被用户改过（有 `nameOverridden` 标记），不覆盖
- 或更简单：扫描只在**新游戏**时读 `GAME_NAME`，已存在的游戏不覆盖名称

选后者，实现更简单，语义清晰。

### 3.2 分辨率改动需要重启会话

`WIDTH`/`HEIGHT` 同时影响 `Xvfb` 屏幕尺寸与 Selkies 采集区域。改动后必须重启会话才生效。

编辑页保存分辨率时：

- 会话在跑 → 提示「需重启会话生效」，提供「立即重启」按钮
- 会话停止 → 下次启动生效

同时要写回 `game.env`（`run-game.sh` 从那里读）。

### 3.3 媒体文件命名与轮换顺序

```
cover-1.png  cover-2.jpg      封面（按编号轮换）
icon.png                       图标（单张，固定名）
cg-high-1.mp4 cg-high-2.mp4    高清档
cg-1.mp4                       标准档
cg-low-1.webp                  流畅档
```

**编号即轮换顺序**，删除后重排（删 `cover-2` 时把 `cover-3` 改成 `cover-2`）。

### 3.4 兼容现有数据

现有文件：

```
games/sample-game.meta/media/cover.png        → cover-1.png
games/sample-game.meta/media/cg.mp4           → cg-1.mp4
games/sample-game.meta/media/cg-high.mp4      → cg-high-1.mp4
games/sample-game.meta/media/cg-low.png       → cg-low-1.png
```

迁移脚本做一次重命名 + 建 `GameMedia` 记录。DB 现有 `coverPath`/`cgPath`/`cgHighPath`/`cgLowPath` 保留，作为「主版本」缓存，与 `GameMedia` 首项同步。

---

## 4. 数据模型变更

```sql
-- 新增
CREATE TABLE company (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL UNIQUE,
  name_ja VARCHAR(128),
  logo_path VARCHAR(500),
  created_at TIMESTAMP
);

CREATE TABLE game_media (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  game_id VARCHAR(64) NOT NULL,
  kind VARCHAR(16) NOT NULL,      -- cover | cg-high | cg | cg-low
  path VARCHAR(500) NOT NULL,     -- 相对 URL
  sort_order INT NOT NULL,
  created_at TIMESTAMP,
  INDEX idx_media_game (game_id, kind, sort_order)
);

-- 修改
ALTER TABLE game ADD COLUMN company_id BIGINT;
```

现有表不变的部分：`tag`、`game_tag`、`play_session`、`setting`。

---

## 5. 前端页面变更

| 页面 | 变更 |
|---|---|
| `/` 游戏库 | 新增公司筛选条；卡片显示公司名；封面多图轮换 |
| `/game/<id>` | 移除端口；文案润色；右上角齿轮；封面/CG 多图轮换 |
| `/game/<id>/edit` | **新页面**：名称/分辨率/简介/公司/标签/封面/图标/CG + 服务器路径 |
| `/settings` | 不变 |

**轮换组件**（新增 `MediaRotator.vue`）：

- 输入：媒体列表、类型（图片/视频）、轮换间隔
- 输出：淡入淡出轮换
- 复用现有 `CgShowcase` 的可见性检测与失败降级逻辑

---

## 6. 实施顺序

| 阶段 | 内容 | 依赖 |
|---|---|---|
| **P1** | 需求 1（隐藏端口）+ 需求 3（文案） | 无 |
| **P2** | 需求 2（公司分组） | DB 迁移 |
| **P3** | 需求 4（封面多图）+ 需求 5（CG 多图） | `GameMedia` 表 |
| **P4** | 需求 6（编辑页） | P2、P3 完成后才有完整字段 |

建议顺序：**P1 → P2 → P3 → P4**。

P1 是纯前端，可立即见效；P4 依赖前面所有数据模型，放最后。

---

## 7. 风险与取舍

| 风险 | 影响 | 应对 |
|---|---|---|
| 轮换动画在低端设备上耗 CPU | 列表页卡顿 | 详情页轮换，列表页默认静止；尊重 `prefers-reduced-motion` |
| 多视频轮换占内存 | 浏览器卡 | 同一时刻只挂一个 `<video>`，切换时换 `src` |
| 改名被扫描覆盖 | 用户改完又变回去 | 扫描只对新游戏读 `GAME_NAME` |
| 分辨率改动未重启 | 画面尺寸不符 | 保存时明确提示并提供重启按钮 |
| 媒体文件增多 | 磁盘占用 | 单文件上限保持，删除时清理文件 |
| `GameMedia` 与 `Game.coverPath` 双写 | 不一致 | 明确：`GameMedia` 是真相，`coverPath` 是首项缓存，写操作后同步 |

---

## 8. 验收标准

1. 详情页不出现「端口」字样
2. 首页可按公司筛选，与标签筛选互斥
3. 详情页文案无 `systemd` 等实现细节
4. 上传 2 张封面 → 详情页与卡片每 6 秒轮换
5. 同一档位上传 2 个 CG → 播完一个切下一个，不预加载
6. 详情页右上角齿轮 → 编辑页，能改名称/分辨率/简介/公司/标签/封面/图标/CG，并显示各自服务器绝对路径
7. 改名后刷新列表，名称保持用户设置的值

---

## 9. 需求确认结果（2026-11-02）

| # | 原待确认项 | 结论 |
|---|---|---|
| 1 | 公司归属 | **多对多**。一个游戏可由多个公司联合开发，一个公司下有多个游戏 |
| 2 | 封面轮换范围 | **库页卡片与详情页都轮换**。默认显示置顶封面，其余静默加载完成后逐步加入轮换，避免列表卡顿 |
| 3 | CG 轮换 | **取消分档位**，只保留一个 CG 列表；加载与轮换规则与封面一致 |
| 4 | 编辑页删除游戏 | **不支持**，删除功能后续在独立的管理页面实现 |
| 5 | 分辨率 | **固定列表 + 自动识别**。游戏支持的优先，不支持的选项直接隐藏，避免选到后画面撕裂 |
| 6 | 轮换时间 | 支持**自定义秒数**（自填），不只是预设选项 |
| 7 | 轮换规则作用域 | 支持**全局设置 + 单游戏定制**；默认走全局，单游戏定制后走定制配置 |
| 8 | 存档备份 | **保留在详情页**直接操作，不移到编辑页 |
| 9 | 媒体排序 | 封面与 CG 均需**手动排序**，顺序决定「顺序轮换」的播放次序 |

### 9.6 媒体排序

「顺序」轮换按 `sort_order` 升序播放，所以必须有排序入口，否则用户无法决定播放次序。

**交互**：编辑页每个媒体项左侧有拖拽手柄，拖动即改顺序；也可用 ↑↓ 按钮（键盘/触屏友好）。

**存储**：`game_media.sort_order` 从 0 开始连续编号。重排后整体重写该游戏该档位下所有记录的 `sort_order`，避免出现空洞或重复。

**API**：

```
POST /api/games/{id}/media/reorder
Body: { "kind": "cover", "ids": [12, 9, 15] }   // 按新顺序给出媒体 id
```

服务端按数组下标重写 `sort_order = index`，并同步更新 `Game.coverPath` 为第一项。

**首项语义**：`sort_order = 0` 的封面是「置顶封面」——库页首屏立即加载它，其余静默预加载。

### 9.1 公司改为多对多

```sql
CREATE TABLE company (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL UNIQUE,
  name_ja VARCHAR(128),
  logo_path VARCHAR(500),
  created_at TIMESTAMP
);

CREATE TABLE game_company (
  game_id VARCHAR(64) NOT NULL,
  company_id BIGINT NOT NULL,
  PRIMARY KEY (game_id, company_id)
);
```

筛选语义：`?company=まどそふと` 返回该公司的**全部**游戏（含与其他公司联合开发的）。

### 9.2 轮换规则的作用域

```sql
-- 全局默认（存在 setting 表）
rotation.mode      = seq | random
rotation.interval  = 6      -- 单位秒存储，前端按 秒/分/时 换算展示

-- 单游戏覆盖（game 表新增，null = 跟随全局）
rotation_mode      VARCHAR(16)
rotation_interval  INT      -- 秒
```

前端解析顺序：`游戏值 ?? 全局值`。

**编辑页交互**（按需求确认）：

- 默认只显示「☑ 应用全局轮换规则（顺序 · 6 秒）」一行
- **取消勾选后**才展开自定义区（切换方式 + 数值 + 单位下拉）
- 时间单位支持 **秒 / 分 / 时**，统一按秒存储，范围 1 秒 – 24 小时
- 单位换算超上限时自动收敛并提示

**设置页**：全局默认值同样带单位下拉，作用于所有未定制的游戏。

### 9.3 封面轮换的加载策略（库页关键）

```
1. 首屏只渲染 cover-1（置顶封面），立即可见
2. 其余封面在空闲时（requestIdleCallback）逐个预加载
3. 每张加载完成后追加进轮换列表
4. 未加载完的封面不参与轮换，避免切换时出现空白
```

这样列表页即使有 8 张封面，首屏也不会等待。

### 9.4 分辨率自动识别

`run-game.sh` 启动时游戏会按自身支持的分辨率建窗。做法：

- 首次启动后，`xwininfo` 读实际窗口尺寸，写入 `game.env` 的 `WIDTH/HEIGHT`
- 编辑页下拉只列：**检测到的实际分辨率** + 已知安全的通用档（1280×720、1920×1080、1024×768）
- 若某档与检测值差异过大（比例不是 16:9/4:3），直接隐藏并提示

### 9.5 存档备份位置

详情页保留完整的存档面板（备份 / 恢复 / 删除），不迁移到编辑页。编辑页只负责游戏展示元数据。
