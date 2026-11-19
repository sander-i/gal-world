# 页面跳转加载动效方案

> 状态：已实施（2026-11-19）
> 编写日期：2026-11-19
> 分支：`feat/loading-skeleton`
> 上游文档：`LOADING-UX-PLAN.md`（骨架屏与请求编排）
> 预览：`demo/route-loading-preview.html`
>
> **改动代码前先读 §4「关键实现决策」** —— 13 条都是踩过坑或被否掉过的方案，
> 每条对应一个会复发的 bug。

---

## 1. 问题

`LOADING-UX-PLAN.md` 解决的是**单页内的数据加载**：进页面后骨架块等接口。
它没覆盖**页面之间**那一段：点击卡片 → 新页面出现。

现状：

| 环节 | 现状 | 用户感知 |
|---|---|---|
| 点击卡片 | `.card:hover` 有位移，`click` 无任何反馈 | 不确定点没点上 |
| 路由切换 | `router.js` 全部 `() => import(...)`，首次进入要下载 chunk | 旧页面**完全静止**，像卡死 |
| 组件挂载 | `router-view` 无 `<transition>` | 新页面瞬间替换，硬切 |
| 数据加载 | 各页已有骨架 | 正常 |
| 滚动位置 | `router.js` 无 `scrollBehavior` | 从滚动过的列表进详情，详情页停在半截 |

关键点：**阻塞发生在 `router.push` 内部**。vue-router 4 在导航守卫阶段才解析
异步组件，所以 `import()` 没回来之前，旧页面一个像素都不动。局域网内通常
80–150 ms，冷缓存/反代下 300 ms 以上，观感就是「点了没反应」。

---

## 2. 方案总览

五层，按时间顺序叠。**每层都可单独关闭**，出问题只回退一层。

| 层 | 名称 | 起效时刻 | 作用 | 成本 |
|---|---|---|---|---|
| L0 | 点击确认 | 0 ms | 被点元素缩一下，告诉用户「收到了」 | 纯 CSS |
| L1 | 路由预取 | 悬停时 | 提前把目标 chunk 拉下来，多数跳转直接 0 等待 | ~15 行 |
| L2 | 全局进度条 | 导航起 80 ms | 兜底：预取没命中时也有持续反馈 | 一个组件 |
| L3 | 视图切换动效 | 组件挂载时 | 新页 180 ms 上浮淡入 | 一段 CSS |
| L4 | 骨架入场动效 | 骨架挂载后 | 骨架块逐行错峰淡入 + 流光成波浪 + 底色呼吸 | 一段 CSS + 分组 |

**为什么进度条要延迟 80 ms**：预取命中后跳转常在 100 ms 内完成。进度条一闪
而过比不出现更像故障。80 ms 是「人开始怀疑」的门槛之下、又赶在 150 ms 之前。

**进度条只覆盖 chunk 下载那一段。** 骨架属于目标组件，chunk 没解析完它根本
不存在。所以「点击后立刻看到目标页骨架」只在预取命中时成立；未命中时，点击到
骨架之间由 L0 缩放 + L2 进度条填补。这两段的分界就是 `afterEach` 触发的时刻，
组件挂载后各页自己的骨架期不归路由管。

**为什么新页用上浮淡入而不是整页淡出**：旧内容在等待期间仍然可读，淡出它是
白白浪费 90 ms。骨架已经承担了「新内容在路上」的表达。

**为什么骨架内部还要动。** L3 只把整个骨架页淡进来，骨架块本身是全亮静态的，
所有块共用一条同步流光。用户看到的是「一大片灰块」，读不出加载在推进。
L4 让块按视觉行错峰入场，把静态灰块变成有方向的推进感。

---

## 3. 逐层实现

### L0 点击确认

`styles.css`，与现有 `.card:hover` 的 `transform` 同一坐标系：

