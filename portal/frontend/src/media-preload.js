/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 图片预加载的全局并发闸门。
 *
 * 首页网格里每张卡片都有 MediaRotator 预加载自己的封面；
 * 20+ 款游戏时不计并发会把局域网带宽瞬间打满，首屏反而更慢。
 * 这里限制同时在途的预加载数量，其余的排队。
 */
export const PRELOAD_LIMIT = 6

let active = 0
const waiters = []

export function acquirePreloadSlot() {
  if (active < PRELOAD_LIMIT) {
    active++
    return Promise.resolve()
  }
  return new Promise((resolve) => waiters.push(resolve))
}

export function releasePreloadSlot() {
  const next = waiters.shift()
  if (next) {
    next()
    return
  }
  active = Math.max(0, active - 1)
}

/** 仅测试使用：清空计数与等待队列 */
export function resetPreloadSlots() {
  active = 0
  waiters.length = 0
}
