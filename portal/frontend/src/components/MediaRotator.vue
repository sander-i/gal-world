<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
/**
 * 多图/多 CG 轮换组件。
 *
 * 加载策略（库页列表的关键）：
 *  1. 首屏只渲染第一项（置顶），立即可见
 *  2. 其余项在空闲时逐个预加载，加载完成后才加入轮换列表
 *  3. 未加载完的项不参与轮换，避免切换时空白
 *
 * 性能保护：
 *  - 页面不可见 → 暂停轮换
 *  - 尊重 prefers-reduced-motion → 只显示首项
 *  - 视频同时只挂一个 <video>，切换时复用并换 src
 *  - 任一媒体失败 → 跳过，全失败退回占位
 */
import { t } from '../i18n.js'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { acquirePreloadSlot, releasePreloadSlot } from '../media-preload.js'
import { srcsetOf } from '../media-srcset.js'

const props = defineProps({
  /** 详情页传 true：只加载原图，不用派生图（派生图无原图候选，浏览器永远选不到原图） */
  originalOnly: { type: Boolean, default: false },
  /**
   * 媒体项列表，按顺序轮换；第一项是置顶项。
   * 每项形如 { original: '/media/..?v=..', variants: [{ w, url }] }，
   * variants 可能缺失或为空数组（回退原图）。
   */
  items: { type: Array, default: () => [] },
  name: { type: String, default: '' },
  /** <img sizes>，由调用方按实际显示宽度传入 */
  sizes: {
    type: String,
    default: '(max-width: 700px) 100vw, (max-width: 1200px) 45vw, 420px',
  },
  /** 首项的 fetchpriority；列表里的卡片用 auto，主视觉位传 high */
  priority: { type: String, default: 'auto' },
  /** 轮换间隔（秒） */
  interval: { type: Number, default: 6 },
  /** seq | random */
  mode: { type: String, default: 'seq' },
  /** 视频默认是否有声 */
  defaultSound: { type: Boolean, default: false },
  /** 是否显示圆点指示 */
  showDots: { type: Boolean, default: true },
  /** 是否开启自动轮换（全局设置可关闭） */
  enabled: { type: Boolean, default: true },
  /** 视频行为：autoNext 播完下一条 / repeat 重复当前 / stop 播完停止 */
  videoBehavior: { type: String, default: 'autoNext' },
})

/** 首个视频可播放时通知外部（游戏页据此从封面切到 CG） */
const emit = defineEmits(['video-ready', 'item-ready', 'load-progress', 'all-failed'])

const el = ref(null)
const muted = ref(!props.defaultSound)
/** 加载失败的索引；用普通 Set，配合 reactive 计数触发更新 */
const failed = ref([])
const ready = ref([])   // 已加载完成的索引
let timer = null
let observer = null
/** 是否由我们自己暂停（页面不可见 / 滚出视口）；看门狗据此跳过正常暂停 */
let pausedByUs = false
/** 当前是否在视口内 */
let onScreen = true
/** 看门狗连续检测到「该播没播」的秒数 */
let stallTicks = 0
/** 有声自动播放被浏览器拦截过：等待下一次手势或下一条目自动恢复声音 */
let autoMutedByPolicy = false
/** 用户手动点过静音：此时不再自动恢复声音 */
let userMuted = false
/** 每次 play() 自增；过期尝试的回调不再改状态，避免旧拦截把刚恢复的声音又关掉 */
let playToken = 0
/** 同一个 src 上自动重试恢复声音的次数；换源 / 用户手势 / 用户手点后清零 */
let soundRetries = 0
/** 看门狗定时器 */
let watchdog = null

const current = ref(0)

/** 当前项是否加载失败（模板里不能直接对 ref 调 .has） */
const currentFailed = computed(() => failed.value.includes(current.value))

function isVideo(url) {
  return /\.(mp4|webm)(\?|$)/i.test(url || '')
}

/** 可参与轮换的索引（已加载完成且未失败） */
const usable = computed(() => {
  const out = []
  ready.value.forEach((i) => {
    if (!failed.value.includes(i)) out.push(i)
  })
  return out
})

/** 当前媒体项（对象；缺失时给空项，模板与派生状态都不至于炸） */
const active = computed(() => props.items[current.value] || { original: '', variants: [] })

const activeUrl = computed(() => active.value.original || '')

const activeIsVideo = computed(() => isVideo(activeUrl.value))

/** srcset：variant 越小越先出画面，浏览器按 sizes 选档 */
/**
 * 派生图的 srcset；逻辑见 media-srcset.js。
 *
 * originalOnly=true 时不渲染 srcset，浏览器只能加载原图（详情页用）。
 */