```css
.card:active { transform: translateY(-1px) scale(.995); transition-duration: .06s; }
.ghost-btn:active { transform: scale(.97); }
.hero:active { transform: translateY(-1px) scale(.998); }
```

注意 `.card` 现有 `transition: transform .18s`，`:active` 里覆写 `transition-duration`
才能让按下比抬起快。

### L1 路由预取

新建 `src/prefetch.js`（默认导出与 routes 之外的东西不动）：

`prefetch` 单独成模块，避免 `router.js → LibraryView → router.js` 的循环依赖；
`router.js` 创建实例后调用 `bindRouter(router)` 注入，模块本身不导入 `router.js`。

```js
const warmed = new Set()

/**
 * 悬停/聚焦时预热目标路由的异步组件。
 *
 * router.resolve() 不会解析 async component，matched[].components.default 仍是
 * loader 函数；主动调用一次即触发 import，Vite 会缓存该模块，随后 router.push
 * 拿到的就是已完成的 Promise。
 */
export function prefetch(href) {
  let r
  try { r = router.resolve(href) } catch { return }
  for (const m of r.matched) {
    const c = m.components?.default
    if (typeof c !== 'function' || warmed.has(c)) continue
    warmed.add(c)
    c().catch(() => warmed.delete(c))   // 失败就允许下次重试
  }
}
```

挂载点两处：

1. `<router-link>` 走全局委托，`App.vue` 的 `onMounted` 里一次注册：

```js
document.addEventListener('pointerover', (e) => {
  const a = e.target.closest?.('a[href]')
  if (!a || a.origin !== location.origin || a.target === '_blank') return
  prefetch(a.getAttribute('href'))
}, { passive: true })
```

2. 卡片与 Hero 是 `router.push` 而非 `<a>`，在**包裹卡片的宿主元素**上加 `@pointerenter`：

```vue
<article class="card" @pointerenter="prefetch(gameLink(g.gameId, listState))">
  <GameCard ... />
</article>
```

不能把 `@pointerenter` 直接写在 `<GameCard>` 上：它是多根节点组件（`.cover` + `.card-body`），
Vue 对多根组件不做属性透传，监听器会静默失效并产生告警。原因详见 §7 第 8 条。

**不做 idle 全量预取**：那等于放弃路由级 code split，首屏白付 3 个 chunk 的流量。
只预热「用户已经指向的目标」。

### L2 全局进度条

新建 `src/route-progress.js`（模块级状态，避开 router ↔ 组件的循环依赖）：

```js
import { reactive } from 'vue'

export const routeProgress = reactive({ active: false, value: 0 })

let showTimer = null
let trickleTimer = null
let hideTimer = null

export function begin() {
  clearTimeout(hideTimer)
  // 80ms 内完成就不出现，避免一闪
  showTimer = setTimeout(() => { routeProgress.active = true; step() }, 80)
  clearInterval(trickleTimer)
  trickleTimer = setInterval(step, 200)
  routeProgress.value = 8
}

/** 渐近推进：越接近 90% 越慢，永远不到 100% */
function step() {
  const v = routeProgress.value
  if (v >= 90) return
  routeProgress.value = v + (90 - v) * 0.18
}

export function done() {
  clearTimeout(showTimer)
  clearInterval(trickleTimer)
  if (!routeProgress.active) return        // 没露过面就别露了
  routeProgress.value = 100
  hideTimer = setTimeout(() => {
    routeProgress.active = false
    routeProgress.value = 0
  }, 220)
}
```

`router.js` 里接上（只挂 `beforeEach` / `afterEach` / `onError`，不写守卫逻辑）：

```js
router.beforeEach(() => { begin() })
router.afterEach(() => { done() })
router.onError(() => { done() })
```

组件 `src/components/RouteProgress.vue`，挂在 `App.vue` 里、`router-view` 之外：

```vue
<template>
  <div class="route-progress" :class="{ on: routeProgress.active }" aria-hidden="true">
    <i :style="{ width: routeProgress.value + '%' }" />
  </div>
</template>
```

