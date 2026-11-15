# 游玩窗口全屏优化方案

日期：2026-11-04
状态：待评审
影响面：`portal/frontend/src/views/PlayView.vue`、`portal/frontend/src/styles.css`（仅前端，无后端/接口/数据变更）

## 1. 问题

游玩页 `/play/:id` 目前只有浏览器自带的 F11 全屏。按 F11 后页面整体放大，但门户自己的顶栏仍在：

- `← 游戏库`
- 游戏名 / 状态 / 累计游玩
- `会话管理`

用户看到的不是「只有游戏画面」的真全屏。

## 2. 根因

两件事被混为一谈：

| 方式 | 行为 | 能否隐藏顶栏 |
|---|---|---|
| F11（浏览器级全屏） | 整个页面放大到屏幕，页面内容不变 | 否。顶栏是页面的一部分 |
| Fullscreen API（`element.requestFullscreen()`） | 只渲染指定元素及其子树 | 是。全屏元素之外的内容不参与渲染 |

当前 `PlayView.vue` 没有任何 Fullscreen API 调用，所以只有 F11 路径可用。iframe 已经带 `allowfullscreen` 与 `allow="... fullscreen ..."`，因此 **Selkies 自己界面里的全屏按钮现在就能把画面铺满**；缺的是门户侧的全屏入口与退出体验。

另一个硬限制：页面无法可靠感知或拦截 F11。F11 不触发 `fullscreenchange`，`keydown` 也可能被浏览器直接吞掉。因此「按 F11 也只显示画面」做不到；正确做法是提供一个门户自己的全屏按钮/快捷键。

## 3. 目标与非目标

**目标**

1. 一键进入「只有游戏画面」的全屏，门户顶栏不显示。
2. 一键退出（按钮 + Esc），退出后布局与状态完全恢复。
3. 桌面浏览器优先使用 Fullscreen API；不支持的环境退回「沉浸模式」。
4. 移动端进入全屏后尝试锁定横屏。
5. 不破坏现有启动进度、崩溃覆盖层、心跳、光标修复与主题样式。

**非目标**

- 不拦截/改造 F11。
- 不在进入游玩页时自动全屏（Fullscreen API 要求用户手势，导航后手势失效）。
- 不修改 Selkies 内部 UI，也不依赖它一定有全屏按钮。
- 不改变串流链路、后端接口或部署方式。

## 4. 方案对比

### 方案 A：全屏 `.play-wrap`，CSS 隐藏顶栏

```css
.play-wrap:fullscreen .play-bar { display: none; }
```

- 优点：全屏元素就是整个游玩页，覆盖层、错误卡片天然在内。
- 缺点：需要额外的 `:fullscreen` 选择器；顶栏只是隐藏，仍占 DOM；退出按钮要么放全屏元素内，要么依赖 Esc。

### 方案 B（推荐）：全屏 `.play-stage`

`.play-stage` 只包含 iframe 与覆盖层，顶栏是它的兄弟节点。对它调用 `requestFullscreen()` 后，浏览器只渲染 stage 子树，顶栏**自动不在画面内**，不需要任何隐藏顶栏的 CSS。

- 优点：语义最直接；顶栏、路由按钮无从渲染；覆盖层与错误卡片仍在 stage 内，全屏时照常可见。
- 缺点：退出全屏需要一个位于 stage 内的浮动按钮（移动端无 Esc）。

### 方案 C：只依赖 Selkies 内全屏按钮

- 优点：零代码。
- 缺点：入口在 iframe 内，用户不一定找得到；各版本 Selkies UI 不保证一致；门户无法提供统一快捷键。只作为现状说明，不作为方案。

**结论：方案 B。**

## 5. 交互设计

### 5.1 入口

顶栏右侧新增按钮：

```text
⛶ 全屏        （已全屏时变为「⤢ 退出全屏」）
```

位置在「会话管理」之前，沿用 `ghost-btn` 样式。

快捷键：`Alt+Enter` 切换全屏。

- 不绑 F11：浏览器保留该键，页面拦截可能双触发。
- 快捷键只在门户文档有焦点时生效；焦点在 iframe 内时由 iframe 文档接收，属可接受限制（按钮始终可用）。

### 5.2 退出

- `Esc`：浏览器原生退出。
- stage 内右上角浮动按钮「退出全屏」：仅在 `isFullscreen` 或沉浸模式时显示，半透明、悬停加深，避免挡住游戏 HUD。
- 顶栏按钮在重新出现后同样可退出（若通过沉浸模式进入）。

