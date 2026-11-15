/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { onBeforeUnmount, ref, watch } from 'vue'
import { probeHealth } from './api'

const INTERVAL_MS = 5000
const TIMEOUT_MS = 3000

/**
 * 浏览器到门户的实时时延（HTTP RTT）。
 *
 * 用 /api/health 的空载请求计时：响应很小，耗时基本等于一次网络往返。
 * 只在 enabled 为真时轮询，页面隐藏时跳过，避免后台标签持续发请求。
 */
export function useLatency(enabled, { intervalMs = INTERVAL_MS } = {}) {
  const latency = ref(null)
  let timer = null
  let inflight = false
  let alive = true

  async function probe() {
    if (!alive || !enabled.value || inflight) return
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
    inflight = true
    const startedAt = performance.now()
    try {
      const ok = await probeHealth(TIMEOUT_MS)
      latency.value = ok ? Math.max(1, Math.round(performance.now() - startedAt)) : null
    } catch {
      latency.value = null
    } finally {
      inflight = false
    }
  }

  function stop() {
    if (timer) clearInterval(timer)
    timer = null
  }

  function start() {
    stop()
    if (!enabled.value) return
    probe()
    timer = setInterval(probe, intervalMs)
  }

  watch(enabled, (on) => {
    if (on) start()
    else {
      stop()
      latency.value = null
    }
  }, { immediate: true })

  onBeforeUnmount(() => {
    alive = false
    stop()
  })

  return { latency }
}