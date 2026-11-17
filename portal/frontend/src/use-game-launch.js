/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { t } from './i18n.js'
import { onBeforeUnmount, reactive, ref } from 'vue'
import { api } from './api'
import { LAUNCH_LABELS, launchPercent, startVerdict, stopPercent } from './launch-progress'

const POLL_MS = 400
const START_MS = 15_000
const STREAM_MS = 20_000
/**
 * 关闭期望时长。
 *
 * 关闭走「先请求游戏自身退出、等它存盘」，实测 KiriKiri 类游戏要 10~15s
 * 才真正结束（不会强杀）。进度条只到 95% 就停在原地等，所以这里按真实时长给。
 */
const STOP_MS = 15_000
const START_TIMEOUT_MS = 90_000
const STOP_TIMEOUT_MS = 30_000
const DONE_HOLD_MS = 700

/**
 * 启动后多久内出现的 STOPPED 不算「进程退出」。
 *
 * systemctl start 返回后单元可能还没进入 active，is-active 会短暂给出
 * inactive/activating；启动头几秒内的 STOPPED 按「还在起」处理，避免误报。
 */
const STOPPED_GRACE_MS = 5_000

/**
 * 会话操作控制器：启动 / 关闭，并跟踪真实进度。
 *
 * 启动接口返回的 RUNNING 只代表进程起来，串流端口可能还没就绪；
 * 关闭接口返回后，systemd 也可能短暂报 RUNNING/UNKNOWN。所以进度
 * 必须由 /session 轮询确认，确认后才回调 onStarted / onStopped。
 *
 * 一个实例可同时跟踪多款游戏（按 id 记录），首页卡片据此各自显示进度。
 */
