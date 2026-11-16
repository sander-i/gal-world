<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, inMaintenanceWindow, isMaintenanceError } from '../api'
import { formatPlaytime } from '../format'
import { PHASES, PHASE_LABELS, bootPercent, nextPercent, bootDecision,
  interruptionFor, isCleanExit } from '../play-boot'
import { cropPlan } from '../play-crop'
import { playBackTarget } from '../play-origin'
import { canvasAutoCalibrate } from '../feature-flags'
import { buildSample, postTelemetry } from '../play-telemetry'
import { notify } from '../feedback'
import { beginClose } from '../closing'
import { useLatency } from '../use-latency'
import { latencyTone } from '../latency'

const props = defineProps({ id: { type: String, required: true } })
const router = useRouter()
const route = useRoute()

/** 左上角返回：从哪来回哪去；直接访问地址时回首页 */
const back = computed(() => playBackTarget(route))

function backToSource() {
  router.push({ path: back.value.path, query: back.value.query })
}

/**
 * 页面形态：
 * idle | booting | playing | not-started | stopped | crashed | error
 *
 * 用单一 view 而不是多个布尔量：启动、崩溃、停止是互斥状态，
 * 多布尔量很容易拼出「既在加载又显示崩溃」这种矛盾画面。
 */
const view = ref('idle')
const game = ref(null)
const phase = ref('prepare')
const percent = ref(0)
const showFrame = ref(false)
const frame = ref(null)
const playedSeconds = ref(0)
const busy = ref(false)
const sessionState = ref('UNKNOWN')
const diagnostics = ref(null)
const diagLoading = ref(false)
const cardMessage = ref('')
const showBar = ref(true)
const stage = ref(null)
const viewport = ref(null)
const winRect = ref(null)
const isFullscreen = ref(false)
const isImmersive = ref(false)
const calibrating = ref(false)
const autoCalibrating = ref(false)
let autoCalibrateRunning = false
let autoCalibrateAllowed = false
let autoCalibrateTimer = null
let pageGone = false
let telemetryTimer = null
const TELEMETRY_MS = 10_000

const fullscreenActive = computed(() => isFullscreen.value || isImmersive.value)
const playing = computed(() => view.value === 'playing')

/** 顶部栏正中的时延：只在真正进入画面后采集 */
const { latency } = useLatency(playing)

const HEARTBEAT_MS = 20_000
const POLL_MS = 3_000
const BOOT_TIMEOUT_MS = 120_000

let heartbeatTimer = null
let pollTimer = null
let bootTimer = null
let bootToken = 0
let phaseStartedAt = 0
let phaseExpectedMs = 0
let bootDeadline = 0

/**
 * 串流地址。
 *
 * Linux（Selkies）：走门户自己的反向代理 /stream/<id>/。
 * Windows（Moonlight Web）：后端给 streamUrl，所有会话共用同一个地址；
 * 后端返回 localhost/127.0.0.1 时换成当前页面主机名。
 */
const streamUrl = computed(() => {
  if (!game.value) return ''
  const given = game.value.streamUrl
  if (given) return rewriteLoopback(given)
  return `/stream/${encodeURIComponent(props.id)}/`
})

function rewriteLoopback(url) {
  try {
    const u = new URL(url)
    if (u.hostname === 'localhost' || u.hostname === '127.0.0.1' || u.hostname === '::1') {
      u.hostname = location.hostname
      return u.toString()
    }
    return url
  } catch {
    return url
  }
}

const stateText = computed(() => ({
  booting: '启动中',
  playing: '运行中',
  'not-started': '未启动',
  stopped: '已停止',
  crashed: '异常终止',
  error: '连接失败',
}[view.value] || '—'))

const stateTone = computed(() => ({
  booting: 'booting',
  playing: 'running',
  crashed: 'error',
  error: 'error',
}[view.value] || ''))

