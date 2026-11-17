<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
/**
 * 悬停小窗预览：包住一个触发元素（编辑页的「预览」按钮），
 * 鼠标停留 150ms 后在旁边弹出 320×180 小窗显示图片或播放视频。
 *
 * 只用于编辑页；缩略图本身不挂悬停小窗，避免同一行出现两个入口。
 */
import { t } from '../i18n.js'
import { onBeforeUnmount, ref } from 'vue'

const props = defineProps({
  /** { url, video, filename }；url 为空时退回 poster */
  item: { type: Object, default: null },
  disabled: { type: Boolean, default: false },
})

const show = ref(false)
const pop = ref(null)
const videoEl = ref(null)
let timer = null

function onEnter() {
  if (props.disabled || !props.item) return
  clearTimeout(timer)
  timer = setTimeout(open, 150)
}

function onLeave() {
  clearTimeout(timer)
  hide()
}

function open() {
  show.value = true
  // 等 DOM 渲染后再定位
  requestAnimationFrame(() => {
    if (!show.value || !pop.value) return
    const anchor = eventAnchor()
    if (!anchor) return
    const w = pop.value.offsetWidth || 320
    const h = pop.value.offsetHeight || 220
    let x = anchor.right + 12
    if (x + w > window.innerWidth - 12) x = anchor.left - w - 12
    if (x < 12) x = Math.min(window.innerWidth - w - 12, Math.max(12, anchor.left))
    let y = anchor.top
    if (y + h > window.innerHeight - 12) y = window.innerHeight - h - 12
    if (y < 12) y = 12
    pop.value.style.left = x + 'px'
    pop.value.style.top = y + 'px'
    videoEl.value?.play?.().catch(() => {})
  })
}

/** 触发元素是组件根节点上的事件来源，用最近一次 mouseenter 的 target 记录 */
let anchorEl = null
function onEnterCapture(e) {
  anchorEl = e.currentTarget
  onEnter()
}
function eventAnchor() {
  return anchorEl?.getBoundingClientRect?.() || null
}

function hide() {
  show.value = false
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
  anchorEl = null
}

onBeforeUnmount(hide)
</script>

<template>
  <span class="hover-preview-trigger" @mouseenter="onEnterCapture" @mouseleave="onLeave">
    <slot />
  </span>
  <Teleport to="body">
    <div v-if="show" ref="pop" class="hover-preview" role="tooltip">
      <video
        v-if="item?.video && item?.url"
        ref="videoEl"
        :src="item.url"
        class="hover-preview-media"
        muted
        loop
        autoplay
        playsinline
      />
      <img
        v-else
        :src="item?.url || item?.poster || ''"
        class="hover-preview-media"
        :alt="item?.filename || ''"
      >
      <div class="hover-preview-cap">
        <span class="hp-name">{{ item?.filename || '' }}</span>
        <span class="hp-type">{{ item?.video ? t('视频') : t('图片') }}</span>
      </div>
    </div>
  </Teleport>
</template>