样式进 `styles.css`（颜色全部走 `var()`，`check-theme-vars` 会拦字面值）：

```css
.route-progress {
  position: fixed;
  top: 0; left: 0; right: 0;
  height: 2px;
  z-index: 40;            /* 低于 Element Plus 遮罩，高于 .list-fixed(20) */
  pointer-events: none;
  opacity: 0;
  transition: opacity .18s ease;
}
.route-progress.on { opacity: 1; }
.route-progress i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, var(--accent-dim), var(--accent));
  box-shadow: 0 0 8px var(--accent-ring);
  transition: width .2s ease;
}
@media (prefers-reduced-motion: reduce) {
  .route-progress, .route-progress i { transition: none; }
}
```

`z-index: 40` 的选择：`.list-fixed` 是 20、`.toast` 是 10、Element Plus overlay 在
2000 上下、`.maintenance-mask` 是 9999。进度条不能盖住对话框与维护遮罩。

### L3 视图切换动效

`App.vue`：

```vue
<router-view v-slot="{ Component, route }">
  <!-- 游玩页是全屏 fixed 画布，过渡期间祖先的 transform 会改变它的包含块，
       整页会缩进一个内边距里。直接跳过过渡。 -->
  <component :is="Component" v-if="route.name === 'play'" />
  <!-- 不加 mode="out-in"：旧页在 chunk 到达时已经没有信息价值，
       等它淡出 90 ms 再进新页是纯等待。旧页立刻移除，新页自己淡入。 -->
  <transition v-else name="page">
    <!-- key 用 path 而不是 fullPath：列表筛选只改 query，
         用 fullPath 会让整个列表页重新挂载、丢失滚动位置 -->
    <component :is="Component" :key="route.path" />
  </transition>
</router-view>
```

```css
.page-enter-active { transition: opacity .18s ease, transform .18s ease; }
.page-enter-from   { opacity: 0; transform: translateY(6px); }
@media (prefers-reduced-motion: reduce) {
  .page-enter-active { transition: none; }
}
```

只有进场，没有离场：Vue 的默认（非 out-in）模式下旧节点会被立刻替换，
`.page-enter-*` 作用在新挂载的组件上，总时长就是 180 ms，且这 180 ms 里
用户看到的是**已经在加载的骨架**，不是空白。

需要交叉淡入（方案 C）时改成 `mode="out-in"` 并补 `.page-leave-*`，
代价见 §5。

### 附带：滚动位置

`router.js` 补 `scrollBehavior`：

```js
scrollBehavior(to, from, savedPosition) {
  if (savedPosition) return savedPosition       // 浏览器前进/后退
  if (to.path === from.path) return false       // 仅 query 变化（筛选），保持原位
  return { top: 0 }
}
```

不加这条，从滚动到第 6 屏的列表点进详情，详情页也停在第 6 屏，比任何动画都伤体验。

### L4 骨架入场动效

L3 只管到「骨架页整体淡入」。骨架内部此时是全亮静态块 + 一条同步流光，
15 个块共用同一条扫光，看上去像一条横带划过，读不出「哪里先到」。

新增逐行错峰：

```css
/* 进场：淡入 + 上浮 4px；延迟由 --i 决定 */
.fx-combo .page-skeleton.active .sk {
  animation: sk-in .36s cubic-bezier(.22,.8,.28,1) both;
  animation-delay: calc(var(--i, 0) * 26ms);
}

/* 流光错峰：从左上向右下推，形成波浪 */
.fx-combo .page-skeleton.active .sk::after {
  animation-delay: calc(var(--i, 0) * 55ms + 140ms);
}

@keyframes sk-in {
  from { opacity: 0; transform: translateY(4px); }
  to   { opacity: 1; transform: none; }
}
```

