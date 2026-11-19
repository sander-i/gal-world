/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { createRouter, createWebHistory } from 'vue-router'
import LibraryView from './views/LibraryView.vue'
import { begin, done } from './route-progress'
import { scrollBehavior } from './route-scroll'

// 除首页外全部按需加载：AdminView / PlayView / GameEditView 都是上千行，
// 静态导入会把它们塞进首屏 bundle。路由路径增删要同步
// SpaFallbackController 的回退清单，否则直接访问会 404。

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior,
  routes: [
    { path: '/', name: 'library', component: LibraryView },
    { path: '/games', name: 'games', component: () => import('./views/GameListView.vue') },
    { path: '/games/import', name: 'game-import', component: () => import('./views/GameImportView.vue') },
    { path: '/game/:id', name: 'game', component: () => import('./views/GameView.vue'), props: true },
    { path: '/game/:id/edit', name: 'game-edit', component: () => import('./views/GameEditView.vue'), props: true },
    { path: '/play/:id', name: 'play', component: () => import('./views/PlayView.vue'), props: true },
    { path: '/settings', name: 'settings', component: () => import('./views/SettingsView.vue') },
    { path: '/admin', name: 'admin', component: () => import('./views/AdminView.vue') },
    { path: '/companies', name: 'companies', component: () => import('./views/CompanyManageView.vue') },
    { path: '/localization-groups', name: 'localization-groups', component: () => import('./views/LocalizationGroupManageView.vue') },
    { path: '/tags', name: 'tags', component: () => import('./views/TagManageView.vue') },
  ],
})

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
  if (!href) return
  let r
  try {
    r = router.resolve(href)
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

// 进度条只包住导航本身（chunk 下载），不接任何页面自己的 loading 状态。
// 这里不写守卫逻辑，只做生命周期通知；onError 也要收尾，否则导航失败时
// 进度条会永远停在 90%。
router.beforeEach(() => { begin() })
router.afterEach(() => { done() })
router.onError(() => { done() })

export default router
