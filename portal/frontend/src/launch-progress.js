/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 首页卡片启动进度模型（纯函数，无 DOM 依赖）。
 *
 * 首页只等两件事：游戏进程 RUNNING、串流端口就绪。
 *   start  8-55   等待进程
 *   stream 55-95  等待串流
 *   done   100    就绪
 */
import { t } from './i18n.js'

export const LAUNCH_STEPS = ['start', 'stream', 'done']

/**
 * 启动阶段文案。
 *
 * 做成函数而不是常量：模块级常量在导入期求值，语言切换后不会更新，
 * 用户看到的是首次加载时的语言（2026-11-17 用户报告的问题）。
 */
export function launchLabel(step) {
  switch (step) {
    case 'start': return t('正在启动游戏…')
    case 'stream': return t('连接串流服务…')
    case 'done': return t('已启动')
    case 'stop': return t('正在关闭游戏…')
    case 'closed': return t('已关闭')
    default: return ''
  }
}

const RANGES = {
  start: [8, 55],
  stream: [55, 95],
  done: [100, 100],
}

/** 缓出：开头快、接近上限慢，避免长等待时进度条像卡死 */
const easeOut = (p) => 1 - (1 - p) * (1 - p)

export function launchPercent(step, elapsedMs, expectedMs) {
  const range = RANGES[step]
  if (!range) return 0
  if (step === 'done') return 100
  const ratio = expectedMs > 0 ? Math.min(1, Math.max(0, elapsedMs) / expectedMs) : 0
  return Math.round(range[0] + (range[1] - range[0]) * easeOut(ratio))
}

/** 关闭进度：5→95，轮询确认会话真正停止后由调用方补到 100 */
export function stopPercent(elapsedMs, expectedMs) {
  const ratio = expectedMs > 0 ? Math.min(1, Math.max(0, elapsedMs) / expectedMs) : 0
  return Math.round(5 + 90 * easeOut(ratio))
}

/**
 * 启动轮询拿到一次会话状态后该怎么走（纯函数，便于单测）。
 *
 * 返回 'fail' 表示必须立刻收尾：会话在启动过程中退出了，或后端报崩溃。
 * 只认 FAILED/crashed 是不够的 —— 进程自己退出时 systemd 报 inactive，
 * 后端给出 STOPPED，若继续等就会一直停在 stream 步上限 95% 直到超时。
 *
 * @param {{state?: string, crashed?: boolean}} st 会话状态
 * @param {number} elapsedMs 启动已耗时
 * @param {number} graceMs 启动初期忽略 STOPPED 的宽限期（systemd 瞬时值）
 */
export function startVerdict(st, elapsedMs, graceMs) {
  if (!st) return 'wait'
  if (st.state === 'FAILED' || st.crashed) return 'fail'
  if (st.state === 'STOPPED' && elapsedMs > graceMs) return 'fail'
  return 'wait'
}