export function useGameLaunch({ idOf = (g) => g.id ?? g.gameId, onError, onStarted, onStopped } = {}) {
  const busyId = ref('')
  const launches = reactive({})
  const polls = new Map()
  const settles = new Map()

  function clearLaunch(id) {
    const poll = polls.get(id)
    if (poll) clearInterval(poll)
    polls.delete(id)
    const settle = settles.get(id)
    if (settle) clearTimeout(settle)
    settles.delete(id)
    delete launches[id]
  }

  function clearAll() {
    for (const id of new Set([...polls.keys(), ...settles.keys(), ...Object.keys(launches)])) {
      clearLaunch(id)
    }
    busyId.value = ''
  }

  /** 进度只进不退，避免轮询抖动时进度条回跳 */
  function setLaunch(id, percent, step, action) {
    const prev = launches[id]?.percent ?? 0
    launches[id] = {
      percent: Math.max(prev, percent),
      label: LAUNCH_LABELS[step] || '',
      step,
      action,
    }
  }

  function finish(id, action, resolve) {
    const poll = polls.get(id)
    if (poll) clearInterval(poll)
    polls.delete(id)
    setLaunch(id, 100, action === 'stop' ? 'closed' : 'done', action)
    if (action === 'stop') onStopped?.(id)
    else onStarted?.(id)
    settles.set(id, setTimeout(() => clearLaunch(id), DONE_HOLD_MS))
    resolve?.(true)
  }

  function trackStart(id) {
    return new Promise((resolve) => {
      const startedAt = performance.now()
      let step = 'start'
      let stepStartedAt = startedAt
      let inflight = false
      let done = false

      const fail = (msg) => {
        if (done) return
        done = true
        clearLaunch(id)
        onError?.(msg)
        resolve(false)
      }

      const poll = setInterval(async () => {
        if (done || inflight || !polls.has(id)) return
        if (performance.now() - startedAt > START_TIMEOUT_MS) {
          fail(t('启动超时：90 秒内未就绪，请到详情页查看状态。'))
          return
        }
        inflight = true
        let st
        try {
          st = await api.session(id)
        } catch {
          inflight = false
          return // 单次轮询失败不终止，下一轮再试
        }
        inflight = false
        if (done || !polls.has(id)) return
        if (st.state === 'FAILED' || st.crashed) {
          fail(t('游戏启动失败，请到详情页查看日志。'))
          return
        }
        // 会话在启动过程中自己退出了（游戏崩溃、缺文件秒退、被外部停掉）：
        // 后端只会报 STOPPED，若不在这里收尾，进度条会停在 stream 步上限 95%
        // 直到 90 秒超时，用户看到的就是「卡住不动」。
        // 启动头几秒的 STOPPED 可能是 systemd 的瞬时值，过了宽限期才判定。
        if (startVerdict(st, performance.now() - startedAt, STOPPED_GRACE_MS) === 'fail') {
          fail(t('游戏进程已退出，请到详情页查看日志。'))
          return
        }
        if (step === 'start' && st.state === 'RUNNING') {
          step = 'stream'
          stepStartedAt = performance.now()
        }
        const expected = step === 'start' ? START_MS : STREAM_MS
        setLaunch(id, launchPercent(step, performance.now() - stepStartedAt, expected), step, 'start')
        // streamReady 在 Windows 上恒为 true；Linux 等端口探测就绪
        if (step === 'stream' && st.streamReady) {
          done = true
          finish(id, 'start', resolve)
        }
      }, POLL_MS)

      polls.set(id, poll)
      setLaunch(id, launchPercent('start', 0, START_MS), 'start', 'start')
    })
  }

  function trackStop(id) {
    return new Promise((resolve) => {
      const startedAt = performance.now()
      let inflight = false
      let done = false

      const fail = (msg) => {
        if (done) return
        done = true
        clearLaunch(id)
        onError?.(msg)
        resolve(false)
      }

      const poll = setInterval(async () => {
        if (done || inflight || !polls.has(id)) return
        if (performance.now() - startedAt > STOP_TIMEOUT_MS) {
          fail(t('关闭超时：请刷新页面确认会话状态。'))
          return
        }
        inflight = true
        let st
        try {
          st = await api.session(id)
        } catch {
          inflight = false
          return
        }
        inflight = false
        if (done || !polls.has(id)) return
        setLaunch(id, stopPercent(performance.now() - startedAt, STOP_MS), 'stop', 'stop')
        // 游戏窗口没了 = 没有画面可传，等价于已经关闭；
        // 比等 systemd 单元收尾更贴近真实状态。
        // UNKNOWN 是 systemd 停止过程中的瞬时值，继续等。
        if (st.gameAlive === false
            || (st.state !== 'RUNNING' && st.state !== 'UNKNOWN')) {
          done = true
          finish(id, 'stop', resolve)
        }
      }, POLL_MS)

      polls.set(id, poll)
      setLaunch(id, stopPercent(0, STOP_MS), 'stop', 'stop')
    })
  }

  /**
   * 启动或关闭。返回接口响应；启动等到串流就绪、关闭等到会话真正停止。
   * 失败返回 null，错误已通过 onError 报告。
   */
  async function toggle(game) {
    const id = idOf(game)
    if (busyId.value || launches[id]) return null
    busyId.value = id
    const release = () => { if (busyId.value === id) busyId.value = '' }

    if (game.state === 'RUNNING') {
      setLaunch(id, stopPercent(0, STOP_MS), 'stop', 'stop')
      let updated
      try {
        updated = await api.stopGame(id)
      } catch (e) {
        clearLaunch(id)
        release()
        onError?.(t("操作失败：{0}", e.message))
        return null
      }
      release()
      const ok = await trackStop(id)
      return ok ? updated : null
    }

    setLaunch(id, launchPercent('start', 0, START_MS), 'start', 'start')
    let started
    try {
      started = await api.startGame(id)
    } catch (e) {
      clearLaunch(id)
      release()
      onError?.(t("操作失败：{0}", e.message))
      return null
    }
    release()
    const ok = await trackStart(id)
    return ok ? started : null
  }

  /** 重新启动：先关闭残留会话，再重新启动，进度条连续显示 */
  async function restart(game) {
    const id = idOf(game)
    if (game.state === 'RUNNING') {
      await toggle({ ...game, state: 'RUNNING' })
      clearLaunch(id)
    }
    return toggle({ ...game, state: 'STOPPED' })
  }

  onBeforeUnmount(clearAll)

  return { busyId, launches, toggle, restart, clearLaunch }
}