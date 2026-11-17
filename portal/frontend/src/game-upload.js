/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 游戏上传的纯逻辑：分片规划、指纹、进度聚合、SSE 归并、前端预检。
 *
 * 全部是纯函数，便于 node --test 直接覆盖；DOM / XHR 留在组件与 api.js。
 */

import { t } from './i18n.js'

export const DEFAULT_CHUNK_SIZE = 8 * 1024 * 1024

export function isArchiveName(name) {
  const n = String(name || '').toLowerCase()
  return n.endsWith('.zip') || n.endsWith('.7z')
}

/** 文件 → 分片列表；空文件返回空数组（调用方按错误处理） */
export function planChunks(file, chunkSize = DEFAULT_CHUNK_SIZE) {
  const size = Number(file?.size) || 0
  const step = Number(chunkSize) || 0
  if (size <= 0 || step <= 0) return []
  const out = []
  for (let offset = 0; offset < size; offset += step) {
    out.push({ offset, length: Math.min(step, size - offset) })
  }
  return out
}

export function folderRootName(files) {
  const first = (files || [])[0]
  if (!first) return ''
  const p = first.webkitRelativePath || first.name || ''
  return p.split('/')[0] || ''
}

/** 压缩包指纹：文件名 + 大小 + 修改时间 */
export function fingerprintArchive(file) {
  const name = file?.name || ''
  return `archive:${name}:${Number(file?.size) || 0}:${Number(file?.lastModified) || 0}`
}

/**
 * 文件夹指纹：根目录名 + 文件数 + 总字节 + 排序后首尾路径。
 * 浏览器枚举顺序不稳定，必须先排序再取首尾。
 */
export function fingerprintFolder(files) {
  const list = files || []
  const paths = list.map((f) => f.webkitRelativePath || f.name || '').sort()
  const total = list.reduce((sum, f) => sum + (Number(f.size) || 0), 0)
  return `folder:${folderRootName(list)}:${list.length}:${total}:${paths[0] || ''}:${paths[paths.length - 1] || ''}`
}

export function fingerprintSelection(selection) {
  if (!selection) return ''
  if (selection.kind === 'ARCHIVE') return fingerprintArchive(selection.file)
  return fingerprintFolder(selection.files)
}

/**
 * 汇总进度。
 * @param files 已完成文件的 [{size, uploadedBytes}]
 * @param current 当前在传分片的 {uploaded}
 */
export function aggregateProgress(files, current) {
  const list = files || []
  const totalBytes = list.reduce((sum, f) => sum + (Number(f.size) || 0), 0)
  const doneBytes = list.reduce((sum, f) => sum + (Number(f.uploadedBytes) || 0), 0)
  const inflight = Math.max(0, Number(current?.uploaded) || 0)
  const uploadedBytes = Math.min(totalBytes, doneBytes + inflight)
  return {
    uploadedBytes,
    totalBytes,
    ratio: totalBytes > 0 ? uploadedBytes / totalBytes : 0,
  }
}

/**
 * 上传进度百分比（0–100 整数）。
 *
 * 组件只保存 {uploadedBytes, totalBytes}，不保证带 ratio 字段，
 * 所以统一由字节数换算；总量未知时返回 0。
 */
export function uploadPercent(uploadedBytes, totalBytes) {
  const total = Number(totalBytes) || 0
  if (total <= 0) return 0
  const done = Math.min(total, Math.max(0, Number(uploadedBytes) || 0))
  return Math.round((done / total) * 100)
}

/**
 * 滑动窗口速度与剩余时间。
 * @param samples [{t: 毫秒, bytes: 已传字节}]，按时间升序
 * @param totalBytes 总量
 */
export function speedEta(samples, totalBytes) {
  const list = (samples || []).filter(
    (s) => s && Number.isFinite(s.t) && Number.isFinite(s.bytes),
  )
  if (list.length < 2) return { speed: 0, eta: null }
  const first = list[0]
  const last = list[list.length - 1]
  const dt = (last.t - first.t) / 1000
  if (dt <= 0) return { speed: 0, eta: null }
  const speed = Math.max(0, (last.bytes - first.bytes) / dt)
  const remain = Math.max(0, (Number(totalBytes) || 0) - last.bytes)
  return { speed, eta: speed > 0 ? Math.round(remain / speed) : null }
}

/** SSE 事件归并；返回新对象，便于 Vue 响应式 */
export function mergeUploadEvent(acc, name, data) {
  if (name === 'log') {
    return { ...acc, lines: [...acc.lines, String(data)] }
  }
  if (name === 'step') {
    let evt
    try {
      evt = JSON.parse(data)
    } catch {
      return acc
    }
    if (!evt?.id) return acc
    const rest = (acc.steps || []).filter((s) => s.id !== evt.id)
    return {
      ...acc,
      steps: [...rest, { id: evt.id, state: evt.state, title: evt.title || evt.id }],
    }
  }
  if (name === 'state') {
    return { ...acc, state: data === 'done' ? 'SUCCESS' : String(data) }
  }
  return acc
}