const CARD = {
  'not-started': {
    tone: 'muted',
    icon: '⏸',
    title: '游戏未启动',
    desc: '当前会话没有在运行。启动后即可进入游戏，或先回详情页查看状态。',
    primary: '立即启动',
  },
  stopped: {
    tone: 'muted',
    icon: '⏹',
    title: '游戏已停止',
    desc: '会话已结束，可能是空闲超时自动停止，或已在其它页面停止。',
    primary: '重新启动',
  },
  crashed: {
    tone: 'error',
    icon: '!',
    title: '游戏异常终止',
    desc: '游戏自己退出或崩溃了。未存档的游戏进度可能丢失，存档可在详情页恢复。',
    primary: '重新启动',
  },
  error: {
    tone: 'error',
    icon: '!',
    title: '无法进入游戏',
    desc: '',
    primary: '重试',
  },
}

/** 诊断显示退出码 0 且无信号：是正常退出，不是红色崩溃 */
const CLEAN_EXIT_CARD = {
  tone: 'muted',
  icon: '⏹',
  title: '游戏已退出',
  desc: '游戏已正常退出，未检测到崩溃。存档可在详情页管理。',
  primary: '重新启动',
}

const card = computed(() => {
  if (view.value === 'crashed' && isCleanExit(diagnostics.value)) return CLEAN_EXIT_CARD
  return CARD[view.value] || null
})
const cardDesc = computed(() => {
  const base = card.value?.desc || ''
  return [base, cardMessage.value].filter(Boolean).join(' ')
})

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function beat() {
  try {
    const r = await api.heartbeat(props.id)
    playedSeconds.value = r.totalSeconds
  } catch {
    // 心跳失败不打断游玩，下一轮再试
  }
}

async function leave() {
  try {
    await api.leave(props.id)
  } catch {
    // 后端会用心跳超时兜底
  }
}

/** 只在真正在玩/正在启动时才算游玩时长 */
function canHeartbeat() {
  return view.value === 'playing' || view.value === 'booting'
}

function stopHeartbeat() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
}

function updatePresence() {
  const active = document.visibilityState === 'visible' && canHeartbeat()
  if (active) {
    if (!heartbeatTimer) {
      beat()
      heartbeatTimer = setInterval(beat, HEARTBEAT_MS)
    }
  } else {
    const had = !!heartbeatTimer
    stopHeartbeat()
    if (had) leave()
  }
}

/**
 * iframe 内的光标兜底。
 *
 * 正常路径：Selkies 以 controller 角色连上游，inputAttached=true，
 * 它自己的 cursorDiv 会渲染远端光标（能跟随游戏内光标形状）。
 *
 * 这里只在「远端光标数据缺失」时兜底：那种情况下 Selkies 会写内联
 *   element.style.setProperty('cursor', 'none', 'important')
 * 内联 !important 优先级高于样式表 !important，只能靠 JS 清掉。
 */
let cursorObserver = null

function fixCursor() {
  const doc = frame.value?.contentDocument
  if (!doc) return

  const styleId = 'galworld-cursor-fix'
  if (!doc.getElementById(styleId)) {
    const s = doc.createElement('style')
    s.id = styleId
    s.textContent = 'html, body, canvas, video { cursor: default !important; }'
    doc.head.appendChild(s)
  }

  const clearInline = () => {
    for (const el of doc.querySelectorAll('[style*="cursor"]')) {
      if (el.style.cursor === 'none') el.style.removeProperty('cursor')
    }
  }
  clearInline()

  cursorObserver?.disconnect()
  let fixing = false
  cursorObserver = new MutationObserver((records) => {
    if (fixing) return
    fixing = true
    try {
      for (const r of records) {
        if (r.type === 'attributes' && r.attributeName === 'style') {
          const el = r.target
          if (el.style && el.style.cursor === 'none') el.style.removeProperty('cursor')
        }
      }
    } finally {
      fixing = false
    }
  })
  cursorObserver.observe(doc.documentElement, {
    attributes: true,
    attributeFilter: ['style'],
    subtree: true,
  })
}

// ---------------- 启动进度 ----------------

function clearBootTimers() {
  if (bootTimer) {
    clearInterval(bootTimer)
    bootTimer = null
  }
}

function setPhase(p, expectedMs) {
  phase.value = p
  phaseStartedAt = performance.now()
  phaseExpectedMs = expectedMs
  percent.value = nextPercent(p, percent.value)
}

