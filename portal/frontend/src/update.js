/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 远程更新的纯函数：确认文本、可否执行、阶段文案。
 *
 * 更新会重启 Portal，状态来自 `GET /api/update/status` 的 phase 字段；
 * 这里只做映射，不碰 DOM 与网络，便于 node --test 直接覆盖。
 */

export function confirmText(version) {
  // 与后端 Version.normalize 一致：去 v 前缀，并截掉 - 后缀（v0.1.4-pr7 → 0.1.4）
  const v = String(version || '').trim().replace(/^v/i, '').split('-')[0]
  return `UPDATE ${v}`
}

/** 覆盖更新的确认文本：原样使用输入，不剥 v 前缀、不截后缀 */
export function overrideConfirmText(ref) {
  return `OVERRIDE ${String(ref || '').trim()}`
}

const ACTIVE = new Set([
  'queued', 'precheck', 'downloading', 'verifying', 'backing_up',
  'stopping', 'replacing', 'starting',
])

// 与后端 UpdateService.STALE_MS 保持一致：活跃阶段超过 10 分钟未推进视为残留。
const STALE_MS = 10 * 60 * 1000

/** 更新任务是否仍在进行（进行中不允许再次申请） */
export function isActivePhase(phase) {
  return ACTIVE.has(String(phase || ''))
}

/** 活跃状态是否已过期；没有可用时间戳（旧状态文件）也按残留处理。 */
export function isStaleStatus(status) {
  const at = status?.phaseStartedAt || status?.startedAt
  if (!at) return true
  const started = Date.parse(at)
  return Number.isNaN(started) || Date.now() - started >= STALE_MS
}

export function canApply(result, status) {
  if (!result?.hasUpdate) return false
  return !isActivePhase(status?.phase) || isStaleStatus(status)
}

/** 覆盖更新是否可提交：需要后台开启、非空输入、且没有未过期的活跃任务 */
export function canOverride(enabled, ref, status) {
  if (!enabled) return false
  if (!String(ref || '').trim()) return false
  return !isActivePhase(status?.phase) || isStaleStatus(status)
}

export function phaseLabel(phase) {
  return {
    idle: '空闲',
    queued: '排队中',
    precheck: '预检',
    downloading: '下载更新包',
    verifying: '校验更新包',
    backing_up: '备份数据库',
    stopping: '停止服务',
    replacing: '替换版本',
    starting: '启动服务',
    success: '更新完成',
    rolled_back: '已回滚',
    failed: '更新失败',
  }[phase] || phase || ''
}