### 5.3 移动端

- 进入全屏后尝试 `screen.orientation.lock('landscape')`，失败静默忽略。
- 退出时 `screen.orientation.unlock()`。
- iOS Safari 对非 video 元素不支持 Fullscreen API，自动使用沉浸模式。

### 5.4 沉浸模式（回退）

不支持 Fullscreen API 时：

- 给 `.play-wrap` 加 `immersive` 类。
- CSS 隐藏 `.play-bar`，stage 保持铺满视口。
- 显示同一个「退出全屏」浮动按钮。
- 浏览器地址栏可能仍可见，但游戏区域已最大化；这是平台限制，不是实现缺陷。

## 6. 实现设计

### 6.1 PlayView.vue

新增状态与引用：

```js
const stage = ref(null)
const isFullscreen = ref(false)
const isImmersive = ref(false)

const fullscreenActive = computed(() => isFullscreen.value || isImmersive.value)
```

核心函数（示意）：

```js
async function toggleFullscreen() {
  if (fullscreenActive.value) return exitFullscreen()
  return enterFullscreen()
}

async function enterFullscreen() {
  const el = stage.value
  if (!el) return
  try {
    if (el.requestFullscreen) {
      await el.requestFullscreen({ navigationUI: 'hide' })
    } else if (el.webkitRequestFullscreen) {
      el.webkitRequestFullscreen()
    } else {
      enterImmersive()
    }
  } catch {
    // 用户拒绝或浏览器策略禁止：退回沉浸模式，保证仍有入口
    enterImmersive()
  }
  lockLandscape()
}

async function exitFullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else if (document.webkitFullscreenElement) document.webkitExitFullscreen()
  } catch {
    // 忽略：可能已被 Esc 退出
  }
  exitImmersive()
  unlockOrientation()
}

function enterImmersive() {
  isImmersive.value = true
}

function exitImmersive() {
  isImmersive.value = false
}

function onFullscreenChange() {
  isFullscreen.value = document.fullscreenElement === stage.value
  if (isFullscreen.value) isImmersive.value = false
}

function lockLandscape() {
  try {
    screen.orientation?.lock?.('landscape').catch(() => {})
  } catch {
    // 不支持则忽略
  }
}

function unlockOrientation() {
  try {
    screen.orientation?.unlock?.()
  } catch {
    // 忽略
  }
}

function onKeydown(e) {
  if (e.altKey && e.key === 'Enter') {
    e.preventDefault()
    toggleFullscreen()
  }
}
```

生命周期：

```js
onMounted(() => {
  document.addEventListener('fullscreenchange', onFullscreenChange)
  document.addEventListener('webkitfullscreenchange', onFullscreenChange)
  window.addEventListener('keydown', onKeydown)
  // ...现有逻辑
})

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  document.removeEventListener('webkitfullscreenchange', onFullscreenChange)
  window.removeEventListener('keydown', onKeydown)
  if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {})
  unlockOrientation()
  // ...现有清理
})
```

模板：

```html
<div class="play-bar" ...>
  ...
  <button class="ghost-btn" @click="toggleFullscreen">
    {{ fullscreenActive ? '⤢ 退出全屏' : '⛶ 全屏' }}
  </button>
  <button class="ghost-btn" @click="router.push(`/game/${id}`)">会话管理</button>
</div>

<div ref="stage" class="play-stage">
  <iframe v-if="showFrame" ... />

  <button
    v-if="fullscreenActive"
    class="play-fs-exit"
    title="退出全屏（Esc）"
    @click="exitFullscreen"
  >⤢ 退出全屏</button>

  <!-- 现有进度覆盖层 / 状态卡片 -->
</div>
```

`.play-wrap` 绑定：

```html
<div class="play-wrap" :class="{ immersive: isImmersive }" @mousemove="showBar = true">
```

注意事项：

- 全屏元素是 `stage`，因此进度覆盖层与崩溃卡片仍会显示，不会因全屏丢状态。
- 浮动退出按钮必须放在 `stage` 内，否则全屏时不可见。
- 进入/退出全屏不触发 iframe 重载，`fixCursor` 无需额外处理。
- 全屏请求失败时进入沉浸模式，不让按钮「点了没反应」。

### 6.2 styles.css

新增（全部用变量，禁止 `:root` 之外出现字面色值）：

先在 `:root` 的 media 令牌区补：

```css
--media-overlay-btn: color-mix(in srgb, #000 55%, transparent);
--media-overlay-btn-hover: color-mix(in srgb, #000 75%, transparent);
--media-overlay-line: color-mix(in srgb, #fff 22%, transparent);
```

