<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
/**
 * 媒体区：封面 / 宣传 CG 共用。
 *
 * 负责「列表渲染 + 拖动排序 + 拖放上传 + 删除」，数据与持久化仍在父组件：
 * 本组件只发事件，不直接调 API——排序与删除都要点「保存」才落库。
 *
 * icon 不走这里：图标是单张且固定文件名，交互与多图列表不同。
 */
import { computed, ref } from 'vue'
import MediaUploader from './MediaUploader.vue'
import HoverPreview from './HoverPreview.vue'

const props = defineProps({
  gameId: { type: String, required: true },
  /** cover | cg */
  kind: { type: String, required: true },
  title: { type: String, required: true },
  items: { type: Array, required: true },
  max: { type: Number, required: true },
  imageMaxMb: { type: Number, required: true },
  videoMaxMb: { type: Number, required: true },
  /** 数量单位：张 / 个 */
  unit: { type: String, default: '张' },
  /** 每行左侧的序号标签类型：数字序号，或按媒体类型显示「图片 / 视频」 */
  indexMode: { type: String, default: 'number' },
  /** 上传中时的百分比（0-1），null 表示不显示进度条 */
  uploadPct: { type: Number, default: null },
  uploadLabel: { type: String, default: '' },
  /** 是否接受视频（封面只收图片，CG 两者都收） */
  allowVideo: { type: Boolean, default: false },
})

const emit = defineEmits(['remove', 'reorder', 'uploaded', 'reconcile', 'busy', 'preview'])

const VIDEO_RE = /\.(mp4|webm)$/i

const uploader = ref(null)
const dragging = ref(false)
const dropOver = ref(false)
const dragIndex = ref(-1)
const dragOverIndex = ref(-1)

const accept = computed(() => props.allowVideo
  ? 'image/png,image/jpeg,image/webp,image/gif,video/mp4,video/webm'
  : 'image/png,image/jpeg,image/webp,image/gif')

function isVideoItem(item) {
  return item.file
    ? (item.file.type || '').startsWith('video/') || VIDEO_RE.test(item.filename)
    : VIDEO_RE.test(item.filename)
}

function hoverItemOf(item) {
  return { url: item.url, video: isVideoItem(item), filename: item.filename }
}

/** 把选中的文件交给上传器做预检与入队 */
function pick(ev) {
  const files = Array.from(ev.target.files || [])
  ev.target.value = ''
  if (files.length) uploader.value?.accept(files)
}

function onUploaded(media) {
  emit('uploaded', media)
}

function onReconcile(list) {
  emit('reconcile', list)
}

function onBusy(busy) {
  emit('busy', busy)
}

/** 拖放区：只接管从系统拖入的文件，卡片内部排序不受影响 */
function hasFileDrag(ev) {
  const t = ev.dataTransfer && ev.dataTransfer.types
  return !!t && Array.prototype.indexOf.call(t, 'Files') >= 0
}

function onZoneEnter(ev) {
  if (!hasFileDrag(ev)) return
  ev.preventDefault()
  dropOver.value = true
}

function onZoneOver(ev) {
  if (!hasFileDrag(ev)) return
  ev.preventDefault()
  ev.dataTransfer.dropEffect = 'copy'
}

function onZoneLeave(ev) {
  if (!hasFileDrag(ev)) return
  dropOver.value = false
}

function onZoneDrop(ev) {
  if (!hasFileDrag(ev)) return
  ev.preventDefault()
  dropOver.value = false
  const files = Array.from(ev.dataTransfer.files || [])
  if (files.length) uploader.value?.accept(files)
}

/** 拖动排序只改本地顺序，父组件在保存时统一提交 */
function onDragStart(index) {
  dragging.value = true
  dragIndex.value = index
}

function onDragOver(index) {
  if (!dragging.value) return
  dragOverIndex.value = index
}

function onDrop(index) {
  const from = dragIndex.value
  const dragEnd = () => {
    dragging.value = false
    dragIndex.value = -1
    dragOverIndex.value = -1
  }
  dragEnd()
  if (from < 0 || index < 0 || from === index) return
  emit('reorder', { from, to: index })
}

function onDragEnd() {
  dragging.value = false
  dragIndex.value = -1
  dragOverIndex.value = -1
}
</script>

<template>
  <div class="section-title">{{ title }}</div>
  <div
    class="media-list media-dropzone"
    :class="{ 'drop-over': dropOver }"
    @dragenter="onZoneEnter"
    @dragover="onZoneOver"
    @dragleave="onZoneLeave"
    @drop="onZoneDrop"
  >
    <div
      v-for="(m, i) in items"
      :key="m.key"
      class="media-item"
      draggable="true"
      :class="{
        dragging: dragging && dragIndex === i,
        'drag-over': dragging && dragOverIndex === i && dragIndex !== i,
      }"
      @dragstart="onDragStart(i)"
      @dragover.prevent="onDragOver(i)"
      @drop.prevent="onDrop(i)"
      @dragend="onDragEnd"
    >
      <span class="drag-handle" title="拖动排序">⠿</span>
      <span class="media-thumb-btn" title="点击预览" @click="emit('preview', i)">
        <img v-if="!isVideoItem(m)" :src="m.url" class="media-thumb" :alt="m.filename">
        <video v-else :src="m.url" class="media-thumb" muted preload="metadata" />
      </span>
      <span class="kind">{{ indexMode === 'number' ? i + 1 : (isVideoItem(m) ? '视频' : '图片') }}</span>
      <span class="name">{{ m.filename }}</span>
      <span v-if="m.fresh" class="fresh-badge">已上传</span>
      <HoverPreview :item="hoverItemOf(m)">
        <button class="link-btn" type="button" @click="emit('preview', i)">预览</button>
      </HoverPreview>
      <button class="link-btn danger" @click="emit('remove', i)">删除</button>
    </div>

    <MediaUploader
      ref="uploader"
      :game-id="gameId"
      :kind="kind"
      :used="items.length"
      :max="max"
      :image-max-mb="imageMaxMb"
      :video-max-mb="videoMaxMb"
      @uploaded="onUploaded"
      @reconcile="onReconcile"
      @busy="onBusy"
    />
  </div>

  <div style="margin-top:10px">
    <label class="file-btn">
      {{ kind === 'cover' ? '添加封面' : '添加 CG' }}
      <input type="file" :accept="accept" multiple hidden @change="pick">
    </label>
    <span class="muted-sm" style="margin-left:10px">
      最多 {{ max }} {{ unit }}，图片 {{ imageMaxMb }} MB<template v-if="allowVideo"> / 视频 {{ videoMaxMb }} MB</template>
    </span>
  </div>

  <div v-if="uploadPct !== null" class="upload-progress">
    <div class="upload-progress-head">
      <span>{{ uploadLabel }}</span><span>{{ Math.round(uploadPct * 100) }}%</span>
    </div>
    <div class="upload-progress-track">
      <div class="upload-progress-fill" :style="{ width: (uploadPct * 100).toFixed(1) + '%' }" />
    </div>
  </div>
</template>
