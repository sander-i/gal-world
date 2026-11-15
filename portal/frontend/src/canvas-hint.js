/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 推荐画布尺寸：游戏窗口 + 24px 余量，按 8 取整，并限制在 640×480 到 3840×2160。
 * 与后端 CanvasPlanner 的 fit-window 规则保持一致。
 */
export function recommendedCanvas(winW, winH) {
  if (!(Number(winW) > 0) || !(Number(winH) > 0)) return null
  const round8 = (v) => Math.ceil(v / 8) * 8
  const clamp = (v, min, max) => Math.min(max, Math.max(min, v))
  return {
    width: round8(clamp(Number(winW) + 24, 640, 3840)),
    height: round8(clamp(Number(winH) + 24, 480, 2160)),
  }
}
