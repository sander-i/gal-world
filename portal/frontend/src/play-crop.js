/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 把「串流画布」里游戏主窗口那一块铺满播放器。
 *
 * 画布（Xvfb 屏幕）通常比游戏窗口大，多出来的是黑边；按画布等比缩放
 * 会让窗口小的游戏看起来更小（画布越大越明显）。这里改为按窗口尺寸
 * 缩放、再把 iframe 位移到窗口位置，外层 overflow:hidden 裁掉黑边。
 *
 * @param {{width:number,height:number}} box  stage 可用区域
 * @param {{width:number,height:number}} canvas 串流画布
 * @param {{x:number,y:number,width:number,height:number}|null} win 窗口矩形
 */
export function cropPlan(box, canvas, win) {
  const cw = Number(canvas?.width) || 1280
  const ch = Number(canvas?.height) || 720
  let known = !!win && win.width > 0 && win.height > 0

  // 窗口可能越出画布：Wine 钳不住时窗口会比 Xvfb 屏幕大（sugarstyle
  // 实测 1286x752 对 1280x720）。直接按整窗缩放会算出比容器小的 frame，
  // 画面铺不满、右下留黑缝。这里先裁到画布内可见的那一块，比例也随之变化，
  // 越界部分本来就没被采样，裁掉不丢信息。
  let wx = known ? win.x : 0
  let wy = known ? win.y : 0
  let ww = known ? win.width : 0
  let wh = known ? win.height : 0
  if (known) {
    const x0 = Math.max(0, wx)
    const y0 = Math.max(0, wy)
    const x1 = Math.min(cw, wx + ww)
    const y1 = Math.min(ch, wy + wh)
    wx = x0
    wy = y0
    ww = x1 - x0
    wh = y1 - y0
    // 完全不与画布相交：没有可裁的画面，退回旧行为
    known = ww > 0 && wh > 0
  }

  const ratio = known ? ww / wh : cw / ch
  let viewW = box.width
  let viewH = viewW / ratio
  if (viewH > box.height) {
    viewH = box.height
    viewW = viewH * ratio
  }
  const vw = Math.floor(viewW)
  const vh = Math.floor(viewH)

  if (!known) {
    // 没有窗口信息：按画布比例铺满，保持旧行为
    return { viewW: vw, viewH: vh, frameW: vw, frameH: vh, left: 0, top: 0, scale: 1 }
  }

  const scale = vw / ww
  return {
    viewW: vw,
    viewH: vh,
    frameW: Math.ceil(cw * scale),
    frameH: Math.ceil(ch * scale),
    // 加 0 把 -0 归一成 0（Math.round(-0) === -0）
    left: Math.round(-wx * scale) + 0,
    top: Math.round(-wy * scale) + 0,
    scale,
  }
}

/**
 * 窗口尺寸采样时刻（毫秒，相对进入播放）。
 *
 * run-game.sh 的放大是后台循环：每 2 秒扫一次窗口，面积不足画布 85% 就
 * `xdotool windowsize` 放大，首次被忽略时下一轮重试，所以尺寸变化可能落在
 * 启动后 4–10 秒。这四个采样点覆盖该窗口期，之后连续两次相同即停止。
 */
export const WINDOW_SAMPLE_DELAYS = [1500, 4000, 8000, 12000]

/**
 * 早于该时刻不做「已稳定」判定（毫秒）。
 *
 * 放大前窗口本身是稳定的，若两次采样一相同就停，1.5s 就会停，拿到的是
 * 放大前的旧矩形。至少要跨过 8s 采样点才允许提前结束。
 */
export const WINDOW_STABLE_AFTER_MS = 8000

/**
 * 两次采样是否指向同一个窗口矩形（宽高或位置变化都算变化）。
 * 两者都为空（窗口尚未找到）视为未变化，避免重复重算裁剪。
 */
export function sameWindowRect(a, b) {
  if (!a || !b) return !a && !b
  return a.x === b.x && a.y === b.y && a.width === b.width && a.height === b.height
}

