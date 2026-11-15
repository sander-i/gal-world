<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
/**
 * 媒体灯箱：图片放大 / 视频播放，← → 切换，Esc 或点背景关闭。
 * 只负责展示，不感知上传与排序。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** [{ key, url, poster, video, filename }] */
  items: { type: Array, default: () => [] },
  index: { type: Number, default: 0 },
})
const emit = defineEmits(['update:visible', 'update:index'])

const videoEl = ref(null)

const current = computed(() => props.items[props.index] || null)
const count = computed(() => props.items.length)

function close() {
  stopVideo()
  emit('update:visible', false)
}

function step(delta) {
  if (count.value <= 1) return
  stopVideo()
  emit('update:index', (props.index + delta + count.value) % count.value)
}

function stopVideo() {
  const v = videoEl.value
  if (v) {
    try {
      v.pause()
      v.removeAttribute('src')
      v.load()
    } catch {
      // 忽略卸载失败
    }
  }
}

function onKey(e) {
  if (!props.visible) return
  if (e.key === 'Escape') {
    e.preventDefault()
    close()
  } else if (e.key === 'ArrowLeft') {
    e.preventDefault()
    step(-1)
  } else if (e.key === 'ArrowRight') {
    e.preventDefault()
    step(1)
  }
}

function lock(on) {
  document.body.style.overflow = on ? 'hidden' : ''
}

watch(() => props.visible, (on) => {
  lock(on)
  if (on) document.addEventListener('keydown', onKey)
  else {
    document.removeEventListener('keydown', onKey)
    stopVideo()
  }
})

// 列表被清空（例如后台刷新）时自动关闭，避免锁住滚动却看不到内容
watch(() => props.items.length, (len) => {
  if (props.visible && !len) close()
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKey)
  lock(false)
})
</script>

<template>
  <Teleport to="body">
    <div v-if="visible && current" class="media-lightbox" role="dialog" aria-modal="true" aria-label="媒体预览">
      <div class="ml-head">
        <span class="ml-file">{{ current.filename }}</span>
        <span class="ml-idx">{{ index + 1 }} / {{ count }}</span>
        <button class="ml-close" type="button" title="关闭（Esc）" @click="close">×</button>
      </div>

      <div class="ml-body" @click.self="close">
        <video
          v-if="current.video && current.url"
          ref="videoEl"
          :src="current.url"
          class="ml-media"
          controls
          autoplay
          muted
          playsinline
        />
        <img
          v-else
          :src="current.url || current.poster || ''"
          class="ml-media"
          :alt="current.filename"
        >
      </div>

      <template v-if="count > 1">
        <button class="ml-nav ml-prev" type="button" title="上一项（←）" @click="step(-1)">‹</button>
        <button class="ml-nav ml-next" type="button" title="下一项（→）" @click="step(1)">›</button>
      </template>
      <div class="ml-foot">← / → 切换 · Esc 关闭</div>
    </div>
  </Teleport>
</template>
