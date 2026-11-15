/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { createRouter, createWebHistory } from 'vue-router'
import LibraryView from './views/LibraryView.vue'
import GameListView from './views/GameListView.vue'
import GameView from './views/GameView.vue'
import PlayView from './views/PlayView.vue'
import SettingsView from './views/SettingsView.vue'
import AdminView from './views/AdminView.vue'
import GameEditView from './views/GameEditView.vue'
import CompanyManageView from './views/CompanyManageView.vue'
import LocalizationGroupManageView from './views/LocalizationGroupManageView.vue'
import TagManageView from './views/TagManageView.vue'
import GameImportView from './views/GameImportView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'library', component: LibraryView },
    { path: '/games', name: 'games', component: GameListView },
    { path: '/games/import', name: 'game-import', component: GameImportView },
    { path: '/game/:id', name: 'game', component: GameView, props: true },
    { path: '/game/:id/edit', name: 'game-edit', component: GameEditView, props: true },
    { path: '/play/:id', name: 'play', component: PlayView, props: true },
    { path: '/settings', name: 'settings', component: SettingsView },
    { path: '/admin', name: 'admin', component: AdminView },
    { path: '/companies', name: 'companies', component: CompanyManageView },
    { path: '/localization-groups', name: 'localization-groups', component: LocalizationGroupManageView },
    { path: '/tags', name: 'tags', component: TagManageView },
  ],
})