/**
 * 容器顶部「悬停不产生输入」的带宽（容器内的 CSS 像素）。
 *
 * 裁剪之后，容器最上面那一行就是游戏窗口的上沿（Wine `Decorated=N` 时画布
 * y=12）。指针停在那里会被 Kirikiri 当成「鼠标移到窗口顶部」，弹出窗口菜单，
 * 画面跟着重绘一次——用户看到的就是「鼠标到顶部画面抖」。只留一条空白
 * （`INNER_TOP_GAP`）不够：空隙在容器之外、不接收事件，指针再往下 1px 就进了
 * 容器顶行。
 *
 * 处理放在输入侧，只丢**悬停移动**：按下/抬起照常转发，拖拽划进顶部时鼠标键
 * 不会卡住；画面本身一个像素都不裁。
 */
export const TOP_GUARD_PX = 10

/**
 * 屏蔽带对应的画布像素数。
 *
 * 上面那个 10px 是「至少这么多 CSS 像素」。窗口比画布小的时候画面会被放大
 * （`scale` = 容器宽 / 窗口宽），10 CSS 像素换算回画布就只剩几个像素，盖不住
 * 菜单触发区。这里按画布像素给一个下限，两种单位取大的那个。
 */
export const TOP_GUARD_CANVAS_PX = 14

/**
 * 生成注入 iframe 的样式：容器按 viewW×viewH 居中，内部画布按 frameW×frameH
 * 放大并位移，窗口外的黑边被容器裁掉。
 *
 * 只改 iframe 内部元素，不动 iframe 自身尺寸与 transform：Selkies 的指针换算
 * 按元素矩形（canvas 的 getBoundingClientRect）与 buffer 尺寸的比例计算，容器
 * 与画布同步缩放位移后映射依旧成立。
 *
 * `#overlayInput` 必须与画布用同一组尺寸位移。Selkies 按容器宽度给它单独算居中
 * 留白（内联 `left`），只改 video/canvas 时输入层会缩在中间，两侧 47px 的可见画面
 * 上没有输入层，点上去落到 `videoStream` 上，事件根本不发。
 *
 * @param {{width:number,height:number}} box   stage 可用区域（已扣掉顶栏）
 * @param {{width:number,height:number}} canvas 串流画布
 * @param {{x:number,y:number,width:number,height:number}|null} win 窗口矩形
 * @param {number} topBar 顶栏高度，容器垂直居中时要让出来
 * @returns {string} CSS 文本
 */
export function streamFitStyle(box, canvas, win, topBar) {
  const plan = cropPlan(box, canvas, win)
  const left = Math.round((box.width - plan.viewW) / 2)
  const top = topBar + Math.round((box.height - plan.viewH) / 2)
  return `
.video-container {
  position: absolute !important;
  left: ${left}px !important;
  top: ${top}px !important;
  width: ${plan.viewW}px !important;
  height: ${plan.viewH}px !important;
  overflow: hidden !important;
}
.video-container video, .video-container canvas, .video-container #overlayInput {
  left: ${plan.left}px !important;
  top: ${plan.top}px !important;
  width: ${plan.frameW}px !important;
  height: ${plan.frameH}px !important;
}`
}

/**
 * 容器顶部悬停屏蔽带的矩形（iframe 内部坐标）。
 *
 * 与 `streamFitStyle` 用同一套算法，返回 `{ top, bottom }`：`top` 是容器上沿，
 * `bottom` 是屏蔽带下沿。`bottom` 已经夹在容器内，容器比带子还矮时整块都算。
 *
 * @returns {{top:number,bottom:number}}
 */
export function topGuardRect(box, canvas, win, topBar) {
  const plan = cropPlan(box, canvas, win)
  const top = topBar + Math.round((box.height - plan.viewH) / 2)
  // 画布像素与 CSS 像素取大的那个：放大倍数小于 1 时（窗口比画布大）10 CSS
  // 像素已经覆盖不止 14 个画布像素，按 CSS 算；放大时按画布下限算。
  const band = Math.round(Math.max(TOP_GUARD_PX, TOP_GUARD_CANVAS_PX * plan.scale))
  return {
    top,
    bottom: top + Math.min(band, plan.viewH),
  }
}