function tickBoot() {
  if (view.value !== 'booting') return
  percent.value = Math.max(percent.value,
    bootPercent(phase.value, performance.now() - phaseStartedAt, phaseExpectedMs))
  if (performance.now() > bootDeadline) {
    failBoot('连接超时：串流服务在 2 分钟内没有就绪。')
  }
}

async function startBoot(startedByUser = false) {
  autoCalibrateAllowed = startedByUser
  const token = ++bootToken
  clearBootTimers()
  stopPolling()
  stopHeartbeat()
  diagnostics.value = null
  cardMessage.value = ''
  showFrame.value = false
  percent.value = 0
  phase.value = 'prepare'
  view.value = 'booting'
  bootDeadline = performance.now() + BOOT_TIMEOUT_MS
  bootTimer = setInterval(tickBoot, 200)
  setPhase('prepare', 2500)

  let st
  try {
    st = await api.session(props.id)
  } catch (e) {
    // 平台更新/重启期间后端不可用：交给维护遮罩，不要当作启动失败
    if (isMaintenanceError(e)) return
    if (token === bootToken) failBoot('读取会话状态失败：' + e.message)
    return
  }
  if (token !== bootToken || view.value !== 'booting') return

  sessionState.value = st.state
  // 非 RUNNING 一律不自动启动：未启动 → not-started，异常 → crashed。
  // 显式启动后的 UNKNOWN 是 systemd 瞬时值，继续等待，不能弹回未启动。
  const decision = bootDecision(st, startedByUser)
  if ((decision === 'crashed' || decision === 'halt') && inMaintenanceWindow()) {
    // 平台刚重启完，状态可能是瞬时的；继续按启动中等待
    setPhase('start', 4000)
  } else if (decision === 'crashed' || decision === 'halt') {
    await haltNotRunning(token, st)
    return
  }

  beat()
  updatePresence()
  setPhase('start', 4000)

  while (token === bootToken && view.value === 'booting') {
    try {
      st = await api.session(props.id)
    } catch {
      await sleep(1000)
      continue
    }
    if (token !== bootToken || view.value !== 'booting') return
    sessionState.value = st.state
    const decision = bootDecision(st, startedByUser)
    // 刚经历平台重启时，systemd 可能瞬时给出 FAILED；先等维护窗口过去再判定
    if (decision === 'crashed' && inMaintenanceWindow()) {
      await sleep(1000)
      continue
    }
    if (decision === 'crashed') {
      await haltNotRunning(token, st, '游戏在启动过程中异常终止。')
      return
    }
    if (decision === 'halt') {
      await haltNotRunning(token, st)
      return
    }
    if (decision === 'running' && st.streamReady) break
    if (phase.value === 'start' && performance.now() - phaseStartedAt > 4000) {
      setPhase('stream', 8000)
    }
    await sleep(1000)
  }
  if (token !== bootToken || view.value !== 'booting') return

  setPhase('player', 12_000)
  showFrame.value = true
  nextTick(fitStage)
}

function onFrameLoad() {
  fitStage()
  fixCursor()                       // 每次 iframe 载入都必须兜底
  // iframe 内部脚本错误（Selkies 客户端崩溃/解码异常）也上报
  try {
    const w = frame.value?.contentWindow
    w?.addEventListener('error', onPageError)
    w?.addEventListener('unhandledrejection', onPageError)
  } catch {
    // 跨域或已卸载：忽略
  }
  if (view.value !== 'booting') return
  setPhase('frame', 10_000)
  waitForFirstFrame()
}

function waitForFirstFrame() {
  const doc = frame.value?.contentDocument
  if (!doc) { finishBoot(); return }   // 跨域无法探测，加载完成即就绪
  const token = bootToken
  const deadline = performance.now() + 12_000
  const check = () => {
    if (token !== bootToken || view.value !== 'booting') return
    const canvas = doc.querySelector('canvas')
    const video = doc.querySelector('video')
    const ready = (canvas && canvas.width > 0 && canvas.height > 0)
      || (video && video.videoWidth > 0)
    // 有些编码路径没有任何可探测的媒体元素；超时后按就绪收尾，
    // 否则进度条会永远停在 99%，用户以为卡死。
    if (ready || performance.now() > deadline) {
      finishBoot()
      return
    }
    setTimeout(check, 250)
  }
  check()
}

