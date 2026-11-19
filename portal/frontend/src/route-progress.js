/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { reactive } from 'vue'

/**
 * 全局路由进度条状态。
 *
 * 状态放在模块级而不是 router 里，是为了避开 router ↔ 组件的循环依赖：
 * router.js 需要 begin/done，App.vue 的进度条组件需要读值，两边都只依赖本文件。
 *
 * 只覆盖路由导航本身，也就是 `() => import()` 的 chunk 下载那一段；
 * 组件挂载后各页自己的骨架期不归它管（否则进度条会拖到数据回来才收尾，
 * 看起来像导航卡住）。
 */
export const routeProgress = reactive({ active: false, value: 0 })

let showTimer = null
let trickleTimer = null
let hideTimer = null
// 是否真的露过面。只清定时器不判断，会出现「已经结束了才冒出来」的闪条。
// 注意它不能在 begin() 里重置：若上一轮还在 220 ms 淡出窗口内（active 仍为 true），
// 重置后新一轮 80 ms 内完成会让 done() 提前返回，进度条永久停在 8%。
let shown = false

export function begin() {
  clearTimeout(hideTimer)
  // 80 ms 内完成就不出现：局域网跳转常在此之内，一闪而过比不出现更像故障
  showTimer = setTimeout(() => {
    shown = true
    routeProgress.active = true
    step()
  }, 80)
  clearInterval(trickleTimer)
  trickleTimer = setInterval(step, 200)
  routeProgress.value = 8
}

/** 渐近推进：越接近 90% 越慢，永远不到 100% —— 收尾交给 done() */
function step() {
  const v = routeProgress.value
  if (v >= 90) return
  routeProgress.value = v + (90 - v) * 0.18
}

export function done() {
  clearTimeout(showTimer)
  clearInterval(trickleTimer)
  if (!shown) return // 没露过面就别露了
  routeProgress.value = 100
  // 留 220 ms 让 100% 那一下可见，再淡出；期间若新导航开始会被 begin() 取消
  hideTimer = setTimeout(() => {
    routeProgress.active = false
    routeProgress.value = 0
    // 归零放在这里而不是 begin()：begin 时若上一轮还在淡出，active 仍为 true，
    // 重置 shown 会让新一轮 80 ms 内完成时提前返回，进度条卡在 8%。
    shown = false
  }, 220)
}
