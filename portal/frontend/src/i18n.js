/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { computed, ref } from 'vue'

/**
 * 前端国际化。
 *
 * 语言包由后端提供（`/api/i18n`），与后端异常文案共用同一份 JSON：
 * 加一门语言只需要往 `<root>/data/i18n/` 放一个文件，前端、后端同时生效，
 * 不用改代码、不用重新打包。
 *
 * 本机选择存 localStorage，优先于站点默认语言；`?lang=` 可临时切换，
 * 用于验收与截图，不写回存储。
 */
export const LOCALE_STORAGE_KEY = 'galworld.locale'
export const DEFAULT_LOCALE = 'en'

/** 请求头：后端 LocaleFilter 按它决定异常文案语言 */
export const LOCALE_HEADER = 'X-GalWorld-Locale'

/**
 * 兜底文案：只覆盖启动期与「语言包加载失败」这两个场景。
 *
 * 完整文案来自后端；这里留一份最小的英文，保证 API 不可达时页面不是一片键名。
 * 键是中文原文（与后端同一套 gettext 风格），值是英文兜底。
 */
const FALLBACK = {
  '加载中…': 'Loading…',
  '重试': 'Retry',
  '保存': 'Save',
  '取消': 'Cancel',
  '确定': 'Confirm',
  '关闭': 'Close',
  '删除': 'Delete',
  '编辑': 'Edit',
  '语言包加载失败，使用内置文案': 'Failed to load language pack, using built-in text',
}

const locale = ref(DEFAULT_LOCALE)
const messages = ref({ ...FALLBACK })
const languages = ref([])
const defaultLocale = ref(DEFAULT_LOCALE)
const loading = ref(false)
/** 语言包加载失败时的提示，供设置页展示 */
const loadError = ref('')

const currentName = computed(() => {
  const hit = languages.value.find((l) => l.id === locale.value)
  return hit ? hit.name || hit.id : locale.value
})

/** 归一化语言标签：zh-hant→zh-TW、en-US→en 等，与后端 LocalePack.normalize 一致 */
export function normalizeLocale(raw) {
  const v = String(raw || '').trim().replace(/_/g, '-')
  if (!v) return ''
  const lower = v.toLowerCase()
  if (lower === 'zh' || lower.startsWith('zh-cn') || lower.startsWith('zh-hans') || lower === 'zh-sg') {
    return 'zh-CN'
  }
  if (lower.startsWith('zh-tw') || lower.startsWith('zh-hk') || lower.startsWith('zh-mo')
    || lower.startsWith('zh-hant')) {
    return 'zh-TW'
  }
  if (lower.startsWith('ja')) return 'ja'
  if (lower.startsWith('en')) return 'en'
  const parts = v.split('-')
  return parts.length === 1
    ? parts[0].toLowerCase()
    : `${parts[0].toLowerCase()}-${parts[1].toUpperCase()}`
}

/** 浏览器首选语言里第一个已安装的；都没有时返回空串 */
function detectBrowserLocale(installed) {
  const prefs = navigator.languages && navigator.languages.length
    ? navigator.languages
    : [navigator.language]
  for (const p of prefs) {
    const norm = normalizeLocale(p)
    if (installed.some((l) => l.id === norm)) return norm
  }
  return ''
}

/**
 * 插值：`{0}` 位置参数，或 `{name}` 具名参数。
 *
 * 缺失的参数保留占位符本身——页面上能直接看出漏传，不会静默变空白。
 */
export function formatMessage(template, args) {
  if (!template) return ''
  if (args == null) return template
  if (Array.isArray(args)) {
    return template.replace(/\{(\d+)\}/g, (m, i) => (args[i] == null ? m : String(args[i])))
  }
  if (typeof args === 'object') {
    return template.replace(/\{([A-Za-z0-9_]+)\}/g, (m, k) =>
      (args[k] == null ? m : String(args[k])))
  }
  return template.replace(/\{0\}/g, String(args))
}

/**
 * 翻译。
 *
 * 兜底链：当前语言 → 键名（中文原文）。
 *
 * 缺键时直接给原文，不落到英文：与后端 I18nService.translate 一致——
 * 这个项目的用户以中文玩家为主，缺译处显示原文比显示英文更好读，
 * 也便于一眼看出哪些还没翻译。FALLBACK 只覆盖启动期与语言包加载失败。
 */
export function t(key, args) {
  const msg = messages.value[key]
  if (msg != null) return formatMessage(msg, args)
  const fallback = FALLBACK[key]
  return fallback != null ? formatMessage(fallback, args) : key
}

/** 供 `useI18n()` 之外的地方读取当前语言（api.js 拼请求头用） */
export function currentLocale() {
  return locale.value
}

/** 当前语言是否已就绪 */
export function i18nReady() {
  return !loading.value && !loadError.value
}