/** 预加载用的最小派生图；没有 variants 时回退原图 */
function smallestUrl(item) {
  const vars = item?.variants || []
  if (!vars.length) return item?.original || ''
  let best = vars[0]
  for (const v of vars) {
    if (v.w < best.w) best = v
  }
  return best.url
}

async function preloadOne(item, idx) {
  const url = item?.original || ''
  if (!url) {
    markFailed(idx)
    return
  }
  // 全局并发闸门：超过上限时在这里排队，避免几十张封面同时抢带宽
  await acquirePreloadSlot()
  let released = false
  const release = () => {
    if (released) return
    released = true
    releasePreloadSlot()
  }

  return new Promise((resolve) => {
    let settled = false
    const settle = () => {
      if (settled) return
      settled = true
      resolve()
    }
    const finish = (ok) => {
      clearTimeout(guard)
      release()
      if (ok) markReady(idx)
      else markFailed(idx)
      settle()
    }
    // 网络挂起时 onload / onerror 都不来，5 秒兜底释放，避免闸门被永久占死
    const guard = setTimeout(() => {
      release()
      settle()
    }, 5000)

    if (isVideo(url)) {
      // 视频只用 metadata 预加载，不下载完整内容
      const v = document.createElement('video')
      v.preload = 'metadata'
      v.muted = true
      v.onloadedmetadata = () => finish(true)
      v.onerror = () => finish(false)
      v.src = url
      return
    }
    // 注意：不能设 loading='lazy'。这里用的是游离（不在文档里）的 Image 对象，
    // lazy 依赖视口判断，游离图片永远不会进入视口，onload 永不触发，
    // 结果只有第 0 项可用，轮换直接不启动。
    const img = new Image()
    img.onload = () => finish(true)
    img.onerror = () => finish(false)
    img.src = props.originalOnly ? url : smallestUrl(item)
  })
}

function markFailed(idx) {
  if (!failed.value.includes(idx)) failed.value.push(idx)
  // 当前项失败就跳下一项，别卡在空白/占位
  if (idx === current.value) {
    const next = ready.value.find((i) => i !== idx && !failed.value.includes(i))
    if (next !== undefined) current.value = next
  }
  // 全部失败时通知外部，避免外层一直停在「加载中」
  if (props.items.length && failed.value.length >= props.items.length) {
    emit('all-failed')
  }
}

function markReady(idx) {
  if (!ready.value.includes(idx)) {
    ready.value = [...ready.value, idx].sort((a, b) => a - b)
  }
}

function pickNext() {
  const list = usable.value
  if (list.length <= 1) return
  const pos = list.indexOf(current.value)
  if (props.mode === 'random') {
    let n
    do { n = list[Math.floor(Math.random() * list.length)] } while (n === current.value && list.length > 1)
    current.value = n
  } else {
    current.value = list[(pos + 1) % list.length]
  }
}

function startTimer() {
  stopTimer()
  const ms = Math.max(1000, props.interval * 1000)
  timer = setInterval(pickNext, ms)
}

function stopTimer() {
  if (timer) { clearInterval(timer); timer = null }
}

/** autoNext 模式下视频靠播放结束事件切换，图片仍按定时器 */
function autoNextMode() {
  return props.videoBehavior === 'autoNext'
}

function shouldTick() {
  if (!props.enabled) return false
  if (usable.value.length <= 1) return false
  // repeat / stop 的视频不靠定时器；图片两种模式下仍按间隔切换
  if (activeIsVideo.value && !autoNextMode()) return false
  if (autoNextMode() && activeIsVideo.value) return false
  return true
}

function syncTimer() {
  if (shouldTick()) {
    if (!timer) startTimer()
  } else {
    stopTimer()
  }
}

function onVideoEnded() {
  if (autoNextMode() && usable.value.length > 1) {
    pickNext()
  }
}

function shouldLoop() {
  if (props.videoBehavior === 'repeat') return true
  return autoNextMode() && usable.value.length <= 1
}

function pause() {
  stopTimer()
  pausedByUs = true
  const v = el.value?.querySelector?.('video')
  if (v && !v.paused) v.pause()
}

/** 期望有声：调用方要求有声音，且用户没有手动静音过 */
function wantSound() {
  return props.defaultSound && !userMuted
}

