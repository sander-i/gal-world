/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { buildSample } from '../src/play-telemetry.js'

test('内存字节换算成 MB 并保留一位小数', () => {
  const s = buildSample({ gameId: 'localove2', heapBytes: 8 * 1048576, iframeHeapBytes: 7.5 * 1048576 })
  assert.equal(s.gameId, 'localove2')
  assert.equal(s.heapMB, 8)
  assert.equal(s.iframeHeapMB, 7.5)
  assert.equal(s.event, 'sample')
})

test('非法数值变 null，长文本被截断', () => {
  const s = buildSample({ heapBytes: 'x', videoTime: 'x', message: 'a'.repeat(500) })
  assert.equal(s.heapMB, null)
  assert.equal(s.videoTime, null)
  assert.equal(s.message.length, 300)
})

test('解码与丢帧取整', () => {
  const s = buildSample({ decoded: 19462.4, dropped: 0 })
  assert.equal(s.decoded, 19462)
  assert.equal(s.dropped, 0)
})
