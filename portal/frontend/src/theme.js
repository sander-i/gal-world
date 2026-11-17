/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { t } from './i18n.js'

export const THEME_STORAGE_KEY = 'galworld.theme'

const BASE_VARS = {
  '--radius': '10px',
  '--r-ctl': '8px',
}

/** 内置主题：深色三套 + 浅色一套。vars 为完整覆盖，保证与现有视觉一致 */
export const BUILTIN_THEMES = [
  {
    id: 'obsidian', nameKey: '黑曜石', base: 'dark', builtin: true,
    vars: {}, // 空表示使用 styles.css 的 :root 默认值
  },
  {
    id: 'midnight', nameKey: '深海', base: 'dark', builtin: true,
    vars: deriveVars({
      bg: '#0b1119', surface: '#121a24', line: '#1e2a38', text: '#e2ecf5',
      muted: '#7d8fa3', accent: '#4fa8c8', ok: '#5ec49a', warn: '#d99a5c', err: '#d96c7c',
    }),
  },
  {
    // 樱：暗紫底 + 樱粉强调。
    // 原配色的问题：底色与表面几乎同明度（#140f14 / #1d1620），卡片浮不起来；
    // 强调色 #e08aa8 与错误色 #e07a8a 只差一点，删除按钮和主按钮看着一样红。
    // 现在拉开三层明度，强调色提亮到浅樱粉，错误色改向正红并压暗。
    id: 'sakura', nameKey: '樱', base: 'dark', builtin: true,
    vars: deriveVars({
      bg: '#16111a',          // 底色略微提亮、偏紫，与纯黑拉开
      surface: '#221a28',     // 表面比底色亮一档，卡片能浮起来
      line: '#3a2d43',        // 边框再亮一档，轮廓清楚
      text: '#f4ecf4',        // 正文近白偏粉
      muted: '#a495ae',       // 次要文字，与正文有足够对比
      accent: '#f2a6c0',      // 樱粉：比原 #e08aa8 更亮，做主按钮不发灰
      ok: '#7fc9a0',          // 成功：偏青绿，与樱粉互补
      warn: '#e0a862',        // 警告：暖橙，与樱粉区分
      err: '#e05a6e',         // 错误：正红偏品红，明显区别于樱粉
    }),
  },
  {
    // 素纸：米白底 + 赭石强调。
    // 原配色的问题：底 #f6f2ea 与表面 #fffdf8 差异太小，卡片边界几乎看不见；
    // 强调色 #a0682c 在浅底上偏浑，主按钮不突出。
    // 现在加深底色分层、边框更实、强调色更饱和。
    id: 'paper', nameKey: '素纸', base: 'light', builtin: true,
    vars: deriveVars({
      bg: '#efe9dd',          // 底色加深一档，与白卡片形成对比
      surface: '#fffcf5',     // 表面近白，卡片能看清边界
      line: '#d8cdbb',        // 边框加深，轮廓清楚
      text: '#2a2520',        // 正文深褐，比纯黑柔和
      muted: '#6f6558',       // 次要文字，浅底上仍可读
      accent: '#b06a1e',      // 赭石：比原 #a0682c 更饱和，主按钮跳出来
      ok: '#3d7a48',          // 成功：深绿，浅底上对比够
      warn: '#a85f18',        // 警告：橙褐
      err: '#b03a3a',         // 错误：正红
    }),
  },
]

