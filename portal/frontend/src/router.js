/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { createRouter, createWebHistory } from 'vue-router'
import LibraryView from './views/LibraryView.vue'
import { bindRouter } from './prefetch'
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

// 预取模块不能导入 router.js（否则 router → 视图 → router 循环），
// 实例创建后注入给它，悬停预取才有 router.resolve 可用
bindRouter(router)

// 进度条只包住导航本身（chunk 下载），不接任何页面自己的 loading 状态。
// 这里不写守卫逻辑，只做生命周期通知；onError 也要收尾，否则导航失败时
// 进度条会永远停在 90%。
router.beforeEach(() => { begin() })
router.afterEach(() => { done() })
router.onError(() => { done() })

export default router
