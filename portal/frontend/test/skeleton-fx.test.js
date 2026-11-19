/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { groupRows } from '../src/skeleton-fx.js'

test('同一视觉行的 3 个块得到同一行号', () => {
  assert.deepEqual(groupRows([100, 100, 100]), [0, 0, 0])
})

test('顶边相差 6px 以内算同一行', () => {
  assert.deepEqual(groupRows([100, 106]), [0, 0])
  assert.deepEqual(groupRows([100, 94]), [0, 0])
})

test('相差 7px 不算同一行', () => {
  assert.deepEqual(groupRows([100, 107]), [0, 1])
})

test('行号按出现顺序递增', () => {
  assert.deepEqual(groupRows([10, 60, 110]), [0, 1, 2])
})

test('不连续出现的同行元素合并到同一行', () => {
  // 第 2、4 块在同一行（顶边都是 60），中间夹了别的行
  assert.deepEqual(groupRows([10, 60, 110, 62]), [0, 1, 2, 1])
})

test('空数组返回空数组', () => {
  assert.deepEqual(groupRows([]), [])
})
