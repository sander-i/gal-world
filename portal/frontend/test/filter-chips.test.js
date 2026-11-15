/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { rankFilterItems } from '../src/filter-chips.js'

test('隐藏没有关联游戏的项', () => {
  const out = rankFilterItems([
    { id: 1, count: 0 },
    { id: 2, count: 3 },
    { id: 3 },
  ])
  assert.deepEqual(out.map((x) => x.id), [2])
})

test('按关联游戏数倒序', () => {
  const out = rankFilterItems([
    { id: 1, count: 2 },
    { id: 2, count: 9 },
    { id: 3, count: 5 },
  ])
  assert.deepEqual(out.map((x) => x.id), [2, 3, 1])
})

test('数量相同保持原有顺序', () => {
  const out = rankFilterItems([
    { id: 7, count: 1 },
    { id: 8, count: 1 },
    { id: 9, count: 1 },
  ])
  assert.deepEqual(out.map((x) => x.id), [7, 8, 9])
})

test('当前选中项即使为 0 也保留', () => {
  const out = rankFilterItems(
    [{ id: 1, count: 0 }, { id: 2, count: 4 }],
    (item) => item.id === 1,
  )
  assert.deepEqual(out.map((x) => x.id), [2, 1])
})

test('空列表与缺 count 字段不报错', () => {
  assert.deepEqual(rankFilterItems([]), [])
  assert.deepEqual(rankFilterItems([{ id: 1 }]), [])
})