function finishBoot() {
  clearBootTimers()
  percent.value = 100
  sessionState.value = 'RUNNING'
  view.value = 'playing'
  startPolling()
  updatePresence()
  refreshWindowRect()
  startTelemetry()
  // 只在“本次由用户启动”的会话上自动校准：已经在跑的游戏重启会丢失进度
  if (autoCalibrateAllowed) {
    clearTimeout(autoCalibrateTimer)
    // 等窗口居中稳定后再测量；校准会重启会话，由 startBoot 重新接管画面
    autoCalibrateTimer = setTimeout(maybeAutoCalibrate, 1500)
  }
}

function failBoot(message) {
  bootToken++
  clearBootTimers()
  showFrame.value = false
  stopHeartbeat()
  leave()
  sendTelemetry('boot_failed', message)
  cardMessage.value = message
  view.value = 'error'
}

/** 启动阶段发现会话不在运行：崩溃 / 未启动分开呈现 */
async function haltNotRunning(token, st, message = '') {
  if (token !== bootToken) return
  clearBootTimers()
  showFrame.value = false
  stopHeartbeat()
  leave()
  if (st.crashed || st.state === 'FAILED') {
    cardMessage.value = message
    view.value = 'crashed'
    await loadDiagnostics()
  } else {
    view.value = 'not-started'
  }
}

// ---------------- 会话轮询 ----------------

