<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
/**
 * 批量上传器：预检 → 并发队列（2 路）→ 单文件进度 / 取消 / 重试。
 *
 * 上传即时进行：选中或拖入文件后马上发起请求，成功后把服务端返回的媒体项
 * 交给外层追加到列表；「保存」只负责删除与排序。
 *
 * 取消上传后延迟对账一次：abort 可能发生在服务端已落库、响应尚未返回之间，
 * 以 GET /media/{kind} 的返回为准，避免界面与服务端不一致。
 */
import { t } from '../i18n.js'
import { computed, onBeforeUnmount, ref } from 'vue'
import { api } from '../api'

const props = defineProps({
  gameId: { type: String, required: true },
  /** cover | cg */
  kind: { type: String, required: true },
  /** 当前已保存（或已上传）的媒体数量，用于计算剩余名额 */
  used: { type: Number, default: 0 },
  max: { type: Number, default: 8 },
  imageMaxMb: { type: Number, default: 8 },
  videoMaxMb: { type: Number, default: 120 },
})
const emit = defineEmits(['uploaded', 'reconcile', 'busy'])

const CONCURRENCY = 2
const IMAGE_RE = /\.(png|jpe?g|webp|gif)$/i
const VIDEO_RE = /\.(mp4|webm)$/i
const MB = 1024 * 1024

let seq = 0
const queue = ref([])
const running = ref(0)
/** 预检结果确认框 */
const dialog = ref(null)

const active = computed(() =>
  queue.value.filter((t) => t.status === 'waiting' || t.status === 'uploading'))
const failed = computed(() => queue.value.filter((t) => t.status === 'failed'))
/** 本轮批量上传的统计：成功项会从队列移除，单独计数 */
const stats = ref({ total: 0, done: 0 })
const totalCount = computed(() => active.value.length + failed.value.length)
const overallPct = computed(() => {
  const total = stats.value.total
  if (!total) return 0
  const sum = stats.value.done + queue.value.reduce((n, t) => n + t.progress, 0)
  return Math.round((sum / total) * 100)
})

function notifyBusy() {
  emit('busy', active.value.length > 0)
}

function isVideo(file) {
  return (file.type || '').startsWith('video/') || VIDEO_RE.test(file.name)
}

/** 预检：返回可上传与被拒绝的文件 */
function precheck(files) {
  const accepted = []
  const rejected = []
  let slots = Math.max(0, props.max - props.used - queue.value
    .filter((t) => t.status !== 'failed').length)

  for (const file of files) {
    const video = isVideo(file)
    if (props.kind === 'cover' && video) {
      rejected.push({ file, reason: t('封面只支持图片') })
      continue
    }
    if (video && !VIDEO_RE.test(file.name)) {
      rejected.push({ file, reason: t('只支持 mp4 / webm') })
      continue
    }
    if (!video && !IMAGE_RE.test(file.name)) {
      rejected.push({ file, reason: t('不支持的格式') })
      continue
    }
    const maxMb = video ? props.videoMaxMb : props.imageMaxMb
    if (file.size > maxMb * MB) {
      rejected.push({ file, reason: t("超过 {0} MB", maxMb) })
      continue
    }
    if (slots <= 0) {
      rejected.push({
        file,
        reason: t("超出数量上限（{0}最多 {1} 个）", props.kind === 'cover' ? t('封面') : 'CG', props.max),
      })
      continue
    }
    slots--
    accepted.push({ file, video })
  }
  return { accepted, rejected }
}

/** 外层调用：input change / 拖放 */
function accept(files) {
  const list = Array.from(files || [])
  if (!list.length) return
  const { accepted, rejected } = precheck(list)
  if (rejected.length) {
    // 有被拒文件时先让用户确认，不静默丢弃
    dialog.value = { accepted, rejected }
    return
  }
  if (accepted.length) enqueue(accepted)
}

function confirmDialog(ok) {
  const d = dialog.value
  dialog.value = null
  if (ok && d?.accepted?.length) enqueue(d.accepted)
}

function enqueue(items) {
  for (const { file, video } of items) {
    queue.value.push({
      key: 'up-' + (++seq),
      file,
      video,
      status: 'waiting',
      progress: 0,
      error: '',
      retried: false,
      previewUrl: URL.createObjectURL(file),
      xhr: null,
    })
    stats.value.total++
  }
  pump()
  notifyBusy()
}

function pump() {
  while (running.value < CONCURRENCY) {
    const task = queue.value.find((t) => t.status === 'waiting')
    if (!task) break
    start(task)
  }
  notifyBusy()
}

function start(task) {
  task.status = 'uploading'
  task.progress = 0
  running.value++

  api.uploadMedia(props.gameId, props.kind, task.file,
    (p) => { task.progress = p },
    (xhr) => { task.xhr = xhr })
    .then((res) => {
      const media = res?.media || res?.all?.at?.(-1)
      if (!media) throw new Error(t('上传后没有返回媒体记录'))
      stats.value.done++
      releaseTask(task)
      running.value = Math.max(0, running.value - 1)
      emit('uploaded', media)
      pump()
    })
    .catch((e) => {
      running.value = Math.max(0, running.value - 1)
      if (task.canceled) {
        releaseTask(task)
        reconcileSoon()
      } else {
        task.status = 'failed'
        task.error = e?.message || t('上传失败')
      }
      pump()
    })
}

function retry(task) {
  task.status = 'waiting'
  task.progress = 0
  task.error = ''
  task.retried = true
  pump()
}

