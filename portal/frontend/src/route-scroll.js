/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 路由滚动位置恢复。
 *
 * 单独成文件而不是内联进 router.js：router.js 会 import .vue，node --test 里
 * 加载不了，拆出纯函数才能在测试里直接跑。
 *
 * 三条分支各对应一种真实场景：
 * - savedPosition：浏览器前进/后退，恢复到离开时那一屏；
 * - to.path === from.path：列表页改筛选只动 query，组件不会重挂，
 *   返回 false 表示「别动滚动位置」。这里不能返回 { top: 0 }，
 *   否则每次勾选筛选都会被拉回顶部，用户刚翻到第 3 屏就丢了；
 * - 其余情况：跨页跳转一律回到顶部。从滚到第 6 屏的列表点进详情，
 *   详情页也停在第 6 屏比任何动画都伤体验。
 */
export function scrollBehavior(to, from, savedPosition) {
  if (savedPosition) return savedPosition
  if (to.path === from.path) return false
  return { top: 0 }
}