function play() {
  const v = el.value?.querySelector?.('video')
  if (!v) return
  pausedByUs = false
  stallTicks = 0
  const token = ++playToken
  // 每次播放都按「期望是否出声」重置静音状态，避免一次拦截永久静音
  const want = wantSound()
  v.muted = !want
  muted.value = !want
  const p = v.play()
  if (p && typeof p.catch === 'function') {
    p.catch((e) => {
      // 过期的尝试（已有更新的 play()）不再改状态：否则上一次被拦的回调
      // 会把刚刚恢复的声音又静音回去，表现为「声音自己开不起来」
      if (token !== playToken) return
      // 有声自动播放被拦截：先降级静音把画面播起来，
      // 之后任何用户手势 / 下一个条目都会自动尝试恢复声音
      if (e && e.name === 'NotAllowedError' && !v.muted) {
        autoMutedByPolicy = true
        muted.value = true
        v.muted = true
        const retry = v.play()
        if (retry && typeof retry.catch === 'function') retry.catch(() => {})
      }
    })
  }
}

/** 任意用户手势都尝试恢复声音；若仍被拦截就退回静音继续等下一次 */
function restoreSoundOnGesture(e) {
  if (!autoMutedByPolicy || userMuted) return
  // 静音按钮自己走 click → toggleMute，这里必须让路：
  // 否则 pointerdown（捕获阶段，先于 click）会先把声音打开，
  // 紧接着同一次点击的 toggleMute 又按新状态切回去，表现成
  // 「点开声音反而被关掉」，要点第二次才生效
  if (e?.target instanceof Element && e.target.closest('.rotator-mute')) return
  autoMutedByPolicy = false
  soundRetries = 0
  play()
}

/** 看门狗：当前视频「该播却没播」超过 3 秒就重试，6 秒仍不动就跳过这一条 */
function checkStall() {
  if (!props.enabled || !onScreen || document.hidden || pausedByUs) return
  const v = el.value?.querySelector?.('video')
  if (!v) { stallTicks = 0; return }
  if (!v.paused || v.ended) { stallTicks = 0; return }
  if (v.readyState < 2) return          // 还在加载，交给加载逻辑
  stallTicks++
  if (stallTicks >= 6) {
    stallTicks = 0
    markFailed(current.value)
    return
  }
  // 每秒重试一次播放；若只是被自动播放策略拦下（画面在动、没卡住），
  // 不去调 play()，否则反复触发拦截回调，声音永远恢复不了
  if (stallTicks >= 3 && shouldRetryPlay()) play()
}

/**
 * 是否值得再调一次 play()：
 *  - 被策略拦过：只有当用户已经交互过才重试（重试才能恢复声音），否则跳过
 *  - 没被拦过：按原来「卡住就重试」的逻辑
 */
function shouldRetryPlay() {
  if (!autoMutedByPolicy) return true
  // 被策略拦过、且用户还没交互：重试必被拦，不要再打扰
  if (!userActivated()) return false
  if (soundRetries >= 2) return false
  soundRetries++
  return true
}

/** 用户是否已经与页面交互过（自动播放策略据此判断能否出声） */
function userActivated() {
  const ua = navigator.userActivation
  if (ua && typeof ua.hasBeenActive === 'boolean') return ua.hasBeenActive
  return false
}

function toggleMute() {
  muted.value = !muted.value
  // 用户手动静音后不再自动恢复；手动开声音则视为期望有声
  userMuted = muted.value
  autoMutedByPolicy = false
  // 用户点了按钮：重试额度重置，下一次播放按用户选择执行；
  // 同时作废在途播放的回调，避免旧拦截把用户刚选的声音又关掉
  soundRetries = 0
  playToken++
}

/** 已通知过 video-ready 的索引，避免同一项重复 emit */
const readyNotified = new Set()

/** 当前项真正加载完成（图片 onload / 视频 canplay）：通知外层可以显示 CG */
function onItemLoaded() {
  if (readyNotified.has(current.value)) return
  readyNotified.add(current.value)
  emit('item-ready', current.value)
}

/**
 * 视频可播放：先按「项就绪」通知，再补一个视频专用事件。
 *
 * 这里不再主动重试恢复声音：canplay 会随缓冲反复触发，
 * 没有用户手势时每次重试都必被拦截，旧回调还会把刚恢复的声音关掉。
 * 恢复声音交给手势（restoreSoundOnGesture）、用户点按钮或看门狗。
 */
function onVideoCanPlay() {
  onItemLoaded()
  emit('video-ready', current.value)
}

/** 视频缓冲进度：buffered / duration；拿不到时长时回 null（外层显示不确定态） */
function reportLoadProgress(e) {
  const v = e.target
  const d = v?.duration
  if (!Number.isFinite(d) || d <= 0) {
    emit('load-progress', null)
    return
  }
  const b = v.buffered
  const end = b && b.length ? b.end(b.length - 1) : 0
  emit('load-progress', Math.max(0, Math.min(100, Math.round((end / d) * 100))))
}