/**
 * 把语言写到 <html lang> 与 <title>。
 *
 * index.html 里的 lang 与 title 是静态中文，切换语言时不更新的话，
 * 浏览器会按错误语言做断行与朗读，标签页标题也一直是中文。
 * 标题走语言包的「Gal 世界」键，缺译时回退原文。
 */
function applyDocumentLang(id) {
  if (typeof document === 'undefined') return
  document.documentElement.lang = id
  const title = t('Gal 世界')
  if (title && title !== 'Gal 世界') {
    document.title = title
  }
}

/** 拉语言清单 */
export async function fetchLanguages() {
  const res = await fetch('/api/i18n', { cache: 'no-store' })
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  const data = await res.json()
  languages.value = Array.isArray(data.languages) ? data.languages : []
  defaultLocale.value = normalizeLocale(data.defaultLocale) || DEFAULT_LOCALE
  return data
}

/** 拉某语言的消息表 */
async function fetchMessages(id) {
  const res = await fetch(`/api/i18n/${encodeURIComponent(id)}`, { cache: 'no-store' })
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  const data = await res.json()
  return data && typeof data.messages === 'object' ? data.messages : {}
}

/**
 * 拉当前语言的消息表；整包拉不到时退到站点默认语言。
 *
 * 这与后端 I18nService.translate 的兜底链一致：语言包不存在 → 站点默认语言，
 * 语言包存在但缺键 → 中文原文。缺键不回退英文，因为本站用户以中文玩家为主，
 * 显示原文比显示英文更好读，也便于一眼看出哪些还没翻译。
 */
async function fetchPackOrFallback(id) {
  try {
    return await fetchMessages(id)
  } catch (e) {
    const site = defaultLocale.value
    if (site && site !== id) {
      try {
        return await fetchMessages(site)
      } catch {
        // 站点默认语言也拉不到时抛出原始错误
      }
    }
    throw e
  }
}

/** 决定启动语言：?lang= → localStorage → 浏览器 → 站点默认 */
function pickInitialLocale() {
  const forced = normalizeLocale(new URLSearchParams(location.search).get('lang'))
  if (forced) return forced
  const stored = normalizeLocale(localStorage.getItem(LOCALE_STORAGE_KEY))
  if (stored) return stored
  const detected = detectBrowserLocale(languages.value)
  if (detected) return detected
  return defaultLocale.value || DEFAULT_LOCALE
}

/**
 * 启动加载。
 *
 * 失败时保留兜底英文并记下错误，页面仍可渲染；设置页会提示「语言包加载失败」。
 */
export async function loadI18n() {
  loading.value = true
  loadError.value = ''
  try {
    await fetchLanguages()
    const wanted = pickInitialLocale()
    const installed = languages.value.some((l) => l.id === wanted)
    const id = installed ? wanted : (defaultLocale.value || DEFAULT_LOCALE)
    messages.value = await fetchPackOrFallback(id)
    locale.value = id
    applyDocumentLang(id)
  } catch (e) {
    loadError.value = e.message || String(e)
  } finally {
    loading.value = false
  }
}

/** 切换语言：写 localStorage 并重新拉消息表 */
export async function setLocale(id, { persist = true } = {}) {
  const norm = normalizeLocale(id)
  if (!norm || norm === locale.value) return
  loading.value = true
  loadError.value = ''
  try {
    messages.value = await fetchPackOrFallback(norm)
    locale.value = norm
    if (persist) localStorage.setItem(LOCALE_STORAGE_KEY, norm)
    applyDocumentLang(norm)
    await fireLocaleChange()
  } catch (e) {
    loadError.value = e.message || String(e)
  } finally {
    loading.value = false
  }
}

/** 语言包变化后重新加载当前语言（后台安装 / 删除后调用） */
export async function reloadI18n() {
  await loadI18n()
}

/**
 * 语言切换后需要重取服务端数据的回调。
 *
 * 后端返回的文案（部署步骤标题、环境信息、会话诊断…）是按请求时的
 * Accept-Language / X-GalWorld-Locale 翻译的，切换语言后已取到的那份
 * 仍是旧语言。页面注册一个重取函数即可，不必各自 watch locale。
 */
const reloadHooks = new Set()

/** 注册「语言变了请重取数据」，返回注销函数 */
export function onLocaleChange(fn) {
  reloadHooks.add(fn)
  return () => reloadHooks.delete(fn)
}

async function fireLocaleChange() {
  for (const fn of reloadHooks) {
    try {
      await fn(locale.value)
    } catch {
      // 单个页面的重取失败不能影响其它订阅者
    }
  }
}

/** 组件里用：响应式的 t / 当前语言 / 语言清单 */
export function useI18n() {
  return {
    t,
    locale,
    languages,
    defaultLocale,
    currentName,
    loading,
    loadError,
    setLocale,
  }
}
