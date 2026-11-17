/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 列表页 → 游戏详情 的返回上下文。
 *
 * 详情页不知道"我是从哪一页点进来的"：SPA 里 router.back() 不可靠
 * （直接贴链接打开时没有历史）。所以用 URL 查询参数显式带上来源页的筛选状态。
 *
 * 选查询参数而不是 sessionStorage：
 *  - 刷新详情页后返回目标仍然正确；
 *  - 多标签页并行浏览时互不干扰；
 *  - 分享/收藏链接时状态可复现。
 *
 * 约定：
 *  - 从列表页进入：/game/xxx?from=list&tag=校园&company=3&q=abc&density=compact
 *  - 从首页进入、或直接访问：不带 from 参数 → 返回首页
 */

/** 详情页链接：带上来源页的筛选与密度 */
import { t } from './i18n.js'

export function gameLink(gameId, listState) {
  if (!listState) {
    return `/game/${encodeURIComponent(gameId)}`
  }
  const qs = new URLSearchParams({ from: 'list' })
  if (listState.tag) qs.set('tag', listState.tag)
  if (listState.company != null && listState.company !== '') qs.set('company', String(listState.company))
  if (listState.localization != null && listState.localization !== '') qs.set('localization', String(listState.localization))
  if (listState.q) qs.set('q', listState.q)
  if (listState.density) qs.set('density', listState.density)
  return `/game/${encodeURIComponent(gameId)}?${qs.toString()}`
}

/**
 * 详情页返回按钮的目的地与文案。
 *
 * 从列表页进来 → 回列表页并还原筛选；否则回首页。
 */
export function backTarget(route) {
  const q = route.query || {}
  if (q.from === 'list') {
    const query = {}
    if (q.tag) query.tag = String(q.tag)
    if (q.company != null && q.company !== '') query.company = String(q.company)
    if (q.localization != null && q.localization !== '') query.localization = String(q.localization)
    if (q.q) query.q = String(q.q)
    if (q.density) query.density = String(q.density)
    return { path: '/games', query, label: t('← 全部游戏') }
  }
  return { path: '/', query: {}, label: t('← 游戏库') }
}

/** 列表页从 URL 读回筛选状态（首次进入或从详情页返回时都走这里） */
export function readListQuery(route) {
  const q = route.query || {}
  const company = q.company
  return {
    tag: q.tag ? String(q.tag) : '',
    // company 是数字 id；URL 里是字符串，取不到数字时当作未筛选
    company: company != null && company !== '' && !Number.isNaN(Number(company))
      ? Number(company) : null,
    localization: q.localization != null && q.localization !== ''
      && !Number.isNaN(Number(q.localization)) ? Number(q.localization) : null,
    q: q.q ? String(q.q) : '',
    density: q.density ? String(q.density) : '',
  }
}

/** 列表页要把状态写回 URL，这样从详情页返回时能带上 */
export function listQuery(state) {
  const out = {}
  if (state.tag) out.tag = state.tag
  if (state.company != null) out.company = String(state.company)
  if (state.localization != null) out.localization = String(state.localization)
  if (state.q) out.q = state.q
  if (state.density) out.density = state.density
  return out
}