/** 由 9 个种子色派生全部变量；自定义主题编辑器只暴露这 9 项 */
export function deriveVars(seed) {
  const v = {
    '--bg': seed.bg,
    '--surface': seed.surface,
    '--surface-2': `color-mix(in srgb, ${seed.surface} 88%, ${seed.text})`,
    '--surface-3': `color-mix(in srgb, ${seed.surface} 70%, ${seed.bg})`,
    '--surface-deep': `color-mix(in srgb, ${seed.bg} 80%, #000)`,
    '--line': seed.line,
    '--line-strong': `color-mix(in srgb, ${seed.line} 70%, ${seed.text})`,
    '--text': seed.text,
    '--text-dim': `color-mix(in srgb, ${seed.text} 78%, ${seed.surface})`,
    '--muted': seed.muted,
    '--muted-soft': `color-mix(in srgb, ${seed.muted} 70%, ${seed.surface})`,
    '--accent': seed.accent,
    '--accent-hi': `color-mix(in srgb, ${seed.accent} 80%, ${seed.text})`,
    '--accent-lo': `color-mix(in srgb, ${seed.accent} 80%, #000)`,
    '--accent-dim': `color-mix(in srgb, ${seed.accent} 55%, ${seed.surface})`,
    '--accent-ink': `color-mix(in srgb, ${seed.accent} 12%, #000)`,
    '--on-accent': seed.base === 'light' ? '#fff' : `color-mix(in srgb, ${seed.accent} 12%, #000)`,
    '--ok': seed.ok,
    '--warn': seed.warn,
    '--err': seed.err,
    '--ok-soft': `color-mix(in srgb, ${seed.ok} 16%, transparent)`,
    '--ok-line': `color-mix(in srgb, ${seed.ok} 35%, transparent)`,
    '--warn-soft': `color-mix(in srgb, ${seed.warn} 14%, transparent)`,
    '--warn-line': `color-mix(in srgb, ${seed.warn} 30%, transparent)`,
    '--err-soft': `color-mix(in srgb, ${seed.err} 16%, transparent)`,
    '--err-line': `color-mix(in srgb, ${seed.err} 35%, transparent)`,
    '--muted-soft-bg': `color-mix(in srgb, ${seed.muted} 14%, transparent)`,
    '--muted-soft-line': `color-mix(in srgb, ${seed.muted} 28%, transparent)`,
    '--cover-gradient': 'linear-gradient(135deg, var(--surface-2), var(--surface-3))',
    '--hero-body-gradient': 'linear-gradient(100deg, var(--surface), var(--surface-3))',
    '--glass-bg': `color-mix(in srgb, ${seed.surface} 72%, transparent)`,
    '--overlay': 'color-mix(in srgb, #000 58%, transparent)',
    '--shadow': '0 10px 30px color-mix(in srgb, #000 45%, transparent)',
  }
  return { ...BASE_VARS, ...v, _base: seed.base }
}

/** 内置主题的显示名：按当前界面语言现取，避免模块导入期冻结语言 */
export function themeName(theme) {
  if (!theme) return ''
  if (theme.nameKey) return t(theme.nameKey)
  return theme.name || theme.id || ''
}

export function resolveTheme(id, customThemes = []) {
  const custom = customThemes.find((t) => t.id === id)
  if (custom) return custom
  return BUILTIN_THEMES.find((t) => t.id === id) || BUILTIN_THEMES[0]
}

export function parseStoredTheme(raw) {
  if (!raw) return null
  try {
    const t = JSON.parse(raw)
    if (!t || typeof t.id !== 'string' || !t.vars || typeof t.vars !== 'object') return null
    return t
  } catch {
    return null
  }
}

/** 把主题写到 html 上；obsidian 清空内联变量，回落到 :root */
let appliedKeys = []

export function applyTheme(theme, root = document.documentElement) {
  const base = theme.base || 'dark'
  // CSSStyleDeclaration 不把自定义属性暴露在 Object.keys 里，必须自己记账
  for (const k of appliedKeys) root.style.removeProperty(k)
  appliedKeys = []
  for (const [k, val] of Object.entries(theme.vars || {})) {
    if (k.startsWith('--')) {
      root.style.setProperty(k, val)
      appliedKeys.push(k)
    }
  }
  root.dataset.theme = theme.id || 'custom'
  root.dataset.themeBase = base
  root.classList.toggle('dark', base === 'dark')
  root.style.colorScheme = base
}
