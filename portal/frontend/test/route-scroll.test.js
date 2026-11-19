/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { scrollBehavior, takeSavedScroll, __resetForTests } from '../src/route-scroll.js'

// pending 是模块级状态，用例之间必须清掉，否则上一条的 savedPosition 会漏进来
test.beforeEach(() => __resetForTests())

test('savedPosition 原样返回，takeSavedScroll 取到 top 且只取一次', () => {
  const saved = { top: 640, left: 0 }
  assert.equal(scrollBehavior({ path: '/games' }, { path: '/game/3' }, saved), saved)
  assert.equal(takeSavedScroll('/games'), 640)
  // 取过一次即消耗：后续无关导航不能误用旧位置
  assert.equal(takeSavedScroll('/games'), null)
})

test('path 不匹配时 take 返回 null，记录保留给真正的目标页', () => {
  // 当前实现语义：不匹配只返回 null、不消耗记录。
  // 若将来改成「取即清」，本用例会失败，需同步确认调用方不会先问错路径。
  scrollBehavior({ path: '/games' }, { path: '/game/3' }, { top: 420 })
  assert.equal(takeSavedScroll('/other'), null)
  assert.equal(takeSavedScroll('/games'), 420)
})

test('同 path 只改 query 返回 false，且不产生待恢复记录', () => {
  assert.equal(scrollBehavior({ path: '/games' }, { path: '/games' }, null), false)
  assert.equal(takeSavedScroll('/games'), null)
})

test('普通跨页跳转返回 { top: 0 }，且不产生待恢复记录', () => {
  assert.deepEqual(scrollBehavior({ path: '/game/9' }, { path: '/games' }, null), { top: 0 })
  assert.equal(takeSavedScroll('/game/9'), null)
})

test('普通导航会清空上一次残留的待恢复位置', () => {
  scrollBehavior({ path: '/games' }, { path: '/game/3' }, { top: 900 })
  // 模拟一次普通 push：旧记录必须清掉，避免之后进入 /games 时滚到旧位置
  scrollBehavior({ path: '/settings' }, { path: '/games' }, null)
  assert.equal(takeSavedScroll('/games'), null)
})

test('savedPosition 缺 top 时按 0 记录', () => {
  scrollBehavior({ path: '/games' }, { path: '/game/3' }, { left: 0 })
  assert.equal(takeSavedScroll('/games'), 0)
})
