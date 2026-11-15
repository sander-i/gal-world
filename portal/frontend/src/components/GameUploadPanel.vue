<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, getWriteToken } from '../api'
import { notify } from '../feedback'
import { formatBytes } from '../game-import'
import {
  canStartUpload,
  fingerprintSelection,
  folderRootName,
  isArchiveName,
  mergeUploadEvent,
  sha256Hex,
  speedEta,
  uploadPercent,
} from '../game-upload'

const props = defineProps({
  limits: { type: Object, default: () => ({}) },
  supported: { type: Boolean, default: true },
  /** 页面刷新时自动进入「继续配置」；从配置页点「上一步」回来时为 false */
  autoResume: { type: Boolean, default: true },
})
const emit = defineEmits(['ready'])

const STORE_KEY = 'galworld.import.upload'
const MAX_SAMPLES = 12

const tab = ref('archive')
const name = ref('')
const archiveFile = ref(null)
const folderFiles = ref([])
const uploadId = ref('')
// idle | uploading | paused | extracting | ready | failed
const state = ref('idle')
const error = ref('')
const progress = ref({ uploadedBytes: 0, totalBytes: 0 })
const speed = ref(0)
const eta = ref(null)
const logLines = ref([])
const steps = ref([])
const uploadedCard = ref(null)
const pending = ref(null)
const archiveInput = ref(null)
const folderInput = ref(null)

let currentXhr = null
let abort = null
let samples = []
let paused = false

// Firefox / Safari 不支持 webkitdirectory：只保留压缩包上传
const folderSupported = (() => {
  try {
    return 'webkitdirectory' in document.createElement('input')
  } catch {
    return false
  }
})()

const selection = computed(() => {
  if (tab.value === 'archive') {
    return archiveFile.value ? { kind: 'ARCHIVE', file: archiveFile.value } : null
  }
  return folderFiles.value.length ? { kind: 'FOLDER', files: folderFiles.value } : null
})

const check = computed(() => canStartUpload({ name: name.value }, selection.value, props.limits))

const info = computed(() => {
  if (!selection.value) return ''
  const r = check.value
  const total = r.totalBytes != null ? formatBytes(r.totalBytes) : ''
  if (selection.value.kind === 'ARCHIVE') {
    return `${archiveFile.value.name} · ${total}`
  }
  return `${folderRootName(folderFiles.value) || '文件夹'} · ${r.count} 个文件 · ${total}`
})

const percent = computed(() =>
  uploadPercent(progress.value.uploadedBytes, progress.value.totalBytes))
const percentText = computed(() => {
  const p = progress.value
  return `${formatBytes(p.uploadedBytes)} / ${formatBytes(p.totalBytes)}（${percent.value}%）`
})
const speedText = computed(() => (speed.value > 0 ? `${formatBytes(speed.value)}/s` : '—'))
const etaText = computed(() => {
  if (eta.value == null) return '剩余时间未知'
  const s = Number(eta.value)
  if (s < 60) return `剩余 ${s} 秒`
  const m = Math.floor(s / 60)
  return `剩余 ${m} 分 ${s % 60} 秒`
})
const busy = computed(() => state.value === 'uploading' || state.value === 'extracting')

// ---------------- 选择 ----------------

function resetSamples() {
  samples = []
  speed.value = 0
  eta.value = null
}

function onArchiveChange(e) {
  const f = e.target.files?.[0] || null
  archiveFile.value = f
  if (f) {
    tab.value = 'archive'
    if (!name.value) name.value = f.name.replace(/\.(zip|7z)$/i, '')
  }
  syncPending(f ? { kind: 'ARCHIVE', file: f } : null)
}

function onFolderChange(e) {
  folderFiles.value = Array.from(e.target.files || [])
  if (folderFiles.value.length) {
    tab.value = 'folder'
    if (!name.value) name.value = folderRootName(folderFiles.value)
  }
  syncPending(folderFiles.value.length ? { kind: 'FOLDER', files: folderFiles.value } : null)
}

async function onDrop(e) {
  if (busy.value) return
  const items = Array.from(e.dataTransfer?.items || [])
  const entries = items.map((it) => it.webkitGetAsEntry?.()).filter(Boolean)
  if (!entries.length) {
    // 普通文件拖入：按压缩包处理
    const f = e.dataTransfer?.files?.[0]
    if (f) {
      archiveFile.value = f
      tab.value = 'archive'
      if (!name.value) name.value = f.name.replace(/\.(zip|7z)$/i, '')
      syncPending({ kind: 'ARCHIVE', file: f })
    }
    return
  }
  const files = []
  try {
    for (const entry of entries) {
      if (entry.isFile && isArchiveName(entry.name)) {
        const f = await entryFile(entry)
        archiveFile.value = f
        tab.value = 'archive'
        if (!name.value) name.value = entry.name.replace(/\.(zip|7z)$/i, '')
        syncPending({ kind: 'ARCHIVE', file: f })
        return
      }
      await walkEntry(entry, '', files)
    }
  } catch (err) {
    notify.error('读取拖入内容失败：' + err.message)
    return
  }
  if (files.length) {
    folderFiles.value = files
    tab.value = 'folder'
    if (!name.value) name.value = folderRootName(files)
    syncPending({ kind: 'FOLDER', files })
  }
}

