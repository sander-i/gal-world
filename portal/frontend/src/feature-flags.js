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

/** 首次进入时加载一次；失败时保持默认开启 */
export async function loadFeatureFlags() {
  try {
    applySettings(await api.settings())
  } catch {
    // 后端不可用时不影响页面渲染，保持默认启用
  }
}
