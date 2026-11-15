/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/** 向导可提交判定：与后端校验保持一致的最小集合 */
export function canSubmit(form) {
  const w = Number(form?.width)
  const h = Number(form?.height)
  return !!(form?.name || '').trim()
    && !!form?.exe
    && w >= 640 && w <= 3840
    && h >= 480 && h <= 2160
}

export function runPhase(state) {
  if (state === 'SUCCESS') return 'done'
  if (state === 'FAILED' || state === 'CANCELLED') return 'failed'
  return 'running'
}

/** 按 id 取步骤状态，没有时返回 PENDING 占位 */
export function stepOf(steps, id) {
  return (steps || []).find((s) => s.id === id) || { id, state: 'PENDING', title: id }
}

/** SSE 事件归并；返回新对象，便于 Vue 响应式 */
export function mergeEvent(acc, name, data) {
  if (name === 'log') {
    return { ...acc, lines: [...acc.lines, String(data)] }
  }
  if (name === 'step') {
    let evt
    try {
      evt = JSON.parse(data)
    } catch {
      return acc
    }
    if (!evt?.id) return acc
    const rest = (acc.steps || []).filter((s) => s.id !== evt.id)
    return {
      ...acc,
      steps: [...rest, { id: evt.id, state: evt.state, title: evt.title || evt.id }],
    }
  }
  if (name === 'state') {
    return { ...acc, state: data === 'done' ? 'SUCCESS' : String(data) }
  }
  return acc
}

/** 备用 exe 候选：排除当前主用项，避免选到同一个文件 */
export function altOptions(exes, current) {
  return (exes || []).filter((e) => e.path !== current)
}

export function formatBytes(n) {
  const v = Number(n) || 0
  if (v < 1024) return `${v} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let x = v / 1024
  let i = 0
  while (x >= 1024 && i < units.length - 1) {
    x /= 1024
    i++
  }
  return `${x.toFixed(1)} ${units[i]}`
}
