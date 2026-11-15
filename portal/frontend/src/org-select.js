/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/** 公司/汉化组多选下拉的纯逻辑，便于单测 */

/** 名称或别名包含查询词（忽略大小写与首尾空白） */
export function filterOrgs(orgs, query) {
  const q = (query || '').trim().toLowerCase()
  if (!q) return orgs
  return orgs.filter((o) =>
    (o.name || '').toLowerCase().includes(q)
    || (o.alias || '').toLowerCase().includes(q))
}

function sameOrg(a, b) {
  if (a.id != null && b.id != null) return a.id === b.id
  return (a.name || '') === (b.name || '')
    && (a.alias || '') === (b.alias || '')
    && (a.website || '') === (b.website || '')
}

export function isSelected(list, org) {
  return (list || []).some((x) => sameOrg(x, org))
}

/** 已选则移除，未选则加入（保留原对象字段） */
export function toggleOrg(list, org) {
  const current = list || []
  if (isSelected(current, org)) {
    return current.filter((x) => !sameOrg(x, org))
  }
  return [...current, org]
}

/** 名称或别名的精确匹配项（忽略大小写与首尾空白），无命中返回 null */
export function findExactMatch(orgs, query) {
  const q = (query || '').trim().toLowerCase()
  if (!q) return null
  return (orgs || []).find((o) =>
    (o.name || '').toLowerCase() === q || (o.alias || '').toLowerCase() === q) || null
}

/**
 * 回车 / 主操作的决策。
 * filtered 必须是已经按查询词过滤过的列表。
 * - 名称或别名精确命中 → 选中该条
 * - 只剩唯一近似匹配 → 选中它
 * - 允许新建且完全没有匹配 → 创建
 * - 其余（多个近似匹配、空查询）→ null，什么都不做
 */
export function resolveEnterAction(filtered, query, creatable) {
  const q = (query || '').trim()
  if (!q) return null
  const list = filtered || []
  const exact = findExactMatch(list, q)
  if (exact) return { kind: 'select', org: exact }
  if (list.length === 1) return { kind: 'select', org: list[0] }
  if (creatable && list.length === 0) return { kind: 'create', name: q }
  return null
}

/** 按维护页顺序重排已选项；字典中找不到的项保持相对顺序排在末尾 */
export function sortByMaintenanceOrder(selected, options) {
  const list = selected || []
  const opts = options || []
  const rank = (o) => {
    const i = opts.findIndex((x) => sameOrg(x, o))
    return i < 0 ? Number.MAX_SAFE_INTEGER : i
  }
  return list
    .map((o, i) => ({ o, i, r: rank(o) }))
    .sort((x, y) => x.r - y.r || x.i - y.i)
    .map((x) => x.o)
}
