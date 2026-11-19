/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 路由预取。
 *
 * 单独成文件而不是留在 router.js：视图组件（App / LibraryView / GameListView）
 * 需要调用 prefetch，若从 router.js 导入就会形成 router → 视图 → router 的循环依赖。
 * 这里只保存 router 实例的引用，不反过来导入 router.js。
 */
let routerRef = null

/** router.js 创建实例后注入，避免 prefetch 与路由模块互相导入 */
export function bindRouter(router) {
  routerRef = router
}

/**
 * 已预热过的 loader。按 loader 函数本身去重（同一路由的默认组件唯一），
 * 避免同一次悬停反复触发 import。
 */
const warmed = new Set()

/**
 * 悬停/聚焦时预热目标路由的异步组件。
 *
 * router.resolve() 不会解析 async component，matched[].components.default 仍是
 * loader 函数；主动调用一次即触发 import，Vite 会缓存该模块，随后 router.push
 * 拿到的就是已完成的 Promise —— 多数跳转因此 0 等待。
 *
 * 只做「用户已经指向的目标」，不做 idle 全量预取：那等于放弃路由级 code split。
 */
export function prefetch(href) {
  if (!routerRef || !href) return
  let r
  try {
    r = routerRef.resolve(href)
  } catch {
    return // 非法 href（外部链接、空值）直接放过
  }
  for (const m of r.matched) {
    const c = m.components?.default
    if (typeof c !== 'function' || warmed.has(c)) continue
    warmed.add(c)
    // 失败必须移除：永久记住失败会让该路由此后再也不预取
    c().catch(() => warmed.delete(c))
  }
}
