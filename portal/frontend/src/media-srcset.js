/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 派生图 srcset。
 *
 * `originalOnly=true` 时返回 undefined，让浏览器只能使用 src 原图；
 * 用于详情页这类要求画质、且没有列入原图候选的地方。
 *
 * 不能返回空字符串：`srcset=""` 是空候选集，浏览器会连 src 一起放弃加载。
 */
export function srcsetOf(item, originalOnly = false) {
  if (originalOnly) return undefined
  const vars = item?.variants || []
  if (!vars.length) return undefined
  return vars.map((v) => `${v.url} ${v.w}w`).join(', ')
}
