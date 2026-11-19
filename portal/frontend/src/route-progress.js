/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 全局路由进度条状态。
 *
 * 状态放在模块级而不是 router 里，是为了避开 router ↔ 组件的循环依赖：
 * router.js 需要 begin/done，App.vue 的进度条组件需要读值，两边都只依赖本文件。
 *
 * 只覆盖路由导航本身，也就是 `() => import()` 的 chunk 下载那一段；
 * 组件挂载后各页自己的骨架期不归它管（否则进度条会拖到数据回来才收尾，
 * 看起来像导航卡住）。
 *
 * 这里刻意不 import vue：Vue 的 esm-bundler 构建在 node --test 下不可靠。
 * 导出普通对象作为初始值，由 RouteProgress.vue 用 reactive() 包一层后通过
 * bindProgress() 注入；模块内所有写入都走注入的代理，响应性才成立。
 */
export const routeProgress = { active: false, value: 0 }

/**
 * 模块内实际读写的目标。
 *
 * 组件把 reactive(routeProgress) 注入进来后，begin/done 的写入会经过 proxy 的
 * set trap，模板才会更新。若一直写原始对象会绕过 trap —— 浏览器里进度条永远不出现，
 * 而 node 测试发现不了。
 */
let state = routeProgress

/** 组件用 reactive(routeProgress) 包一层后注入，模块内所有写入都走 proxy，响应性才成立 */
export function bindProgress(target) {
  state = target
}

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
    state.active = true
    step()
  }, 80)
  clearInterval(trickleTimer)
  trickleTimer = setInterval(step, 200)
  // 已在显示时保留当前宽度，避免可见回跳：上一轮可能停在 100%，
  // 重置成 8% 会让用户看到进度条倒退。收尾仍由 done() 负责。
  // 代价：若上一轮正在淡出（宽度 100）而新导航较慢，进度条会停在满格直到
  // done()。相比回跳，宁可短时间显示满格。
  if (!state.active) state.value = 8
}

/** 渐近推进：越接近 90% 越慢，永远不到 100% —— 收尾交给 done() */
function step() {
  const v = state.value
  if (v >= 90) return
  state.value = v + (90 - v) * 0.18
}

export function done() {
  clearTimeout(showTimer)
  clearInterval(trickleTimer)
  // afterEach 与 onError 可能对同一次失败导航各调一次 done()；
  // 先清旧的隐藏定时器，保证只有一个收尾计时器在跑
  clearTimeout(hideTimer)
  if (!shown) return // 没露过面就别露了
  state.value = 100
  // 留 220 ms 让 100% 那一下可见，再淡出；期间若新导航开始会被 begin() 取消
  hideTimer = setTimeout(() => {
    state.active = false
    state.value = 0
    // 归零放在这里而不是 begin()：begin 时若上一轮还在淡出，active 仍为 true，
    // 重置 shown 会让新一轮 80 ms 内完成时提前返回，进度条卡在 8%。
    shown = false
  }, 220)
}

/** 仅供测试：清掉三个定时器并恢复初始状态，避免用例之间互相污染 */
export function __resetForTests() {
  clearTimeout(showTimer)
  clearInterval(trickleTimer)
  clearTimeout(hideTimer)
  showTimer = null
  trickleTimer = null
  hideTimer = null
  shown = false
  state.active = false
  state.value = 0
  // 解除上一用例注入的目标，默认回到导出对象
  state = routeProgress
}