**`--i` 按视觉行编号，不按 DOM 顺序**（理由与分组算法见 D8）。
按 DOM 顺序时详情页 15 个块串成 390 ms 的长队，数据 450 ms 回来时还没铺完。

三个必须遵守的顺序约束，细节见 D9 / D10：

1. **先让页面可见再量位置。** `display: none` 下所有 `getBoundingClientRect().top`
   都是 0，分组会把全部块压成一行。
2. **写完 `--i` 要重放动画。** `active` 类加上时 CSS 动画已按旧的 `--i` 启动，
   改变量不会回溯。重置 `animation` 再读一次 `offsetWidth` 强制重排即可。
3. **呼吸与进场写同一条 `animation` 列表**（D11），分开写会互相覆盖。

成本：详情页 9 行 × 26 ms = 234 ms，小于数据 450 ms 的等待期。
列表页 4 行（筛选 chip 3 行 + 卡片 1 行）只要 78 ms。

`prefers-reduced-motion: reduce` 下整组关掉（含呼吸），只留静态块。

---

## 4. 关键实现决策

每条都写过一版、踩过坑或被否掉过。**改动这段代码前先读这一节**，
否则会重新引入已经修掉的 bug。

### D1 进度条只覆盖 chunk 下载，不覆盖数据加载

`beforeEach` / `afterEach` 包住的只有路由导航，也就是 `() => import()` 的
chunk 解析。组件挂载后各页自己的骨架期不归路由管。

后果：「点击后立刻看到目标页骨架」只在**预取命中**时成立。未命中时，点击到
骨架之间必须靠 L0 缩放 + L2 进度条填补，不能假装骨架已经在了。

否定方案：把进度条接到每页的 loading 状态上。那样每个页面都要往全局状态写，
而且进度条会拖到数据回来才收尾，看起来像导航卡住。

### D2 进度条延迟 80 ms 出现，未露面时不收尾

局域网跳转常在 100 ms 内完成，进度条一闪而过比不出现更像故障。

```js
showTimer = setTimeout(() => { shown = true; ... }, 80)
export function done() {
  if (!shown) return        // 没露过面就别露了
  ...
}
```

`shown` 这个标志不能省。只清定时器不判断，会出现「已经结束了才冒出来」。

### D3 只做进场动画，不做 `mode="out-in"`

旧页在 chunk 到达时已无信息价值。等它淡出 90 ms 再进新页是纯等待，
用户点十次就付十次。

`.page-enter-*` 作用在新挂载的组件上，总时长 180 ms，且这 180 ms 里用户
看到的是**已经在加载的骨架**，不是空白。

方案 C（交叉淡入）保留在预览页做对照，不进主线。

### D4 `<transition>` 跳过 `play` 路由

`.play-wrap` 是 `position: fixed; inset: 0`。过渡期间祖先元素带 `transform`，
`fixed` 子元素会相对该祖先定位而不是视口 —— 整页画布会缩进一个内边距里。

```vue
<component :is="Component" v-if="route.name === 'play'" />
<transition v-else name="page"> ... </transition>
```

同理 `.list-fixed` 是 `sticky`，过渡那 180 ms 内 sticky 会失效一帧。
实测无可见跳动，暂不特殊处理。

### D5 `key` 用 `route.path`，不用 `fullPath`

列表页改筛选只改 query。用 `fullPath` 做 key 会让整个列表页重新挂载：
丢滚动位置、重放过渡动画、重新发请求。

用 `path` 时 query 变化不触发重挂，`readListQuery(route)` 的 watch 自己处理。

### D6 `scrollBehavior` 必须补，且 `to.path === from.path` 时返回 `false`

```js
scrollBehavior(to, from, savedPosition) {
  if (savedPosition) return savedPosition
  if (to.path === from.path) return false    // 只改 query，保持原位
  return { top: 0 }
}
```

不补这条，从滚动到第 6 屏的列表点进详情，详情页也停在第 6 屏 —— 比任何
动画都伤体验。返回 `false` 而不是 `{ top: 0 }` 是为了筛选时不被拉回顶部。

