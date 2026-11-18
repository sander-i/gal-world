/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
// 只引 Element Plus 实际用到的组件样式（全站在 feedback.js 里只用
// ElMessage / ElMessageBox）。全量 index.css 有 434 KB，其中 361 KB 用不上。
// MessageBox 自带按钮与关闭图标，所以 el-button / el-icon 也要带上。
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-message.css'
import 'element-plus/theme-chalk/el-message-box.css'
import 'element-plus/theme-chalk/el-button.css'
import 'element-plus/theme-chalk/el-icon.css'
import 'element-plus/theme-chalk/el-overlay.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles.css'
import { BUILTIN_THEMES } from './theme.js'
import { useTheme } from './theme-store.js'
import { loadI18n } from './i18n.js'

// Element Plus 的 dark 变量挂在 html.dark；applyTheme 会按主题 base 增删该类
const theme = useTheme()

/**
 * 支持 ?theme=<id> 临时覆盖主题，不写回 localStorage。
 *
 * 用途：截图/验收时不必手动点设置页；也方便把"某个主题下长什么样"的链接发给别人。
 * 只接受内置主题 id，避免 URL 里塞任意变量覆盖界面。
 */
const forced = new URLSearchParams(location.search).get('theme')
if (forced && BUILTIN_THEMES.some((t) => t.id === forced)) {
  theme.setTheme(forced)
  // setTheme 会持久化；这里恢复用户原本的选择，让刷新后回到自己的主题
  history.replaceState(null, '', location.pathname + location.hash)
} else {
  theme.setTheme(theme.current.value.id)
}

/**
 * 语言包先于挂载加载：否则首屏会闪一次中文原文，再跳成用户选择的语言。
 * 加载失败时 i18n.js 保留内置兜底文案，页面照常渲染。
 */
loadI18n().finally(() => {
  createApp(App).use(router).mount('#app')
})