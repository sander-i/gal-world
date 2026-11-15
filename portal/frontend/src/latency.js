/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/** 时延分档：≤60ms 良好，≤150ms 一般，其余偏差（纯函数，便于单测） */
export function latencyTone(ms) {
  if (ms == null) return ''
  if (ms <= 60) return 'good'
  if (ms <= 150) return 'warn'
  return 'bad'
}