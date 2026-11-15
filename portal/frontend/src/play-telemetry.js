/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 游玩页遥测样本。纯函数，便于单测。
 * 字段与后端 PlayTelemetryService 白名单保持一致。
 */
export function buildSample(input) {
  const i = input || {}
  const num = (v) => {
    const n = Number(v)
    return Number.isFinite(n) ? Math.round(n) : null
  }
  const mb = (v) => {
    const n = Number(v)
    return Number.isFinite(n) && n > 0 ? Math.round(n / 1048576 * 10) / 10 : null
  }
  return {
    event: String(i.event || 'sample').slice(0, 40),
    ts: Date.now(),
    gameId: String(i.gameId || ''),
    heapMB: mb(i.heapBytes),
    iframeHeapMB: mb(i.iframeHeapBytes),
    decoded: num(i.decoded),
    dropped: num(i.dropped),
    videoW: num(i.videoW),
    videoH: num(i.videoH),
    videoTime: Number.isFinite(Number(i.videoTime))
      ? Math.round(Number(i.videoTime) * 10) / 10 : null,
    readyState: num(i.readyState),
    visibility: String(i.visibility || '').slice(0, 20),
    viewport: String(i.viewport || '').slice(0, 40),
    canvas: String(i.canvas || '').slice(0, 40),
    window: String(i.window || '').slice(0, 40),
    ua: String(i.ua || '').slice(0, 200),
    message: String(i.message || '').slice(0, 300),
  }
}

/** 优先 sendBeacon：页面崩溃/关闭时 fetch 可能来不及发出 */
export function postTelemetry(payload) {
  if (typeof navigator === 'undefined' || typeof location === 'undefined') return false
  const url = '/api/play-telemetry'
  const body = JSON.stringify(payload)
  try {
    if (typeof navigator.sendBeacon === 'function') {
      return navigator.sendBeacon(url, new Blob([body], { type: 'application/json' }))
    }
  } catch {
    // 回退到 fetch
  }
  try {
    fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
      keepalive: true,
    }).catch(() => {})
    return true
  } catch {
    return false
  }
}