function startPolling() {
  stopPolling()
  pollTimer = setInterval(pollSession, POLL_MS)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function pollSession() {
  if (view.value !== 'playing') return
  if (document.visibilityState !== 'visible') return
  let st
  try {
    st = await api.session(props.id)
  } catch {
    return
  }
  if (view.value !== 'playing') return
  sessionState.value = st.state
  const interruption = interruptionFor(st)
  // 平台重启刚恢复时，systemd 可能瞬时返回 FAILED；此时不能判为游戏崩溃
  if (interruption === 'crashed' && inMaintenanceWindow()) return
  if (interruption === 'crashed') {
    stopPolling()
    stopHeartbeat()
    showFrame.value = false
    cursorObserver?.disconnect()
    cardMessage.value = ''
    view.value = 'crashed'
    await loadDiagnostics()
    leave()
    return
  }
  if (interruption === 'stopped') {
    stopPolling()
    stopHeartbeat()
    showFrame.value = false
    cursorObserver?.disconnect()
    view.value = 'stopped'
    leave()
  }
}

async function loadDiagnostics() {
  diagLoading.value = true
  try {
    diagnostics.value = await api.sessionDiagnostics(props.id)
  } catch (e) {
    diagnostics.value = { summary: '读取会话日志失败：' + e.message, logTail: [] }
  } finally {
    diagLoading.value = false
  }
}

// ---------------- 页面动作 ----------------

async function load() {
  clearAll()
  try {
    game.value = await api.getGame(props.id)
  } catch (e) {
    cardMessage.value = '读取游戏信息失败：' + e.message
    view.value = 'error'
    return
  }
  await startBoot()
}

async function launch() {
  busy.value = true
  try {
    await api.startGame(props.id)
  } catch (e) {
    busy.value = false
    cardMessage.value = '启动失败：' + e.message
    view.value = 'error'
    return
  }
  busy.value = false
  await startBoot(true)
}

async function restart() {
  busy.value = true
  try {
    try {
      await api.stopGame(props.id)
    } catch {
      // 已经停了
    }
    await api.startGame(props.id)
  } catch (e) {
    busy.value = false
    cardMessage.value = '重新启动失败：' + e.message
    view.value = 'error'
    return
  }
  busy.value = false
  await startBoot(true)
}

/** 校准返回 restarted 时只重建 UI，不必再 stop/start 一次 */
async function rebootStream() {
  showFrame.value = false
  try {
    game.value = await api.getGame(props.id)
  } catch {
    // 读不到就用旧数据，startBoot 仍会按会话状态继续
  }
  await startBoot(autoCalibrateAllowed)
}

/** 自动校准：受全局开关控制，失败时静默，避免每次进游戏弹提示 */
async function maybeAutoCalibrate() {
  if (!canvasAutoCalibrate.value) return
  if (!autoCalibrateAllowed || autoCalibrateRunning || calibrating.value) return
  if (view.value !== 'playing' || pageGone) return
  autoCalibrateRunning = true
  autoCalibrating.value = true
  try {
    const r = await api.calibrateCanvas(props.id, false)
    if (pageGone) return
    if (r.status === 'restarted') {
      notify.info(`正在按实际窗口校准画布：${r.canvasWidth}×${r.canvasHeight}`)
      await rebootStream()
    } else if (r.status === 'failed') {
      notify.warning(r.message)
    }
  } catch {
    // 自动校准失败不打扰用户；需要时点右上角按钮重试
  } finally {
    autoCalibrateRunning = false
    autoCalibrating.value = false
  }
}

/** 手动校准：即使全局是「手动模式」也执行，并给出明确结果 */
async function calibrateManual() {
  if (calibrating.value || autoCalibrating.value || !playing.value) return
  calibrating.value = true
  try {
    const r = await api.calibrateCanvas(props.id, true)
    if (r.status === 'restarted') {
      notify.success(`${r.message}，正在重新进入游戏`)
      await rebootStream()
    } else if (r.status === 'ok') {
      notify.success(r.message)
    } else {
      notify.warning(r.message)
    }
  } catch (e) {
    notify.error('校准失败：' + e.message)
  } finally {
    calibrating.value = false
  }
}

/** 采集一帧遥测；iframe 未就绪时字段留空，不阻塞发送 */
function telemetryPayload(event, message = '') {
  const w = frame.value?.contentWindow
  const d = w?.document
  const v = d?.querySelector('video')
  return buildSample({
    event,
    gameId: props.id,
    message,
    heapBytes: performance.memory?.usedJSHeapSize,
    iframeHeapBytes: w?.performance?.memory?.usedJSHeapSize,
    decoded: v?.webkitDecodedFrameCount,
    dropped: v?.webkitDroppedFrameCount,
    videoW: v?.videoWidth,
    videoH: v?.videoHeight,
    videoTime: v?.currentTime,
    readyState: v?.readyState,
    visibility: document.visibilityState,
    viewport: `${window.innerWidth}x${window.innerHeight}`,
    canvas: `${game.value?.width || ''}x${game.value?.height || ''}`,
    window: winRect.value ? `${winRect.value.width}x${winRect.value.height}` : '',
    ua: navigator.userAgent,
  })
}

function sendTelemetry(event, message = '') {
  postTelemetry(telemetryPayload(event, message))
}

function startTelemetry() {
  stopTelemetry()
  sendTelemetry('playing_start')
  telemetryTimer = setInterval(() => sendTelemetry('sample'), TELEMETRY_MS)
}

function stopTelemetry() {
  if (telemetryTimer) clearInterval(telemetryTimer)
  telemetryTimer = null
}

/** 页面脚本错误：立即上报，带堆内存与解码状态 */
function onPageError(e) {
  const msg = e?.message || e?.reason?.message || String(e?.reason || e)
  sendTelemetry('pageerror', msg)
}

function primaryAction() {
  if (busy.value) return
  if (view.value === 'not-started') return launch()
  if (view.value === 'stopped' || view.value === 'crashed') return restart()
  if (view.value === 'error') return load()
}

function cancelBoot() {
  bootToken++
  clearBootTimers()
  stopPolling()
  stopHeartbeat()
  showFrame.value = false
  leave()
  router.push(`/game/${props.id}`)
}

/**
 * 关闭游戏。
 *
 * 关闭要等游戏自己存盘退出（10~15s），不能把用户锁在游玩页干等：
 * 先发起停止请求，然后立刻回到来源页面，进度由全局横幅展示
 * （见 closing.js / ClosingBanner），和「启动游戏」的体验一致。
 */
async function closeGame() {
  if (!canHeartbeat()) return
  beginClose(props.id, game.value?.name)
  // 不等待：请求照常走完，服务端会等游戏自己退出后再收拾串流
  api.stopGame(props.id).catch((e) => notify.error('关闭失败：' + e.message))
  stopPolling()
  stopHeartbeat()
  leave()
  backToSource()
}

function stepDone(p) {
  return PHASES.indexOf(p) < PHASES.indexOf(phase.value)
}

// ---------------- 全屏 ----------------

/**
 * 全屏的是 stage，不是整个 play-wrap：
 * 浏览器只渲染全屏元素子树，顶栏作为兄弟节点自然不在画面内。
 * 进度覆盖层与错误卡片在 stage 内，全屏时照常可见。
 */
async function toggleFullscreen() {
  if (fullscreenActive.value) return exitFullscreen()
  return enterFullscreen()
}

async function enterFullscreen() {
  const el = stage.value
  if (!el) return
  try {
    if (el.requestFullscreen) {
      await el.requestFullscreen({ navigationUI: 'hide' })
    } else if (el.webkitRequestFullscreen) {
      el.webkitRequestFullscreen()
    } else {
      enterImmersive()
    }
  } catch {
    // 用户拒绝或平台不允许（iOS Safari 非 video 元素）：退回沉浸模式，
    // 不让按钮「点了没反应」
    enterImmersive()
  }
  lockLandscape()
}

async function exitFullscreen() {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen()
    } else if (document.webkitFullscreenElement) {
      document.webkitExitFullscreen()
    }
  } catch {
    // 可能已被 Esc 退出，忽略
  }
  exitImmersive()
  unlockOrientation()
}

