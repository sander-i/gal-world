/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 游玩窗口的启动进度模型（纯函数，无 DOM 依赖，便于单测）。
 *
 * 进度不是真实字节数——Selkies 的握手与首帧对父页面不可见。
 * 这里按阶段给区间，并在阶段内用缓出曲线随时间推进：
 * 阶段切换时跳到新区间，但永不回退；阶段完成时到达上限。
 *
 *   prepare 2-10   查询会话状态
 *   start   10-35  等待游戏进程 RUNNING
 *   stream  35-70  等待串流端口监听
 *   player  70-90  加载播放器页面
 *   frame   90-100 等待首帧画面
 */
import { t } from './i18n.js'

export const PHASES = ['prepare', 'start', 'stream', 'player', 'frame']

export const PHASE_LABELS = {
  prepare: t('查询会话状态…'),
  start: t('正在启动游戏…'),
  stream: t('连接串流服务…'),
  player: t('加载播放器…'),
  frame: t('等待画面…'),
}

const RANGES = {
  prepare: [2, 10],
  start: [10, 35],
  stream: [35, 70],
  player: [70, 90],
  frame: [90, 100],
}

/** 缓出：开头快、接近上限时慢，避免进度条早早卡在 99% */
const easeOut = (p) => 1 - (1 - p) * (1 - p)

export function bootPercent(phase, elapsedMs, expectedMs) {
  const range = RANGES[phase]
  if (!range) return 0
  const ratio = expectedMs > 0 ? Math.min(1, Math.max(0, elapsedMs) / expectedMs) : 0
  return Math.round(range[0] + (range[1] - range[0]) * easeOut(ratio))
}

export function nextPercent(phase, currentPercent) {
  const range = RANGES[phase]
  if (!range) return currentPercent
  return Math.max(currentPercent, range[0])
}

/**
 * 打开游玩页时，会话状态 → 页面形态。
 *
 * 关键约束：非 RUNNING 一律不自动启动；异常终止单独成态。
 */
export function viewForSession(st) {
  if (!st) return 'error'
  if (st.state === 'FAILED' || st.crashed) return 'crashed'
  if (st.state === 'RUNNING') return 'booting'
  return 'not-started'
}

/** 诊断结果是否表示「正常退出」：退出码 0 且无终止信号 */
export function isCleanExit(diagnostics) {
  return !!diagnostics && diagnostics.exitCode === 0 && !diagnostics.signal
}

/**
 * 启动阶段收到会话状态后该做什么。
 * 显式启动后的 UNKNOWN 是 systemd 的瞬时值（activating），继续等待；
 * 打开页面时的 UNKNOWN 说明会话没起来，直接提示未启动。
 * 返回 'running' | 'crashed' | 'halt' | 'wait'
 */
export function bootDecision(st, startedByUser) {
  if (!st) return 'wait'
  if (st.state === 'FAILED' || st.crashed) return 'crashed'
  if (st.state === 'RUNNING') return 'running'
  if (st.state === 'UNKNOWN' && startedByUser) return 'wait'
  return 'halt'
}

/**
 * 游玩中轮询到会话变化时，是否需要中断画面。
 * 返回 null 表示继续游玩；stopped 与 crashed 必须分开，
 * 空闲超时自动停止不是崩溃，不能报错误。
 *
 * UNKNOWN 也按「继续游玩」处理：systemd 的 is-active 在
 * activating/deactivating 期间返回 unknown，若把这种瞬时值当成
 * 停止就会把玩家从画面上踢出去。真正的停止由 STOPPED/FAILED 反映。
 */
export function interruptionFor(st) {
  if (!st) return null
  if (st.state === 'FAILED' || st.crashed) return 'crashed'
  if (st.state === 'UNKNOWN') return null
  if (st.state !== 'RUNNING') return 'stopped'
  return null
}