// 切换条目后重新计算进度，避免沿用上一条的百分比；换条后重试额度重新计
watch(activeUrl, () => {
  emit('load-progress', null)
  soundRetries = 0
})

/** 首屏：立即渲染起始项，其余空闲时预加载 */
function bootstrap() {
  ready.value = []
  failed.value = []
  readyNotified.clear()
  emit('load-progress', null)
  // 随机模式下「播放前」就选好起点，刷新页面看到不同 CG；顺序模式固定从第一项开始
  current.value = props.mode === 'random' && props.items.length > 1
    ? Math.floor(Math.random() * props.items.length)
    : 0
  if (!props.items.length) return

  // 起始项立即标记可用（img/video 自身的 onload 也会补一次）
  markReady(current.value)

  const rest = props.items.map((_, i) => i).filter((i) => i !== current.value)
  const loadNext = async () => {
    const i = rest.shift()
    if (i === undefined) return
    try {
      await preloadOne(props.items[i], i)
    } finally {
      // 单项结束后再排下一个：单个轮换组件内保持串行，全局由闸门限并发
      scheduleNext()
    }
  }
  const scheduleNext = () => {
    if (typeof requestIdleCallback === 'function') {
      requestIdleCallback(loadNext, { timeout: 1200 })
    } else {
      setTimeout(loadNext, 300)
    }
  }
  scheduleNext()
}

function startRotation() {
  if (!props.enabled) return
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  syncTimer()
}

watch(() => props.items, () => { bootstrap(); startRotation() })
watch(() => props.enabled, (on) => {
  if (on) {
    startRotation()
  } else {
    stopTimer()
    current.value = 0
  }
})
watch([usable, current, activeIsVideo], () => {
  if (props.enabled) syncTimer()
})

onMounted(() => {
  bootstrap()
  // 等首屏渲染后再起轮换
  setTimeout(startRotation, 400)

  if (typeof IntersectionObserver !== 'undefined' && el.value) {
    observer = new IntersectionObserver((entries) => {
      for (const e of entries) {
        onScreen = e.isIntersecting
        if (e.isIntersecting) { startRotation(); play() }
        else pause()
      }
    }, { threshold: 0.25 })
    observer.observe(el.value)
  }

  // 有声自动播放被拦截过时，等用户第一次交互恢复声音并重试
  document.addEventListener('pointerdown', restoreSoundOnGesture, true)
  document.addEventListener('keydown', restoreSoundOnGesture, true)
  // 看门狗：每秒检查一次是否「该播却没播」
  watchdog = setInterval(checkStall, 1000)
  document.addEventListener('visibilitychange', onVisibility)
})

function onVisibility() {
  if (document.visibilityState === 'visible') {
    startRotation(); play()
  } else {
    pause()
  }
}

onBeforeUnmount(() => {
  observer?.disconnect()
  if (watchdog) { clearInterval(watchdog); watchdog = null }
  document.removeEventListener('pointerdown', restoreSoundOnGesture, true)
  document.removeEventListener('keydown', restoreSoundOnGesture, true)
  document.removeEventListener('visibilitychange', onVisibility)
  stopTimer()
  pause()
})
</script>

<template>
  <div ref="el" class="rotator">
    <template v-if="items.length && !currentFailed">
      <video
        v-if="activeIsVideo"
        :key="activeUrl"
        :src="activeUrl"
        :muted="muted"
        autoplay
        :loop="shouldLoop()"
        playsinline
        preload="metadata"
        class="rotator-media"
        @ended="onVideoEnded"
        @canplay="onVideoCanPlay"
        @progress="reportLoadProgress"
        @timeupdate="reportLoadProgress"
        @error="markFailed(current)"
      />
      <img
        v-else
        :key="activeUrl"
        :src="activeUrl"
        :srcset="srcsetOf(active, originalOnly)"
        :sizes="props.sizes"
        :alt="name"
        :fetchpriority="current === 0 ? priority : 'low'"
        class="rotator-media"
        decoding="async"
        @load="onItemLoaded"
        @error="markFailed(current)"
      />
      <div v-if="showDots && enabled && usable.length > 1" class="rotator-dots">
        <span
          v-for="i in usable"
          :key="i"
          class="rotator-dot"
          :class="{ on: i === current }"
        />
      </div>
      <button v-if="activeIsVideo" class="rotator-mute" @click.stop="toggleMute">
        {{ muted ? '🔇' : '🔊' }}
      </button>
    </template>
    <div v-else class="rotator-empty">
      <span>{{ (name || 'G').slice(0, 1) }}</span>
    </div>
  </div>
</template>
