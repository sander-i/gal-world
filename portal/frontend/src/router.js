/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { createRouter, createWebHistory } from 'vue-router'
import LibraryView from './views/LibraryView.vue'

// 除首页外全部按需加载：AdminView / PlayView / GameEditView 都是上千行，
// 静态导入会把它们塞进首屏 bundle。路由路径增删要同步
// SpaFallbackController 的回退清单，否则直接访问会 404。

export default createRouter({
  history: createWebHistory(),
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