function entryFile(entry) {
  return new Promise((resolve, reject) => entry.file(resolve, reject))
}

function readEntries(reader) {
  return new Promise((resolve, reject) => reader.readEntries(resolve, reject))
}

async function walkEntry(entry, prefix, out) {
  if (entry.isFile) {
    const file = await entryFile(entry)
    const path = prefix + entry.name
    try {
      Object.defineProperty(file, 'webkitRelativePath', { value: path })
    } catch {
      // 只读时忽略：指纹会退化成文件名
    }
    out.push(file)
    return
  }
  if (entry.isDirectory) {
    const reader = entry.createDirectoryReader ? entry.createDirectoryReader() : entry.createReader()
    let batch = await readEntries(reader)
    while (batch.length) {
      for (const child of batch) {
        await walkEntry(child, prefix + entry.name + '/', out)
      }
      batch = await readEntries(reader)
    }
  }
}

// ---------------- 续传恢复 ----------------

function saved() {
  try {
    return JSON.parse(localStorage.getItem(STORE_KEY) || 'null')
  } catch {
    return null
  }
}

function remember(id, fp, displayName) {
  localStorage.setItem(STORE_KEY, JSON.stringify({
    uploadId: id,
    fingerprint: fp,
    name: displayName,
    updatedAt: Date.now(),
  }))
}

function forget() {
  localStorage.removeItem(STORE_KEY)
  pending.value = null
  uploadId.value = ''
}

/** 用户重新选择文件后：指纹一致就续传已有会话，不一致则丢弃旧会话 */
async function syncPending(sel) {
  const p = pending.value
  if (!p || !sel) return
  const fp = fingerprintSelection(sel)
  if (fp === p.fingerprint) {
    uploadId.value = p.uploadId
    return
  }
  pending.value = null
  uploadId.value = ''
  localStorage.removeItem(STORE_KEY)
  try {
    await api.cancelUpload(p.uploadId)
  } catch {
    // 旧会话已过期或正在解压：不影响新建
  }
  notify.error('文件已变化，旧上传会话已丢弃，需要重新上传')
}

// ---------------- 上传 ----------------

function sourceFor(kind, file, path) {
  if (kind === 'ARCHIVE') return archiveFile.value
  const want = path
  return folderFiles.value.find((f) => (f.webkitRelativePath || f.name) === want) || null
}

async function start() {
  if (busy.value) return
  if (!check.value.ok) {
    notify.error(check.value.reason)
    return
  }
  error.value = ''
  logLines.value = []
  steps.value = []
  uploadedCard.value = null
  paused = false
  resetSamples()
  state.value = 'uploading'
  try {
    let created
    if (uploadId.value) {
      // 续传：复用会话，重新读一次状态
      const st = await api.uploadStatus(uploadId.value)
      created = {
        uploadId: st.uploadId,
        chunkSize: props.limits.chunkSize || 8388608,
        files: st.files.map((f) => ({ fileId: f.fileId, path: f.path, size: f.size })),
      }
      await uploadAll(created, st.files)
    } else {
      created = await api.createUpload(buildCreateBody())
      uploadId.value = created.uploadId
      remember(created.uploadId, fingerprintSelection(selection.value), name.value)
      await uploadAll(created, created.files)
    }
  } catch (e) {
    if (e.aborted) {
      state.value = 'paused'
      return
    }
    state.value = 'failed'
    error.value = e.message
    notify.error('上传失败：' + e.message)
  }
}

function buildCreateBody() {
  const sel = selection.value
  if (sel.kind === 'ARCHIVE') {
    return {
      kind: 'ARCHIVE',
      name: name.value.trim(),
      fileName: sel.file.name,
      totalBytes: sel.file.size,
    }
  }
  const files = sel.files.map((f) => ({
    path: f.webkitRelativePath || f.name,
    size: f.size,
  }))
  return {
    kind: 'FOLDER',
    name: name.value.trim(),
    totalBytes: files.reduce((a, f) => a + f.size, 0),
    files,
  }
}