### D7 预取只做悬停，不做 idle 全量

idle 时把所有路由 chunk 都拉下来，等于放弃路由级 code split，首屏白付
3 个 chunk 的流量。只预热「用户已经指向的目标」。

```js
const warmed = new Set()
export function prefetch(href) {
  for (const m of router.resolve(href).matched) {
    const c = m.components?.default
    if (typeof c !== 'function' || warmed.has(c)) continue
    warmed.add(c)
    c().catch(() => warmed.delete(c))    // 失败允许下次重试
  }
}
```

`router.resolve()` **不会**解析 async component，`components.default` 仍是
loader 函数；主动调用一次即触发 import，Vite 缓存该模块，随后 `router.push`
拿到的就是已完成的 Promise。

`warmed` 必须在失败时删除。永久记住失败会让该路由此后永不预取。

### D8 骨架 `--i` 按视觉行编号，不按 DOM 顺序

按 DOM 顺序时详情页 15 个块串成 390 ms 的长队，数据 450 ms 回来时骨架还没
铺完，动效白做。按行分组后 9 行 × 26 ms = 234 ms，落在等待期内。

同一行一起出现才像「内容逐行到位」，而不是「元素逐个冒泡」。

```js
const rows = []
for (const el of items) {
  const top = el.getBoundingClientRect().top
  const row = rows.find((r) => Math.abs(r.top - top) <= 6)   // 6px 内算同一行
  if (row) row.items.push(el)
  else rows.push({ top, items: [el] })
}
```

6px 容差是因为同一行的块高度不同（图标 34px、标题 24px），顶边不完全对齐。

### D9 骨架分组必须先可见再量位置

`display: none` 下所有 `getBoundingClientRect().top` 都是 0，分组会把全部块
压成一行，错峰退化成整屏一起出现。

顺序固定：`add('active')` → `numberSkeleton()` → `replaySkeletonFx()`。

### D10 写完 `--i` 必须重放动画

`active` 类加上时 CSS 动画已按当时的 `--i`（默认 0）启动。之后改变量不会
回溯——CSS 动画的延迟在启动那一刻就定了。

```js
el.style.animation = 'none'
void el.offsetWidth        // 强制重排，让浏览器重新计算动画
el.style.animation = ''
```

不重放的表现：第二次进入同一骨架页，所有块同时出现（全按 `--i: 0`）。
首次进入正常，所以这个 bug 只在反复导航时暴露。

### D11 进场与呼吸写在同一 `animation` 列表

两者都改 `.sk` 的 `animation` 属性。分开写两条 CSS 规则会互相覆盖，
后者赢。

```css
animation-name: sk-in, sk-breathe;
animation-duration: .36s, 2.6s;
animation-iteration-count: 1, infinite;
animation-fill-mode: both, none;
```

它们作用于不同属性（opacity/transform vs background-color），可以并列。
`sk-breathe` 的 `fill-mode: none` 是必需的——否则呼吸结束后会卡在
`from` 帧的底色上。

### D12 颜色只走 CSS 变量

`check-theme-vars.mjs` 禁止 `:root` 之外出现字面色值（`#hex` 与 `rgba()`）。
进度条与骨架动效全部用 `var(--accent*)` / `var(--surface-2)` /
`color-mix(in srgb, var(--x) 86%, var(--text))`。

`color-mix` 的百分比要写在 `var()` 之后，即
`color-mix(in srgb, var(--surface-2) 86%, var(--text))`；
不要把整个 `color-mix(...)` 表达式再套进另一层变量做二次混合，
部分浏览器会解析失败。

### D13 本方案不产生任何文案

进度条、骨架、动效都没有文字。**不需要新增 i18n 键**，
也不需要 `t()` 包裹。将来若给进度条加「加载中」标签，再走 i18n 流程。

---

## 5. 变体对比

