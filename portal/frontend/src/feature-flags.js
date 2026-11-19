/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { computed, ref } from 'vue'
import { api } from './api.js'

/**
 * 公司 / 汉化组维度的全局启停。
 *
 * 停用只影响前端展示（入口、筛选、详情、编辑字段），数据与 API 保持可用。
 * 设置缺省时视为启用，只有显式 'false' 才停用。
 */
const flags = ref({ company: true, localization: true, canvasAuto: true })

export const companyEnabled = computed(() => flags.value.company)
export const localizationEnabled = computed(() => flags.value.localization)
export const canvasAutoCalibrate = computed(() => flags.value.canvasAuto)

/** 把 /api/settings 的结果应用到开关；供设置页保存后立即生效 */
export function applySettings(settings) {
  flags.value = {
    company: (settings || {}).companyEnabled !== 'false',
    localization: (settings || {}).localizationGroupEnabled !== 'false',
    canvasAuto: (settings || {}).canvasCalibrateMode !== 'manual',
  }
}

/**
 * /api/settings 的模块级缓存。
 *
 * 生命周期与进程一致：首次调用发起请求，之后所有页面复用同一个 Promise
 * （App.vue、库页、列表页、详情页原来各拉一次，现在只发一次）。
 *
 * 刷新时机：
 * - 设置页保存成功后调用 primeSettings(最新设置)，直接覆盖缓存，不必再请求；
 * - 需要强制重新拉取时调用 invalidateSettings()，下一次 loadSettings() 会重新发请求。
 *
 * 失败不缓存：请求失败时返回空对象（开关保持默认启用），并把 Promise 清掉，
 * 下次调用可以重试——否则一次网络抖动会让整场会话都用默认值。
 */
let settingsPromise = null
let cachedSettings = null

/** 共享的设置请求；失败时返回 {} 且不缓存，开关保持默认启用 */
export function loadSettings() {
  // 请求在途或已成功：复用同一个 Promise
  if (settingsPromise) return settingsPromise
  // 只有缓存、没有 Promise（例如外部清了 Promise）：直接用缓存，不再请求
  if (cachedSettings) {
    settingsPromise = Promise.resolve(cachedSettings)
    return settingsPromise
  }
  settingsPromise = api
    .settings()
    .then((s) => {
      applySettings(s)
      cachedSettings = s
      return s
    })
    .catch(() => {
      // 失败不缓存，允许后续重试
      settingsPromise = null
      cachedSettings = null
      return {}
    })
  return settingsPromise
}

/** 设置页保存后用最新结果覆盖缓存，避免保存后其它页面拿到旧值 */
export function primeSettings(settings) {
  applySettings(settings)
  cachedSettings = settings
  settingsPromise = Promise.resolve(settings)
  return cachedSettings
}

/** 清空缓存；下一次 loadSettings() 会重新请求 */
export function invalidateSettings() {
  settingsPromise = null
  cachedSettings = null
}

/** 首次进入时加载一次；失败已由 loadSettings 兜底为空对象，开关保持默认启用 */
export async function loadFeatureFlags() {
  await loadSettings()
}
