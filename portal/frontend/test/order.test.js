/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { moveItem, idsOf } from '../src/order.js'

test('把元素从 from 移到 to', () => {
  assert.deepEqual(moveItem(['a', 'b', 'c'], 0, 2), ['b', 'c', 'a'])
  assert.deepEqual(moveItem(['a', 'b', 'c'], 2, 0), ['c', 'a', 'b'])
})

test('越界或原地不动时返回原顺序副本', () => {
  assert.deepEqual(moveItem(['a', 'b'], 0, 0), ['a', 'b'])
  assert.deepEqual(moveItem(['a', 'b'], -1, 5), ['a', 'b'])
})

test('idsOf 按当前顺序取 id', () => {
  assert.deepEqual(idsOf([{ id: 3 }, { id: 1 }]), [3, 1])
})
