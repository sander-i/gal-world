/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/** 拖动排序的纯函数 */

/** 把 from 位置的元素移到 to；越界或原地返回副本 */
export function moveItem(list, from, to) {
  const out = [...(list || [])]
  if (from < 0 || to < 0 || from >= out.length || to >= out.length || from === to) {
    return out
  }
  const [item] = out.splice(from, 1)
  out.splice(to, 0, item)
  return out
}

/** 按当前顺序取 id 列表 */
export function idsOf(list) {
  return (list || []).map((x) => x.id)
}