预览页 `demo/route-loading-preview.html` 可逐项切换、调延迟、慢放对照。

| 方案 | 点击后首帧反馈 | 等待期显示 | 新页出现方式 | 总感知时长（300ms 延迟） |
|---|---|---|---|---|
| A 仅进度条 | 80 ms | 旧页静止 | 硬切 | 300 ms，仍像卡住 |
| B 骨架直切 | chunk 到达 | 目标页骨架 | 骨架换内容，无动画 | 300 ms，有进度感 |
| C 交叉淡入 | chunk 到达 | 旧页先淡出 90 ms，再骨架淡入 | 骨架换内容 | 570 ms |
| **D 组合（推荐）** | 立即（预取命中） | 目标页骨架，逐行错峰入场 | 骨架 180 ms 上浮淡入 | 300 ms，且有方向感 |

D = L0 + L1 + L2 + L3 + L4。相对 A 只多 6px 位移、一层 2px 进度条与骨架内错峰。

骨架动效三档可单开（预览页第二排按钮）：

| 档 | 效果 | 适用 |
|---|---|---|
| **全开** | 逐行淡入 + 流光波浪 + 底色呼吸 | 推荐。三层作用于不同属性，不打架 |
| 逐块淡入 | 只做 `sk-in` 错峰 | 嫌呼吸太吵时 |
| 流光波浪 | 只让 `::after` 错峰 | 改动最小，只调一个 delay |
| 呼吸 | 只做底色明暗 | 静态页面（无流光的浅色主题）更协调 |
| 无 | 原状：全亮静态块 + 同步流光 | 对照组 |

注意「新页出现方式」指的是**骨架页**的进场动效：`<transition>` 包的是
`router-view`，动效发生在组件挂载那一刻，此时组件里还是骨架。骨架 → 内容的
替换由数据驱动，**不再加动画**——否则一次跳转会连播两段动效。

**不推荐 C 单独使用**：`mode="out-in"` 的淡出在内容已经就绪时也要等 90 ms，
用户点十次就有十次 90 ms 的纯等待。

---

## 6. 边界与风险

| 风险 | 说明 | 处理 |
|---|---|---|
| `position: fixed` 被祖先 transform 破坏 | `.play-wrap` 是 `inset: 0` 全屏；过渡期间祖先带 `transform` 会让它相对祖先定位 | `play` 路由跳过过渡（D4） |
| `.list-fixed` 是 sticky | 同上，过渡那 180 ms 内 sticky 失效一帧 | 可接受；若实测有跳动，把列表页也加进跳过名单 |
| 筛选只改 query | 用 `fullPath` 做 key 会整页重挂、丢滚动位置、触发页面过渡闪烁 | key 用 `route.path`（D5） |
| 进度条闪一下就消失 | 局域网跳转常在 100 ms 内 | 80 ms 延迟 + 未露面时 `done()` 直接返回（D2） |
| 主题门禁 | `check-theme-vars.mjs` 禁止 `:root` 之外的字面色值 | 全部用 `var(--accent*)` / `var(--accent-ring)`（D12） |
| 文案国际化 | 本方案不产生任何文案（进度条与骨架都无文字） | 无需新增 i18n 键（D13） |
| 首屏 | `index.html` 的内联骨架已覆盖挂载前 | 首屏不走路由过渡，无叠加 |
| 预取放大请求 | 悬停即下载 chunk | 只有 11 条路由；每个 loader 只调一次（`warmed` 去重，D7） |
| 骨架错峰拉长等待 | 按 DOM 顺序编号时 15 块串成 390 ms，数据 450 ms 回来还没铺完 | 按视觉行分组，详情页降到 9 行 × 26 ms = 234 ms（D8） |
| `display:none` 下量位置 | 所有 `top` 都是 0，分组全压成一行 | 先 `active` 再量（D9） |
| 二次进入动画不重播 | `--i` 是 `active` 之后才写的，本轮动画已按旧延迟启动 | 写完 `--i` 重置 `animation` + 读 `offsetWidth` 强制重排（D10） |
| 呼吸与进场属性冲突 | 两者都改 `.sk` 的 `animation` | 写在同一 `animation-name` 列表里，分别控不同属性（D11） |

