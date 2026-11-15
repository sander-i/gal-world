/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  aggregateProgress,
  canStartUpload,
  fingerprintArchive,
  fingerprintFolder,
  fingerprintSelection,
  folderRootName,
  isArchiveName,
  mergeUploadEvent,
  planChunks,
  sha256FallbackHex,
  sha256Hex,
  speedEta,
  uploadPercent,
} from '../src/game-upload.js'

test('uploadPercent 按已传字节计算百分比', () => {
  assert.equal(uploadPercent(0, 100), 0)
  assert.equal(uploadPercent(52, 97), 54)
  assert.equal(uploadPercent(97, 97), 100)
})

test('uploadPercent 缺字段、总量为 0 或超量时安全', () => {
  assert.equal(uploadPercent(10, 0), 0)
  assert.equal(uploadPercent(undefined, undefined), 0)
  assert.equal(uploadPercent(-5, 100), 0)
  assert.equal(uploadPercent(200, 100), 100)
})

test('planChunks 整片与尾片', () => {
  assert.deepEqual(planChunks({ size: 16 }, 8), [
    { offset: 0, length: 8 },
    { offset: 8, length: 8 },
  ])
  assert.deepEqual(planChunks({ size: 10 }, 4), [
    { offset: 0, length: 4 },
    { offset: 4, length: 4 },
    { offset: 8, length: 2 },
  ])
})

test('planChunks 空文件与非法块大小返回空', () => {
  assert.deepEqual(planChunks({ size: 0 }, 8), [])
  assert.deepEqual(planChunks({ size: 10 }, 0), [])
  assert.deepEqual(planChunks(null, 8), [])
})

test('压缩包指纹包含名称/大小/时间', () => {
  const fp = fingerprintArchive({ name: 'game.zip', size: 10, lastModified: 1699 })
  assert.equal(fp, 'archive:game.zip:10:1699')
  assert.notEqual(fp, fingerprintArchive({ name: 'game.zip', size: 11, lastModified: 1699 }))
})

test('文件夹指纹与枚举顺序无关', () => {
  const a = [
    { webkitRelativePath: 'game/b.exe', size: 2 },
    { webkitRelativePath: 'game/a.exe', size: 1 },
  ]
  const b = [...a].reverse()
  assert.equal(fingerprintFolder(a), fingerprintFolder(b))
  assert.equal(folderRootName(a), 'game')
  assert.match(fingerprintFolder(a), /^folder:game:2:3:game\/a\.exe:game\/b\.exe$/)
})

test('fingerprintSelection 按来源分派', () => {
  assert.equal(
    fingerprintSelection({ kind: 'ARCHIVE', file: { name: 'a.7z', size: 1, lastModified: 2 } }),
    'archive:a.7z:1:2',
  )
  assert.match(
    fingerprintSelection({ kind: 'FOLDER', files: [{ webkitRelativePath: 'g/x', size: 1 }] }),
    /^folder:g:1:1:/,
  )
  assert.equal(fingerprintSelection(null), '')
})

test('aggregateProgress 汇总已完成与当前分片', () => {
  const p = aggregateProgress(
    [{ size: 10, uploadedBytes: 4 }, { size: 6, uploadedBytes: 0 }],
    { uploaded: 3 },
  )
  assert.deepEqual(p, { uploadedBytes: 7, totalBytes: 16, ratio: 7 / 16 })
  assert.equal(aggregateProgress([], {}).ratio, 0)
})

test('speedEta 计算速度与剩余', () => {
  const r = speedEta([
    { t: 0, bytes: 0 },
    { t: 1000, bytes: 1000 },
  ], 4000)
  assert.equal(r.speed, 1000)
  assert.equal(r.eta, 3)
  assert.deepEqual(speedEta([{ t: 0, bytes: 0 }], 100), { speed: 0, eta: null })
  assert.deepEqual(speedEta([], 100), { speed: 0, eta: null })
})