async function uploadAll(created, serverFiles) {
  const kind = selection.value?.kind || 'ARCHIVE'
  const total = created.files.reduce((a, f) => a + f.size, 0)
  const done = {}
  for (const f of serverFiles || []) done[f.fileId] = f.uploadedBytes || 0

  progress.value = {
    uploadedBytes: Object.values(done).reduce((a, b) => a + b, 0),
    totalBytes: total,
  }

  for (const f of created.files) {
    const src = sourceFor(kind, f, f.path)
    if (!src) throw new Error('本地文件丢失，请重新选择：' + f.path)
    let offset = done[f.fileId] || 0
    while (offset < f.size) {
      if (paused) return
      const chunkSize = created.chunkSize || props.limits.chunkSize || 8 * 1024 * 1024
      const end = Math.min(f.size, offset + chunkSize)
      const blob = src.slice(offset, end)
      const hash = await sha256Hex(await blob.arrayBuffer())
      let res
      try {
        res = await api.uploadChunk(uploadId.value, f.fileId, offset, blob, hash,
          (loaded) => {
            const base = Object.entries(done)
              .filter(([id]) => id !== f.fileId)
              .reduce((a, [, v]) => a + v, 0)
            progress.value = {
              uploadedBytes: Math.min(total, base + offset + loaded),
              totalBytes: total,
            }
            samples.push({ t: Date.now(), bytes: progress.value.uploadedBytes })
            if (samples.length > MAX_SAMPLES) samples.shift()
            const s = speedEta(samples, total)
            speed.value = s.speed
            eta.value = s.eta
          },
          (xhr) => { currentXhr = xhr })
      } catch (e) {
        if (e.aborted) return
        if (e.status === 409 && typeof e.actualOffset === 'number') {
          offset = e.actualOffset
          continue
        }
        throw e
      } finally {
        currentXhr = null
      }
      offset = res?.uploadedBytes ?? end
      done[f.fileId] = offset
      progress.value = {
        uploadedBytes: Object.values(done).reduce((a, b) => a + b, 0),
        totalBytes: total,
      }
    }
  }
  await finishUpload()
}

async function finishUpload() {
  state.value = 'extracting'
  logLines.value = []
  steps.value = []
  await api.completeUpload(uploadId.value)
  await watch()
}

async function watch() {
  abort?.abort()
  abort = new AbortController()
  let run = { lines: [], steps: [], state: 'RUNNING' }
  try {
    await api.uploadEvents(uploadId.value, getWriteToken(), (evt, data) => {
      if (evt === 'state' && data === 'done') return
      run = mergeUploadEvent(run, evt, data)
      logLines.value = run.lines
      steps.value = run.steps
    }, abort.signal)
  } catch (e) {
    if (e.name !== 'AbortError') {
      // 流断了不影响最终状态：下面统一查一次快照
      logLines.value = [...logLines.value, '日志流中断：' + e.message]
    }
  }
  await settle()
}

async function settle() {
  try {
    const st = await api.uploadStatus(uploadId.value)
    if (st.state === 'READY') {
      uploadedCard.value = { gameId: st.gameId, name: st.name, candidate: st.result }
      state.value = 'ready'
      localStorage.removeItem(STORE_KEY)
      pending.value = null
      if (st.result) emit('ready', { candidate: st.result, uploadId: st.uploadId, name: st.name })
      return
    }
    if (st.state === 'FAILED') {
      state.value = 'failed'
      error.value = st.error || '解压失败'
      return
    }
    if (st.state === 'CANCELLED') {
      reset()
      return
    }
    state.value = 'extracting'
    // 任务还在跑：订阅流
    await watch()
  } catch (e) {
    state.value = 'failed'
    error.value = e.message
  }
}

function pause() {
  paused = true
  currentXhr?.abort()
  state.value = 'paused'
}

async function resume() {
  if (busy.value) return
  paused = false
  state.value = 'uploading'
  resetSamples()
  try {
    const st = await api.uploadStatus(uploadId.value)
    await uploadAll({
      uploadId: st.uploadId,
      chunkSize: props.limits.chunkSize || 8388608,
      files: st.files.map((f) => ({ fileId: f.fileId, path: f.path, size: f.size })),
    }, st.files)
  } catch (e) {
    if (e.aborted) {
      state.value = 'paused'
      return
    }
    state.value = 'failed'
    error.value = e.message
  }
}

async function cancel() {
  if (!window.confirm('取消上传只会删除服务器上的临时文件，已上传完成的游戏目录不受影响。继续？')) {
    return
  }
  paused = true
  currentXhr?.abort()
  try {
    if (uploadId.value) await api.cancelUpload(uploadId.value)
  } catch (e) {
    notify.error('取消失败：' + e.message)
  }
  reset()
}

function reset() {
  abort?.abort()
  abort = null
  uploadId.value = ''
  state.value = 'idle'
  error.value = ''
  progress.value = { uploadedBytes: 0, totalBytes: 0 }
  logLines.value = []
  steps.value = []
  uploadedCard.value = null
  resetSamples()
  forget()
}

function openPicker() {
  if (tab.value === 'archive') archiveInput.value?.click()
  else folderInput.value?.click()
}