function cancel(task) {
  task.canceled = true
  if (task.xhr && task.status === 'uploading') {
    try { task.xhr.abort() } catch { /* 已结束 */ }
  }
  if (task.status === 'waiting') {
    // 还没占用并发槽位，直接移除
    releaseTask(task)
    reconcileSoon()
    pump()
  }
  notifyBusy()
}

function removeTask(task) {
  releaseTask(task)
  notifyBusy()
}

function releaseTask(task) {
  queue.value = queue.value.filter((t) => t !== task)
  if (task.previewUrl) {
    try { URL.revokeObjectURL(task.previewUrl) } catch { /* 已释放 */ }
    task.previewUrl = ''
  }
  // 队列清空后重置本轮统计，避免下一次上传沿用旧分母
  if (!queue.value.length) stats.value = { total: 0, done: 0 }
}

/** abort 后延迟对账，服务端可能已经保存成功 */
function reconcileSoon() {
  setTimeout(() => {
    api.listMedia(props.gameId, props.kind)
      .then((list) => emit('reconcile', list || []))
      .catch(() => { /* 对账失败留给下次进入页面时同步 */ })
  }, 600)
}

function cancelAll() {
  for (const task of [...queue.value]) cancel(task)
}

onBeforeUnmount(() => {
  for (const task of [...queue.value]) {
    if (task.xhr && task.status === 'uploading') {
      try { task.xhr.abort() } catch { /* 忽略 */ }
    }
    if (task.previewUrl) URL.revokeObjectURL(task.previewUrl)
  }
})

defineExpose({ accept, cancelAll, active })
</script>

<template>
  <div v-if="totalCount" class="upload-summary">
    <div class="upload-summary-head">
      <span>{{ t("上传中 {0}/{1}", Math.min(stats.done + active.length, stats.total), stats.total) }}</span>
      <span>{{ overallPct }}%</span>
    </div>
    <div class="upload-progress-track">
      <div class="upload-progress-fill" :style="{ width: overallPct + '%' }" />
    </div>
    <button v-if="active.length" class="link-btn danger upload-cancel-all" type="button" @click="cancelAll">
      {{ t("全部取消") }}</button>
  </div>

  <div
    v-for="task in queue"
    :key="task.key"
    class="media-item upload-row"
    :class="{ failed: task.status === 'failed' }"
  >
    <div class="upload-thumb">
      <video v-if="task.video" :src="task.previewUrl" class="media-thumb" muted preload="metadata" />
      <img v-else :src="task.previewUrl" class="media-thumb" :alt="task.file.name">
    </div>
    <span class="kind">{{ task.video ? t('视频') : t('图片') }}</span>
    <span class="name">{{ task.file.name }}</span>

    <template v-if="task.status === 'failed'">
      <span class="upload-badge failed">{{ t("失败") }}</span>
      <span class="upload-status" :title="task.error">{{ task.error }}</span>
      <button class="link-btn" type="button" @click="retry(task)">{{ t("重试") }}</button>
      <button class="link-btn danger" type="button" @click="removeTask(task)">{{ t("移除") }}</button>
    </template>

    <template v-else>
      <span class="upload-badge" :class="task.status === 'waiting' ? 'waiting' : 'uploading'">
        {{ task.status === 'waiting' ? t('等待') : t('上传中') }}
      </span>
      <div class="upload-row-progress">
        <div class="upload-progress-track">
          <div class="upload-progress-fill" :style="{ width: Math.round(task.progress * 100) + '%' }" />
        </div>
      </div>
      <span class="upload-status">
        {{ task.status === 'waiting' ? t('等待中') : Math.round(task.progress * 100) + '%' }}
      </span>
      <button class="link-btn danger" type="button" @click="cancel(task)">{{ t("取消") }}</button>
    </template>
  </div>

  <Teleport to="body">
    <div v-if="dialog" class="upload-mask" @click.self="confirmDialog(false)">
      <div class="upload-dialog" role="dialog" aria-modal="true" :aria-label="t('上传前检查')">
        <h3>{{ t("上传前检查") }}</h3>
        <p class="muted-sm">
          {{ t("可上传 {0} 个", dialog.accepted.length) }}<span v-if="dialog.rejected.length">{{ t("，被拒绝 {0} 个", dialog.rejected.length) }}</span>
        </p>
        <ul v-if="dialog.accepted.length" class="upload-dialog-list">
          <li v-for="a in dialog.accepted.slice(0, 6)" :key="a.file.name + a.file.size">
            <span class="fname">{{ a.file.name }}</span>
            <span class="ok">{{ t("可上传 · {0}", a.video ? t('视频') : t('图片')) }}</span>
          </li>
          <li v-if="dialog.accepted.length > 6">
            <span class="fname">{{ t("…另有 {0} 个", dialog.accepted.length - 6) }}</span>
          </li>
        </ul>
        <ul v-if="dialog.rejected.length" class="upload-dialog-list">
          <li v-for="r in dialog.rejected" :key="r.file.name + r.file.size">
            <span class="fname">{{ r.file.name }}</span>
            <span class="reason">{{ r.reason }}</span>
          </li>
        </ul>
        <div class="upload-dialog-actions">
          <button class="btn sm" type="button" @click="confirmDialog(false)">{{ t("取消") }}</button>
          <button
            class="btn primary sm"
            type="button"
            :disabled="!dialog.accepted.length"
            @click="confirmDialog(true)"
          >{{ dialog.accepted.length ? t("继续上传 {0} 个", dialog.accepted.length) : t('知道了') }}</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
