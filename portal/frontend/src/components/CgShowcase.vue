<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
/**
 * 宣传 CG 展示：图片或视频，支持高/中/低三档画质。
 *
 * 设计原则：纯装饰，绝不拖累核心功能
 *  - 默认静音（可在设置里改为默认有声），不抢游戏声音
 *  - preload="metadata"，不预下载整个视频
 *  - 只在可见时才播放（IntersectionObserver），滚出视野即暂停
 *  - 加载/播放失败一律静默降级，不抛错、不弹窗
 *  - 档位选择：网络省流或小屏时自动降到低档；用户也可手动切换
 */
import { t } from '../i18n.js'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  /** 中档（主版本） */
  src: { type: String, default: '' },
  /** 高码率版本，可选 */
  highSrc: { type: String, default: '' },
  /** 低码率版本，可选 */
  lowSrc: { type: String, default: '' },
  name: { type: String, default: '' },
  /** 默认是否有声（来自设置） */
  defaultSound: { type: Boolean, default: false },
})

const el = ref(null)
const failed = ref(false)
const muted = ref(!props.defaultSound)
/** 'high' | 'mid' | 'low'，默认自动选择 */
const quality = ref('auto')
let observer = null

function isVideoUrl(u) {
  return /\.(mp4|webm)(\?|$)/i.test(u || '')
}

/** 可用的档位（只有存在的才有） */
const available = computed(() => {
  const out = []
  if (props.highSrc) out.push('high')
  if (props.src) out.push('mid')
  if (props.lowSrc) out.push('low')
  return out
})

/** 自动档：省流或小屏用低档，否则优先高档 */
function autoPick() {
  if (!available.value.length) return ''
  const conn = navigator.connection || navigator.mozConnection || navigator.webkitConnection
  const saveData = !!(conn && (conn.saveData || /2g/.test(conn.effectiveType || '')))
  const smallScreen = window.innerWidth < 700
  if ((saveData || smallScreen) && props.lowSrc) return 'low'
  if (props.highSrc) return 'high'
  return 'mid'
}

const chosen = computed(() => {
  if (quality.value === 'auto') return autoPick()
  if (quality.value === 'high' && props.highSrc) return 'high'
  if (quality.value === 'low' && props.lowSrc) return 'low'
  if (props.src) return 'mid'
  return autoPick()
})

const activeSrc = computed(() => {
  if (chosen.value === 'high') return props.highSrc || props.src || props.lowSrc
  if (chosen.value === 'low') return props.lowSrc || props.src || props.highSrc
  return props.src || props.highSrc || props.lowSrc
})

const activeIsVideo = computed(() => isVideoUrl(activeSrc.value))

const hasAny = computed(() => !!(props.src || props.highSrc || props.lowSrc))

function pause() {
  const v = el.value?.querySelector?.('video')
  if (v && !v.paused) v.pause()
}

function play() {
  const v = el.value?.querySelector?.('video')
  if (!v) return
  const p = v.play()
  if (p && typeof p.catch === 'function') p.catch(() => {})
}

function toggleMute() {
  muted.value = !muted.value
}

function cycleQuality() {
  const opts = ['auto', ...available.value]
  const i = opts.indexOf(quality.value)
  quality.value = opts[(i + 1) % opts.length]
}

const qualityLabel = computed(() => {
  if (quality.value === 'auto') return t('自动')
  return { high: t('高清'), mid: t('标准'), low: t('流畅') }[quality.value] || quality.value
})

onMounted(() => {
  if (typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (e.isIntersecting) play()
      else pause()
    }
  }, { threshold: 0.25 })
  if (el.value) observer.observe(el.value)
})

// 换源后重置状态并继续播放
watch(activeSrc, () => {
  failed.value = false
  if (activeIsVideo.value) setTimeout(play, 0)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  pause()
})
</script>

<template>
  <div ref="el" class="cg-box">
    <template v-if="hasAny && !failed">
      <video
        v-if="activeIsVideo"
        :key="activeSrc"
        :src="activeSrc"
        :muted="muted"
        autoplay
        loop
        playsinline
        preload="metadata"
        class="cg-media"
        @error="failed = true"
      />
      <img
        v-else
        :key="activeSrc"
        :src="activeSrc"
        :alt="name + t(' 宣传图')"
        class="cg-media"
        loading="lazy"
        decoding="async"
        @error="failed = true"
      />

      <div class="cg-controls">
        <button v-if="activeIsVideo" class="cg-btn" @click.stop="toggleMute">
          {{ muted ? '🔇' : '🔊' }}
        </button>
        <button
          v-if="available.length > 1"
          class="cg-btn"
          :title="t('画质：{0}（点击切换）', qualityLabel)"
          @click.stop="cycleQuality"
        >{{ qualityLabel }}</button>
      </div>
    </template>
    <div v-else class="cg-empty">
      <span>{{ (name || 'G').slice(0, 1) }}</span>
    </div>
  </div>
</template>