function enterImmersive() { isImmersive.value = true }
function exitImmersive() { isImmersive.value = false }

function onFullscreenChange() {
  const el = document.fullscreenElement || document.webkitFullscreenElement
  isFullscreen.value = !!el && el === stage.value
  if (isFullscreen.value) isImmersive.value = false
  nextTick(fitStage)
}

/** 移动端全屏后尽量锁横屏；不支持（桌面/iOS）静默忽略 */
function lockLandscape() {
  try {
    screen.orientation?.lock?.('landscape')?.catch(() => {})
  } catch {
    // 不支持则忽略
  }
}

function unlockOrientation() {
  try {
    screen.orientation?.unlock?.()
  } catch {
    // 忽略
  }
}

function onKeydown(e) {
  if (e.altKey && e.key === 'Enter') {
    e.preventDefault()
    toggleFullscreen()
    return
  }
  // Fullscreen API 下 Esc 由浏览器原生处理；沉浸模式没有原生退出，需要自己接
  if (e.key === 'Escape' && isImmersive.value) exitFullscreen()
}

// ---------------- 生命周期 ----------------

function clearAll() {
  bootToken++
  clearBootTimers()
  stopPolling()
  stopHeartbeat()
  clearTimeout(streamFitTimer)
  streamFitTimer = null
  clearTimeout(autoCalibrateTimer)
  autoCalibrateTimer = null
  stopTelemetry()
  autoCalibrateAllowed = false
  showFrame.value = false
  cursorObserver?.disconnect()
  cursorObserver = null
}

/**
 * 画面按画布比例铺满 stage。
 *
 * 曾经按「游戏主窗口」裁剪（去掉画布黑边），但前端裁剪与 Selkies 的指针
 * 换算冲突，两条路都会让悬停/点击落点偏移：
 *   - 改 iframe 布局尺寸：Selkies 在 attach 时记下几何，之后不刷新，
 *     实测指针整体偏 +140 画布像素；
 *   - 用 transform 缩放：浏览器给 iframe 内部的事件坐标不除以缩放比，
 *     指针被放大 1.48 倍，越往右下偏得越多（实测注入点被钳到屏幕右下角）。
 * 所以这里保持铺满、不裁剪。要恢复裁剪只能改服务端（GStreamer 侧裁剪，
 * 或让 Xvfb 屏幕等于游戏窗口）。
 */
let stageObserver = null
let streamFitTimer = null

/** 读一次窗口矩形；串流刚就绪时窗口可能还在居中，1.5s 后再兜底取一次 */
async function refreshWindowRect() {
  winRect.value = await api.gameWindow(props.id)
  nextTick(fitStage)
  setTimeout(async () => {
    if (view.value !== 'playing') return
    const again = await api.gameWindow(props.id)
    if (again && JSON.stringify(again) !== JSON.stringify(winRect.value)) {
      winRect.value = again
      nextTick(fitStage)
    }
  }, 1500)
}

