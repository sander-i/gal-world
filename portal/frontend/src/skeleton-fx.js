/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 骨架入场动效：按视觉行给 .sk 写 --i，并重放动画。
 *
 * 配套样式在 styles.css 的「骨架入场动效（L4）」段：
 *   --i 决定每块的进场延迟（26ms/行）与流光延迟（55ms/行）。
 *
 * 三个顺序约束（改这段代码前先看 docs/ROUTE-TRANSITION-PLAN.md 的 D8–D10）：
 *   1. 先让骨架可见再量位置 —— display:none 下所有 top 都是 0，会把全部块压成一行；
 *   2. 写完 --i 要重放动画 —— 延迟在动画启动那一刻就定了，改变量不会回溯；
 *   3. 推荐直接用 activateSkeleton()，它把三步按正确顺序串好。
 */

/** 同一视觉行的顶边容差（px）。同行块高度不同（图标 34、标题 24），顶边不完全对齐。 */
const ROW_TOLERANCE = 6

/**
 * 按视觉行分组：顶边相差 6px 以内算同一行。
 *
 * 纯函数，不碰 DOM，便于 node 测试。输入是各 .sk 的
 * getBoundingClientRect().top（按 DOM 顺序），输出等长的行号数组。
 *
 * 用「找最近行」而不是「比较相邻元素」：同一行的块不一定连续出现在 DOM 里
 * （例如详情页的标题与操作按钮中间夹着别的块），按相邻比较会拆成多行。
 *
 * @param {number[]} tops
 * @returns {number[]} 每项是该块的行号，从 0 递增
 */
export function groupRows(tops) {
  const rows = []
  const out = []
  for (const top of tops) {
    const row = rows.find((r) => Math.abs(r.top - top) <= ROW_TOLERANCE)
    if (row) {
      out.push(row.i)
    } else {
      rows.push({ top, i: rows.length })
      out.push(rows.length - 1)
    }
  }
  return out
}

/** 取 root 内全部 .sk（root 为 null 时返回空数组） */
function skItems(root) {
  if (!root || typeof root.querySelectorAll !== 'function') return []
  return Array.from(root.querySelectorAll('.sk'))
}

/**
 * 给 root 内的 .sk 按视觉行写 --i。
 *
 * 必须在骨架可见之后调用（D9）。返回行数，便于调试；root 为空时返回 0。
 *
 * @param {Element | null | undefined} root
 * @returns {number}
 */
export function numberSkeleton(root) {
  if (typeof window === 'undefined') return 0
  const items = skItems(root)
  if (!items.length) return 0
  const rows = groupRows(items.map((el) => el.getBoundingClientRect().top))
  items.forEach((el, i) => el.style.setProperty('--i', String(rows[i])))
  return Math.max(...rows) + 1
}

/**
 * 重放动画：--i 是在 active 类加上之后才写的，不重置的话本轮动画
 * 已经按旧延迟（默认 0）启动，第二次进入同一页时所有块会同时出现（D10）。
 *
 * 重置 animation 后读一次 offsetWidth 强制重排，让浏览器按新的 --i 重新计算延迟。
 *
 * @param {Element | null | undefined} root
 */
export function replaySkeletonFx(root) {
  if (typeof window === 'undefined') return
  for (const el of skItems(root)) {
    el.style.animation = 'none'
    void el.offsetWidth
    el.style.animation = ''
  }
}

/**
 * 推荐入口：add('active') → numberSkeleton → replaySkeletonFx，顺序固定（D9/D10）。
 *
 * 顺序不能改：display:none 下所有 getBoundingClientRect().top 都是 0，
 * 先量位置会把全部块压成一行，错峰退化成整屏一起出现。
 *
 * @param {Element | null | undefined} root 骨架页根节点（带 .page-skeleton 的那个）
 * @returns {number} 视觉行数
 */
export function activateSkeleton(root) {
  if (typeof window === 'undefined' || !root || !root.classList) return 0
  root.classList.add('active')
  const rows = numberSkeleton(root)
  replaySkeletonFx(root)
  return rows
}
