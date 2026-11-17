/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 游玩时长格式化：秒 → "12 小时 34 分" / "45 分" / "不足 1 分"
 */
import { t } from './i18n.js'

export function formatPlaytime(seconds) {
  const s = Math.max(0, Math.floor(seconds || 0))
  if (s < 60) return s > 0 ? t('不足 1 分钟') : t('未游玩')
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  if (h > 0) return t("{0} 小时 {1} 分", h, m)
  return t("{0} 分钟", m)
}

/** 简短版，用于卡片角标 */
export function formatPlaytimeShort(seconds) {
  const s = Math.max(0, Math.floor(seconds || 0))
  if (s < 60) return '<1m'
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  return h > 0 ? `${h}h${m}m` : `${m}m`
}

/** 字节数 → "12.3 MB" / "56 KB"，用于文件体积展示 */
export function formatBytes(bytes) {
  const n = Math.max(0, Number(bytes) || 0)
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
}