function fitStage() {
  const el = stage.value
  const f = frame.value
  if (!el || !f) return
  const rect = el.getBoundingClientRect()
  if (!rect.width || !rect.height) return

  // iframe 必须铺满 stage，且 stage 在页面 (0,0)：Selkies 的指针换算会把
  // iframe 在页面上的位置再叠加一次，iframe 一旦离开 (0,0)，悬停和点击就会
  // 整体偏移这段距离（实测偏 236,44 css px）。
  f.style.width = `${Math.floor(rect.width)}px`
  f.style.height = `${Math.floor(rect.height)}px`
  centerStream(f)
}

/**
 * 在 iframe 内部把串流容器按画布比例居中。
 *
 * 画面居中不能靠移动 iframe（见上），只能把视频容器缩到画布比例再居中。
 * Selkies 的指针换算是按「元素矩形」算的（`getBoundingClientRect` +
 * 居中留白修正），所以容器居中后映射依旧正确。
 */
function centerStream(f) {
  const doc = f.contentDocument
  if (!doc) return
  // 顶栏是浮层（absolute，不占布局），会盖住画面顶部 44px；居中时必须把它
  // 让出来，否则画面顶部被遮、底部留黑，看上去整体偏上。
  const TOP_BAR = 44
  const box = {
    width: f.clientWidth,
    height: Math.max(1, f.clientHeight - TOP_BAR),
  }
  const canvas = {
    width: Number(game.value?.width) || 1280,
    height: Number(game.value?.height) || 720,
  }
  if (!box.width || !box.height) return
  const plan = cropPlan(box, canvas, null)
  let style = doc.getElementById('galworld-stream-fit')
  if (!style) {
    style = doc.createElement('style')
    style.id = 'galworld-stream-fit'
    doc.head.appendChild(style)
  }
  style.textContent = `
.video-container {
  position: absolute !important;
  left: ${Math.round((box.width - plan.viewW) / 2)}px !important;
  top: ${TOP_BAR + Math.round((box.height - plan.viewH) / 2)}px !important;
  width: ${plan.viewW}px !important;
  height: ${plan.viewH}px !important;
}
.video-container video, .video-container canvas {
  left: 0 !important;
  top: 0 !important;
  width: 100% !important;
  height: 100% !important;
}`
  // Selkies 的窗口几何只在 resize 时重算；容器改尺寸后要让它重新量一次
  clearTimeout(streamFitTimer)
  streamFitTimer = setTimeout(() => {
    try {
      f.contentWindow?.dispatchEvent(new Event('resize'))
    } catch {
      // 跨域或 iframe 已卸载：忽略
    }
  }, 80)
}

function onVisibility() {
  if (document.visibilityState === 'visible' && view.value === 'playing') {
    pollSession()
  } else if (document.visibilityState === 'hidden' && view.value === 'playing') {
    sendTelemetry('hidden')
  }
  updatePresence()
}

onMounted(() => {
  window.addEventListener('error', onPageError)
  window.addEventListener('unhandledrejection', onPageError)
  load()
  if (stage.value) {
    stageObserver = new ResizeObserver(fitStage)
    stageObserver.observe(stage.value)
  }
  nextTick(fitStage)
  document.addEventListener('visibilitychange', onVisibility)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  document.addEventListener('webkitfullscreenchange', onFullscreenChange)
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('pagehide', leave)
})

onBeforeUnmount(() => {
  sendTelemetry('unload')
  pageGone = true
  window.removeEventListener('error', onPageError)
  window.removeEventListener('unhandledrejection', onPageError)
  stageObserver?.disconnect()
  stageObserver = null
  document.removeEventListener('visibilitychange', onVisibility)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  document.removeEventListener('webkitfullscreenchange', onFullscreenChange)
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('pagehide', leave)
  if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {})
  if (document.webkitFullscreenElement) document.webkitExitFullscreen?.()
  unlockOrientation()
  clearAll()
  leave()
})
</script>

