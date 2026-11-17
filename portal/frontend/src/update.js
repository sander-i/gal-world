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

import { t } from './i18n.js'

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
    idle: t('空闲'),
    queued: t('排队中'),
    precheck: t('预检'),
    downloading: t('下载更新包'),
    verifying: t('校验更新包'),
    backing_up: t('备份数据库'),
    stopping: t('停止服务'),
    replacing: t('替换版本'),
    starting: t('启动服务'),
    success: t('更新完成'),
    rolled_back: t('已回滚'),
    failed: t('更新失败'),
  }[phase] || phase || ''
}

/**
 * 更新状态里的说明文字。
 *
 * 这段由 deploy/bin/updater.sh 直接写进 update-status.json，后端只透传、
 * 不经 I18n，切语言后仍是中文。已知的固定句式在这里按当前语言重排；
 * 认不出来的（更新器里的各类失败原因）原样显示——那些是排障线索，
 * 猜着翻译反而更糟。
 */
export function updateMessage(status) {
  const raw = String(status?.message || '').trim()
  if (!raw) return ''
  let m = /^更新完成\s+(\S+)\s*->\s*(\S+)$/.exec(raw)
  if (m) return t("更新完成 {0} -> {1}", m[1], m[2])
  m = /^已回滚到\s+(\S+)$/.exec(raw)
  if (m) return t("已回滚到 {0}", m[1])
  m = /^当前\s+(\S+)，目标\s+(\S+)$/.exec(raw)
  if (m) return t("当前 {0}，目标 {1}", m[1], m[2])
  return raw
}
