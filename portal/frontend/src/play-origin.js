/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { t } from './i18n.js'
import { listQuery, readListQuery } from './list-origin.js'

/**
 * 游玩页的来源上下文。
 *
 * 与 list-origin 一样用 URL 查询参数而不是 router.back()：直接贴地址打开
 * 时没有历史可退，刷新后也不能丢来源。
 *
 *   /play/xxx                    直接访问 → 首页
 *   /play/xxx?from=game          详情页 → 该游戏详情
 *   /play/xxx?from=import        接入页 → 接入页
 *   /play/xxx?from=list&tag=...  列表页 → 列表并还原筛选
 */
export function playLink(gameId, from = '', listState = null) {
  const base = `/play/${encodeURIComponent(gameId)}`
  if (from === 'list') {
    const qs = new URLSearchParams({ from: 'list', ...listQuery(listState || {}) })
    return `${base}?${qs.toString()}`
  }
  if (from === 'game' || from === 'import') {
    return `${base}?from=${from}`
  }
  return base
}

export function playBackTarget(route) {
  const q = route.query || {}
  if (q.from === 'list') {
    return { path: '/games', query: listQuery(readListQuery(route)), label: t('← 全部游戏') }
  }
  if (q.from === 'game') {
    const id = route.params?.id ?? ''
    return { path: `/game/${encodeURIComponent(id)}`, query: {}, label: t('← 游戏详情') }
  }
  if (q.from === 'import') {
    return { path: '/games/import', query: {}, label: t('← 接入游戏') }
  }
  return { path: '/', query: {}, label: t('← 游戏库') }
}