---

## 7. 改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `portal/frontend/src/styles.css` | `.route-progress` / `.page-*` / `:active` 三组规则 + reduced-motion |
| 2 | `portal/frontend/src/route-progress.js` | 新建，模块级进度状态 |
| 3 | `portal/frontend/src/components/RouteProgress.vue` | 新建，2px 进度条 |
| 4 | `portal/frontend/src/App.vue` | 套 `<transition>`、挂 `<RouteProgress>`、注册 pointerover 委托 |
| 5 | `portal/frontend/src/router.js` | 三个全局钩子；创建实例后 `bindRouter(router)` |
| 6 | `portal/frontend/src/route-scroll.js` | 新建，`scrollBehavior` + `takeSavedScroll()`（弹出导航位置供列表页数据到达后二次对齐） |
| 7 | `portal/frontend/src/prefetch.js` | 新建，`prefetch()` + `bindRouter()`；避免 router ↔ 视图循环依赖 |
| 8 | `portal/frontend/src/components/GameCard.vue` | **不改**。它是多根节点组件（`.cover` + `.card-body`），Vue 对多根组件不做属性透传，把 `@pointerenter` 挂在组件上会静默失效并告警；改为挂在调用方的 `<article class="card">` 上（第 9 条） |
| 9 | `portal/frontend/src/views/LibraryView.vue`、`portal/frontend/src/views/GameListView.vue` | Hero `<section class="hero">` 与卡片外层 `<article class="card">`（包着 `<GameCard>` 的宿主元素）加 `@pointerenter`，而不是挂在 `<GameCard>` 上（原因见第 8 条） |
| 10 | `portal/frontend/src/skeleton-fx.js` | 新建，`numberSkeleton()` 按视觉行写 `--i` + `replaySkeletonFx()` |
| 11 | 各骨架页（`LibraryView` / `GameListView` / `GameView`） | 骨架渲染后调用一次 `numberSkeleton` |

不改后端，不改 `index.html`（首屏骨架已独立）。

---

## 8. 验收

1. 慢网（DevTools Fast 3G）从首页点卡片：**预取命中时**点击后一帧内出现目标页
   骨架；未命中时旧页停留、80 ms 后进度条出现、chunk 到达后再出骨架。
   内容到达时新页上浮淡入。
2. 悬停卡片 200 ms 再点击：网络面板中目标 chunk 在点击**之前**已完成，
   点击后无进度条出现（`done()` 在露面之前返回）。
3. 列表页滚动到第 3 屏 → 点卡片 → 详情页在顶部；返回列表页回到第 3 屏。
4. 列表页改筛选（只改 query）：页面不重挂、不出现过渡动画、滚动位置保持。
5. 进入 `/play/:id`：无过渡动画，画布铺满视口，无内缩。
6. 切换 4 套内置主题，进度条颜色随 `--accent` 变化；`npm run check:theme` 通过。
7. `prefers-reduced-motion: reduce` 下无位移动画与进度条过渡，功能正常。
8. 维护遮罩（`.maintenance-mask`）与 Element Plus 对话框不被进度条压住。
9. 骨架 → 内容替换时**不**播放第二段动画（一次跳转只有一段动效）。
10. 骨架入场：同一视觉行的块同时出现（不是逐块冒泡）；详情页 9 行铺完
    不超过 240 ms；列表页筛选 chip 与卡片网格分别成组。
11. 骨架在 `display:none` 期间不量位置——从 A 方案切回 D、或二次进入同一页时，
    错峰仍然按行生效，不出现全部块压成一行。
12. `npm test` 与 `mvn test` 通过。
