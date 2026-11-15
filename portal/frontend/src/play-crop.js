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
  const known = !!win && win.width > 0 && win.height > 0

  const ratio = known ? win.width / win.height : cw / ch
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
    return { viewW: vw, viewH: vh, frameW: vw, frameH: vh, left: 0, top: 0 }
  }

  const scale = vw / win.width
  return {
    viewW: vw,
    viewH: vh,
    frameW: Math.ceil(cw * scale),
    frameH: Math.ceil(ch * scale),
    // 加 0 把 -0 归一成 0（Math.round(-0) === -0）
    left: Math.round(-win.x * scale) + 0,
    top: Math.round(-win.y * scale) + 0,
  }
}
