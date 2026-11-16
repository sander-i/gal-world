/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { reactive } from 'vue'
import { api } from './api'
import { stopPercent } from './launch-progress'

/**
 * 全局「正在关闭游戏」状态。
 *
 * 关闭游戏不再是瞬间操作：为了让它有机会存盘，服务端先请求游戏自身退出，
 * 实测要 10~15s 才真正结束。这段时间用户已经离开游玩页，所以进度必须放在
 * 模块级（跨路由存活），由 App.vue 里的横幅统一展示。
 *
 * 同时只会有关一个游戏的操作，因此只保留一份状态。
 */
const POLL_MS = 400
/** 与 use-game-launch 的 STOP_MS 保持一致 */
const STOP_MS = 15_000
/** 超过这个时间还没停，就不再等（服务端自己也有强杀兜底） */
const STOP_TIMEOUT_MS = 30_000
/** 「已关闭」停留时长 */
const DONE_HOLD_MS = 2500

export const closing = reactive({
  id: '',
  name: '',
  percent: 0,
  active: false,
  done: false,
})

let timer = null
let startedAt = 0
let doneTimer = null

/** 开始跟踪关闭进度；调用方负责随后发起 stopGame 请求 */
export function beginClose(id, name) {
  clearInterval(timer)
  clearTimeout(doneTimer)
  Object.assign(closing, {
    id,
    name: name || id,
    percent: 5,
    active: true,
    done: false,
  })
  startedAt = performance.now()
  timer = setInterval(poll, POLL_MS)
}

async function poll() {
  if (!closing.active) {
    clearInterval(timer)
    timer = null
    return
  }
  const elapsed = performance.now() - startedAt
  closing.percent = Math.max(closing.percent, stopPercent(elapsed, STOP_MS))

  let st
  try {
    st = await api.session(closing.id)
  } catch {
    // 网络抖动不算结束，下一轮再看
    return
  }
  // 游戏窗口没了就是没有画面可传，等价于已经彻底关闭；
  // 比等 systemd 单元收尾更贴近真实状态（收尾还要关 Selkies、卸音频模块）
  if (st?.gameAlive === false) {
    finish()
    return
  }
  // UNKNOWN 是 systemd 停止过程中的瞬时值，继续等
  if (st?.state !== 'RUNNING' && st?.state !== 'UNKNOWN') {
    finish()
    return
  }
  if (elapsed > STOP_TIMEOUT_MS) finish()
}

function finish() {
  clearInterval(timer)
  timer = null
  closing.active = false
  closing.done = true
  closing.percent = 100
  clearTimeout(doneTimer)
  doneTimer = setTimeout(() => {
    if (!closing.active) closing.done = false
  }, DONE_HOLD_MS)
}