/**
 * 提交前的即时预检。服务端仍是权威，这里只拦明显超限，避免白传几 GB。
 * @param form {name}
 * @param selection {kind, file} 或 {kind, files}
 * @param limits {maxUploadBytes, maxFileBytes, maxFiles, chunkSize, maxDepth}
 */
export function canStartUpload(form, selection, limits = {}) {
  const name = String(form?.name || '').trim()
  if (!name) return { ok: false, reason: t('请填写显示名') }
  if (name.length > 120) return { ok: false, reason: t('显示名不超过 120 字') }
  if (!selection || !selection.kind) return { ok: false, reason: t('请选择压缩包或文件夹') }
  const maxUpload = Number(limits?.maxUploadBytes) || Infinity
  const maxFile = Number(limits?.maxFileBytes) || Infinity
  const maxFiles = Number(limits?.maxFiles) || Infinity

  if (selection.kind === 'ARCHIVE') {
    const file = selection.file
    if (!file) return { ok: false, reason: t('请选择压缩包') }
    if (!isArchiveName(file.name)) return { ok: false, reason: t('只支持 .zip / .7z') }
    const size = Number(file.size) || 0
    if (size <= 0) return { ok: false, reason: t('文件为空') }
    if (size > maxUpload) return { ok: false, reason: t('压缩包超过上传上限') }
    if (size > maxFile) return { ok: false, reason: t('压缩包超过单文件上限') }
    return { ok: true, reason: '', totalBytes: size, count: 1 }
  }

  if (selection.kind === 'FOLDER') {
    const files = selection.files || []
    if (!files.length) return { ok: false, reason: t('请选择文件夹') }
    if (files.length > maxFiles) return { ok: false, reason: t("文件数超过上限 {0}", maxFiles) }
    let total = 0
    for (const f of files) {
      const size = Number(f.size) || 0
      if (size > maxFile) return { ok: false, reason: t("单个文件超过上限：{0}", f.name) }
      total += size
    }
    if (total <= 0) return { ok: false, reason: t('文件夹为空') }
    if (total > maxUpload) return { ok: false, reason: t('文件总量超过上传上限') }
    return { ok: true, reason: '', totalBytes: total, count: files.length }
  }

  return { ok: false, reason: t('未知的上传来源') }
}

/**
 * 计算分片 SHA-256（十六进制）。
 *
 * crypto.subtle 只在安全上下文（HTTPS / localhost）可用；门户常用 LAN 的
 * 明文 HTTP 打开，此时直接调用会得到 undefined。这里带一份纯 JS 实现兜底，
 * 两种路径结果一致（test/game-upload.test.js 有对拍）。
 */
export async function sha256Hex(buffer) {
  const subtle = globalThis.crypto?.subtle
  if (subtle) {
    const digest = await subtle.digest('SHA-256', buffer)
    return toHex(new Uint8Array(digest))
  }
  return sha256FallbackHex(new Uint8Array(buffer))
}

function toHex(bytes) {
  let out = ''
  for (const b of bytes) out += b.toString(16).padStart(2, '0')
  return out
}

const SHA256_K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]

function rotr(x, n) {
  return (x >>> n) | (x << (32 - n))
}

/** 明文 HTTP（非安全上下文）下的兜底实现 */
export function sha256FallbackHex(bytes) {
  const h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]
  const len = bytes.length
  const padded = new Uint8Array(Math.ceil((len + 9) / 64) * 64)
  padded.set(bytes)
  padded[len] = 0x80
  const dv = new DataView(padded.buffer)
  // 长度按 64 位大端写入；分片只有几 MiB，高 32 位为 0
  dv.setUint32(padded.length - 8, Math.floor(len / 0x20000000))
  dv.setUint32(padded.length - 4, (len * 8) >>> 0)
  const w = new Uint32Array(64)

  for (let off = 0; off < padded.length; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = dv.getUint32(off + i * 4)
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3)
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10)
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0
    }
    let [a, b, c, d, e, f, g, hh] = h
    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
      const ch = (e & f) ^ (~e & g)
      const t1 = (hh + S1 + ch + SHA256_K[i] + w[i]) >>> 0
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
      const maj = (a & b) ^ (a & c) ^ (b & c)
      const t2 = (S0 + maj) >>> 0
      hh = g
      g = f
      f = e
      e = (d + t1) >>> 0
      d = c
      c = b
      b = a
      a = (t1 + t2) >>> 0
    }
    h[0] = (h[0] + a) >>> 0
    h[1] = (h[1] + b) >>> 0
    h[2] = (h[2] + c) >>> 0
    h[3] = (h[3] + d) >>> 0
    h[4] = (h[4] + e) >>> 0
    h[5] = (h[5] + f) >>> 0
    h[6] = (h[6] + g) >>> 0
    h[7] = (h[7] + hh) >>> 0
  }
  return h.map((x) => x.toString(16).padStart(8, '0')).join('')
}
