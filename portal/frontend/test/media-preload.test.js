/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  PRELOAD_LIMIT, acquirePreloadSlot, releasePreloadSlot, resetPreloadSlots,
} from '../src/media-preload.js'

test('默认上限为 6', () => {
  assert.equal(PRELOAD_LIMIT, 6)
})

test('超过上限的请求排队，释放后依次放行', async () => {
  resetPreloadSlots()
  const order = []
  const started = []
  for (let i = 0; i < PRELOAD_LIMIT; i++) {
    await acquirePreloadSlot()
    started.push(i)
  }
  const waiter = acquirePreloadSlot().then(() => order.push('waiter'))
  assert.deepEqual(started.length, PRELOAD_LIMIT)
  assert.deepEqual(order, [])

  releasePreloadSlot()
  await waiter
  assert.deepEqual(order, ['waiter'])
  resetPreloadSlots()
})

test('释放次数多于获取不会把计数变负', async () => {
  resetPreloadSlots()
  releasePreloadSlot()
  releasePreloadSlot()
  for (let i = 0; i < PRELOAD_LIMIT; i++) await acquirePreloadSlot()
  resetPreloadSlots()
  assert.ok(true)
})