<template>
  <div class="play-wrap" :class="{ immersive: isImmersive }" @mousemove="showBar = true">
    <div class="play-bar" :class="{ dim: !showBar }" @mouseleave="showBar = false">
      <button class="ghost-btn" @click="backToSource">{{ back.label }}</button>
      <span class="title">{{ game?.name || id }}</span>
      <span class="play-state" :class="stateTone">{{ stateText }}</span>
      <span class="title">累计 {{ formatPlaytime(playedSeconds) }}</span>
      <span
        v-if="playing && latency != null"
        class="play-latency"
        :class="latencyTone(latency)"
      >时延 {{ latency }} ms</span>
      <div class="spacer" />
      <button
        v-if="playing"
        class="ghost-btn"
        :disabled="calibrating || autoCalibrating"
        @click="calibrateManual"
      >{{ calibrating || autoCalibrating ? '校准中…' : '校准分辨率' }}</button>
      <button
        v-if="view === 'playing' || view === 'booting'"
        class="ghost-btn danger"
        @click="closeGame"
      >关闭游戏</button>
      <button class="ghost-btn" @click="router.push(`/game/${id}`)">会话管理</button>
      <button class="ghost-btn" @click="toggleFullscreen">
        {{ fullscreenActive ? '⤢ 退出全屏' : '⛶ 全屏' }}
      </button>
    </div>

    <div ref="stage" class="play-stage">
      <div v-if="showFrame" ref="viewport" class="play-viewport">
        <iframe
          ref="frame"
          :src="streamUrl"
          class="play-frame"
          allow="autoplay; fullscreen; microphone; clipboard-read; clipboard-write"
          allowfullscreen
          @load="onFrameLoad"
        />
      </div>

      <!-- 必须放在 stage 内：全屏时 stage 之外的内容不渲染 -->
      <button
        v-if="fullscreenActive"
        class="play-fs-exit"
        title="退出全屏（Esc）"
        @click="exitFullscreen"
      >⤢ 退出全屏</button>

      <transition name="play-fade">
        <div v-if="view === 'booting'" class="play-overlay">
          <div class="play-boot-card">
            <div class="play-boot-ring" :style="{ '--p': percent + '%' }">
              <span class="play-boot-percent">{{ percent }}<em>%</em></span>
            </div>
            <div class="play-boot-stage">{{ PHASE_LABELS[phase] }}</div>
            <div class="play-boot-track">
              <span class="play-boot-fill" :style="{ width: percent + '%' }" />
            </div>
            <ol class="play-boot-steps">
              <li
                v-for="p in PHASES"
                :key="p"
                :class="{ done: stepDone(p), on: p === phase }"
              >{{ PHASE_LABELS[p] }}</li>
            </ol>
            <button class="link-btn" @click="cancelBoot">取消并返回详情</button>
          </div>
        </div>
      </transition>

      <div v-if="card" class="play-overlay">
        <div class="play-card" :class="`tone-${card.tone}`">
          <div class="play-card-icon">{{ card.icon }}</div>
          <h1>{{ card.title }}</h1>
          <p class="play-card-desc">{{ cardDesc }}</p>

          <div v-if="diagLoading" class="play-diag-loading">正在读取游戏日志…</div>

          <div v-if="diagnostics?.summary" class="play-diag">
            <div class="play-diag-line">{{ diagnostics.summary }}</div>
            <dl
              v-if="diagnostics.exitCode != null || diagnostics.signal"
              class="play-diag-facts"
            >
              <div v-if="diagnostics.exitCode != null">
                <dt>退出码</dt><dd>{{ diagnostics.exitCode }}</dd>
              </div>
              <div v-if="diagnostics.signal">
                <dt>终止信号</dt><dd>{{ diagnostics.signal }}</dd>
              </div>
            </dl>
          </div>

          <pre v-if="diagnostics?.logTail?.length" class="play-log">{{ diagnostics.logTail.join('\n') }}</pre>

          <div class="play-card-actions">
            <button class="btn primary sm" :disabled="busy" @click="primaryAction">
              {{ card.primary }}
            </button>
            <button class="btn" @click="backToSource">{{ back.label }}</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