function emitReady() {
  if (!uploadedCard.value?.candidate) return
  emit('ready', {
    candidate: uploadedCard.value.candidate,
    uploadId: uploadId.value,
    name: uploadedCard.value.name || name.value,
  })
}

onMounted(async () => {
  const p = saved()
  if (!p?.uploadId) return
  try {
    const st = await api.uploadStatus(p.uploadId)
    if (st.state === 'READY') {
      uploadedCard.value = { gameId: st.gameId, name: st.name, candidate: st.result }
      state.value = 'ready'
      pending.value = p
      uploadId.value = p.uploadId
      if (st.result && props.autoResume) {
        emit('ready', { candidate: st.result, uploadId: st.uploadId, name: st.name })
      }
      return
    }
    if (st.state === 'CANCELLED') {
      forget()
      return
    }
    pending.value = p
    uploadId.value = p.uploadId
    name.value = p.name || name.value
    state.value = st.state === 'FAILED' ? 'failed' : 'paused'
    error.value = st.error || ''
    if (st.state === 'COMPLETING' || st.state === 'EXTRACTING') {
      state.value = 'extracting'
      watch()
    }
  } catch {
    forget()
  }
})

onBeforeUnmount(() => {
  paused = true
  currentXhr?.abort()
  abort?.abort()
})
</script>

<template>
  <div class="upload-panel">
    <div class="upload-tabs">
      <button
        class="btn sm"
        :class="{ primary: tab === 'archive' }"
        :disabled="busy"
        @click="tab = 'archive'"
      >上传压缩包</button>
      <button
        class="btn sm"
        :class="{ primary: tab === 'folder' }"
        :disabled="busy || !folderSupported"
        @click="tab = 'folder'"
      >上传文件夹</button>
    </div>
    <p v-if="!folderSupported" class="upload-info">
      当前浏览器不支持选择整个文件夹，请使用压缩包上传。
    </p>

    <label class="upload-name">
      显示名
      <input v-model="name" maxlength="120" :disabled="busy" placeholder="游戏显示名" />
    </label>

    <div
      class="upload-drop"
      @dragover.prevent
      @drop.prevent="onDrop"
    >
      <input
        ref="archiveInput"
        v-show="tab === 'archive'"
        type="file"
        accept=".zip,.7z"
        :disabled="busy || !supported"
        @change="onArchiveChange"
      />
      <input
        ref="folderInput"
        v-show="tab === 'folder'"
        type="file"
        webkitdirectory
        multiple
        :disabled="busy || !supported"
        @change="onFolderChange"
      />
      <button class="btn sm" :disabled="busy || !supported" @click="openPicker">
        选择{{ tab === 'archive' ? '压缩包（.zip / .7z）' : '文件夹' }}
      </button>
      <span class="upload-drop-hint">或直接拖入这里</span>
    </div>

    <p v-if="!supported" class="import-error">
      当前平台不支持上传接入：只能在 Linux 服务器上使用。
    </p>
    <p v-else-if="info" class="upload-info">{{ info }}</p>
    <p v-else-if="check.reason && selection" class="import-error">{{ check.reason }}</p>

    <div v-if="progress.totalBytes > 0" class="upload-progress">
      <div class="upload-bar"><span :style="{ width: percent + '%' }" /></div>
      <div class="upload-meta">{{ percentText }} · {{ speedText }} · {{ etaText }}</div>
    </div>

    <p v-if="error" class="import-error">{{ error }}</p>

    <div class="upload-actions">
      <button
        v-if="!busy"
        class="btn primary sm"
        :disabled="!check.ok || !supported"
        @click="start"
      >{{ uploadId && (state === 'paused' || state === 'failed') ? '继续上传' : '开始上传' }}</button>
      <button v-if="state === 'uploading'" class="btn sm" @click="pause">暂停</button>
      <button v-if="state === 'paused'" class="btn sm" :disabled="!check.ok" @click="resume">继续</button>
      <button v-if="state !== 'idle'" class="btn sm" @click="cancel">取消上传</button>
    </div>

    <ol v-if="steps.length" class="import-steps">
      <li v-for="s in steps" :key="s.id" :class="'state-' + String(s.state).toLowerCase()">
        {{ s.title }} — {{ s.state }}
      </li>
    </ol>
    <pre v-if="logLines.length" class="import-log">{{ logLines.join('\n') }}</pre>

    <div v-if="uploadedCard" class="upload-done">
      已上传到 <code>games/{{ uploadedCard.gameId }}</code>
      <button class="btn sm" @click="emitReady">继续配置</button>
    </div>

    <p v-if="pending && state !== 'uploading' && !uploadedCard" class="upload-info">
      发现未完成上传「{{ pending.name }}」：重新选择同一文件后可继续。
    </p>
  </div>
</template>