再新增：

```css
.play-fs-exit {
  position: absolute;
  top: 12px;
  right: 12px;
  z-index: 5;
  height: var(--ctl-h-sm);
  padding: 0 12px;
  border: 1px solid var(--media-overlay-line);
  border-radius: 999px;
  background: var(--media-overlay-btn);
  color: var(--media-ink-strong);
  font-size: var(--ctl-fs-sm);
  opacity: .45;
  transition: opacity .15s, background .15s;
}

.play-fs-exit:hover,
.play-fs-exit:focus-visible {
  opacity: 1;
  background: var(--media-overlay-btn-hover);
}

.play-wrap.immersive .play-bar {
  display: none;
}

.play-wrap.immersive .play-stage {
  flex: 1;
  min-height: 0;
}
```

说明：浮动按钮压在游戏画面上，使用固定 `--media-*` 令牌而不是主题色，浅色主题下也不会变成深色墨压画面。

### 6.3 不改的部分

- 后端、`SessionController`、诊断接口、串流代理不动。
- `fixCursor`、心跳、轮询、崩溃卡片、启动进度逻辑不动。
- 顶栏在普通模式下保持现状（44px、鼠标移出变淡）。

## 7. 边界与风险

| 场景 | 预期行为 | 说明 |
|---|---|---|
| Chrome/Edge 桌面 | 按钮或 Alt+Enter 进入；Esc 退出 | 主路径 |
| F11 | 仍是浏览器全屏，顶栏可见 | 页面无法感知，明确不支持 |
| 焦点在 iframe 内时按 Alt+Enter | 门户收不到按键 | 按钮始终可用 |
| 跨域 `streamUrl`（Moonlight） | stage 全屏正常；不访问 iframe DOM | 现有 `waitForFirstFrame` 已处理跨域 |
| Selkies 自己在 iframe 内请求全屏 | 浏览器再叠一层全屏 | Esc 逐层退出，属浏览器行为 |
| iOS Safari | 沉浸模式（隐藏顶栏，地址栏可能仍在） | 平台不支持元素全屏 |
| 用户拒绝全屏权限 | 进入沉浸模式 | 不让操作无反馈 |
| 全屏时启动 / 崩溃 | 进度环、错误卡片正常显示 | 覆盖层在 stage 内 |
| 退出后 | 顶栏恢复，iframe 不重载，计时不停 | 不触碰现有生命周期 |

**风险等级：低。** 纯前端、可回滚；不涉及数据与接口。最坏情况是浏览器不支持时退化为沉浸模式。

## 8. 测试与验收

### 8.1 自动检查

```bash
cd portal/frontend
npm test
npm run check:theme
npm run build
```

### 8.2 手工矩阵

1. Chrome/Edge：进游戏 → 点「⛶ 全屏」→ 顶栏消失、画面铺满 → Esc 退出 → 顶栏恢复。
2. 再次进入 → `Alt+Enter` 退出。
3. 全屏状态下调用「启动/崩溃」覆盖层（可用 devdata 伪造），确认进度环与错误卡片可见、按钮可点。
4. 移动端 Chrome：进入全屏 → 尝试横屏锁定 → 浮动按钮退出。
5. iOS Safari：确认进入沉浸模式、顶栏隐藏、退出按钮可用。
6. 四套主题：全屏按钮与浮动退出按钮在浅色/深色下都可读。
7. 普通模式 F11：行为与现在一致（不回归）。

### 8.3 验收标准

- 全屏后画面上不存在门户顶栏与任何门户按钮（浮动退出按钮除外）。
- 退出全屏后布局、状态、计时、轮询完全恢复。
- 不支持 Fullscreen API 时仍可进入沉浸模式，且能退出。
- `npm test`、`check:theme`、`build` 全部通过。

## 9. 工作量

- 代码：`PlayView.vue` 约 70–90 行，`styles.css` 约 30 行，新增 3 个 media 令牌。
- 测试：现有前端单测覆盖不到 Fullscreen API，主要靠手工矩阵；可加一条纯函数 `isFullscreenSupported` 的单测（可选）。
- 预计 0.5–1 人日（含四主题与移动端核对）。

## 10. 后续可选增强

- 记住上次全屏偏好（localStorage），下次进入游玩页显示提示而非自动全屏。
- 全屏时隐藏鼠标 2 秒后自动淡出（需与 Selkies 光标逻辑联动，风险较高）。
- 在设置页提供「进入游戏自动提示全屏」开关。