test('mergeUploadEvent 归并日志、步骤与终态', () => {
  let s = { lines: [], steps: [], state: 'RUNNING' }
  s = mergeUploadEvent(s, 'log', '第一行')
  assert.deepEqual(s.lines, ['第一行'])
  s = mergeUploadEvent(s, 'step', '{"id":"extract","state":"RUNNING","title":"解压"}')
  assert.deepEqual(s.steps, [{ id: 'extract', state: 'RUNNING', title: '解压' }])
  s = mergeUploadEvent(s, 'step', '{"id":"extract","state":"SUCCESS","title":"解压"}')
  assert.equal(s.steps.length, 1)
  assert.equal(s.steps[0].state, 'SUCCESS')
  s = mergeUploadEvent(s, 'state', 'done')
  assert.equal(s.state, 'SUCCESS')
  // 非法 JSON 不破坏状态
  assert.deepEqual(mergeUploadEvent(s, 'step', 'not json'), s)
})

test('isArchiveName 只接受 zip/7z', () => {
  assert.equal(isArchiveName('a.ZIP'), true)
  assert.equal(isArchiveName('a.7z'), true)
  assert.equal(isArchiveName('a.rar'), false)
  assert.equal(isArchiveName(''), false)
})

test('canStartUpload 压缩包预检', () => {
  const limits = { maxUploadBytes: 100, maxFileBytes: 50, maxFiles: 10 }
  const ok = canStartUpload({ name: '游戏' }, { kind: 'ARCHIVE', file: { name: 'a.zip', size: 40 } }, limits)
  assert.equal(ok.ok, true)
  assert.equal(ok.totalBytes, 40)

  assert.equal(canStartUpload({ name: '' }, { kind: 'ARCHIVE', file: { name: 'a.zip', size: 1 } }, limits).ok, false)
  assert.equal(canStartUpload({ name: 'x' }, { kind: 'ARCHIVE', file: { name: 'a.rar', size: 1 } }, limits).ok, false)
  assert.equal(canStartUpload({ name: 'x' }, { kind: 'ARCHIVE', file: { name: 'a.zip', size: 0 } }, limits).ok, false)
  assert.equal(canStartUpload({ name: 'x' }, { kind: 'ARCHIVE', file: { name: 'a.zip', size: 60 } }, limits).ok, false)
  assert.equal(canStartUpload({ name: 'x' }, null, limits).ok, false)
})

test('canStartUpload 文件夹预检', () => {
  const limits = { maxUploadBytes: 100, maxFileBytes: 50, maxFiles: 3 }
  const files = [
    { name: 'a.exe', size: 10 },
    { name: 'b.exe', size: 20 },
  ]
  const ok = canStartUpload({ name: '游戏' }, { kind: 'FOLDER', files }, limits)
  assert.equal(ok.ok, true)
  assert.equal(ok.totalBytes, 30)
  assert.equal(ok.count, 2)

  assert.equal(canStartUpload({ name: 'x' }, {
    kind: 'FOLDER', files: [{ name: 'a', size: 60 }],
  }, limits).ok, false)
  assert.equal(canStartUpload({ name: 'x' }, {
    kind: 'FOLDER', files: new Array(4).fill({ name: 'a', size: 1 }),
  }, limits).ok, false)
  assert.equal(canStartUpload({ name: 'x' }, { kind: 'FOLDER', files: [] }, limits).ok, false)
})

test('sha256Hex 输出标准十六进制', async () => {
  const buf = new TextEncoder().encode('abc').buffer
  assert.equal(
    await sha256Hex(buf),
    'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
  )
})

test('非安全上下文兜底实现与 WebCrypto 结果一致', async () => {
  for (const text of ['', 'abc', '中文分片内容'.repeat(37)]) {
    const bytes = new TextEncoder().encode(text)
    assert.equal(
      sha256FallbackHex(bytes),
      await sha256Hex(bytes.buffer.slice(0)),
      `内容长度 ${bytes.length} 的哈希应一致`,
    )
  }
})
