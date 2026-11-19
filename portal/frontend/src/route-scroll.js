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

/**
 * 本次导航要恢复到的滚动位置。
 *
 * vue-router 在 popstate 时把位置放进 savedPosition，但视图拿不到它，
 * 而列表页骨架期高度不足会把目标位置钳制到骨架底部，数据铺开后需要再对齐一次。
 * 这里在真正返回 savedPosition 时记下目标，供视图在数据渲染完后取用；
 * 只依赖 scrollBehavior 的参数，不读 history.state 这种 vue-router 内部字段。
 */
let pending = null

export function scrollBehavior(to, from, savedPosition) {
  if (savedPosition) {
    pending = { path: to.path, top: savedPosition.top ?? 0 }
    return savedPosition
  }
  // 普通导航（push/replace）不清掉旧记录的话，上一次 popstate 留下却没被
  // 消费的位置（例如同 path 只改 query、列表没有重挂）可能被之后某次
  // router.push('/games') 误用，把新页面滚到旧位置而不是顶部
  pending = null
  if (to.path === from.path) return false
  return { top: 0 }
}

/**
 * 取出并清除「本次导航要恢复到的滚动位置」；没有匹配记录返回 null。
 *
 * path 不匹配时保留记录、不消耗：调用方可能先问一次别的路径，
 * 真正的目标页稍后仍应取得到。
 */
export function takeSavedScroll(path) {
  if (!pending || pending.path !== path) return null
  const top = pending.top
  pending = null
  return top
}

/** 仅供测试：清掉 pending 记录，避免用例之间互相污染 */
export function __resetForTests() {
  pending = null
}
