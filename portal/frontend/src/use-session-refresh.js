/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { onBeforeUnmount, onMounted } from 'vue'
import { api } from './api'

/**
 * 定时监听会话状态，用于首页 / 详情页刷新游戏当前状态。
 *
 * idsOf() 返回要监听的游戏 id；apply(id, state, status) 收到最新状态。
 * 页面隐藏时跳过，回到前台立即补一次。
 */
export function useSessionRefresh(idsOf, apply, { intervalMs = 5000 } = {}) {
  let timer = null
  let inflight = false

  async function tick() {
    if (inflight) return
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
    const ids = [...new Set((idsOf() || []).filter(Boolean))]
    if (!ids.length) return
    inflight = true
    try {
      // 一次批量请求：首页 10 个游戏原本是每 5 秒 10 个 /api/games/{id}/session
      const all = await api.sessions(ids)
      for (const id of ids) {
        const st = all && all[id]
        if (!st) continue
        apply(id, st.crashed ? 'FAILED' : st.state, st)
      }
    } catch {
      // 单次失败忽略，下一轮再试
    } finally {
      inflight = false
    }
  }

  function onVisibility() {
    if (document.visibilityState === 'visible') tick()
  }

  onMounted(() => {
    tick()
    timer = setInterval(tick, intervalMs)
    document.addEventListener('visibilitychange', onVisibility)
  })

  onBeforeUnmount(() => {
    if (timer) clearInterval(timer)
    timer = null
    document.removeEventListener('visibilitychange', onVisibility)
  })

  return { refresh: tick }
}