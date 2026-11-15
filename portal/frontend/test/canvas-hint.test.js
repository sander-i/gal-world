/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { recommendedCanvas } from '../src/canvas-hint.js'

test('窗口加 24 后按 8 取整', () => {
  assert.deepEqual(recommendedCanvas(968, 593), { width: 992, height: 624 })
})

test('窗口未知时返回 null', () => {
  assert.equal(recommendedCanvas(0, 593), null)
  assert.equal(recommendedCanvas(null, null), null)
})

test('推荐值限制在 640×480 到 3840×2160', () => {
  assert.deepEqual(recommendedCanvas(100, 100), { width: 640, height: 480 })
  assert.deepEqual(recommendedCanvas(3830, 2140), { width: 3840, height: 2160 })
})
