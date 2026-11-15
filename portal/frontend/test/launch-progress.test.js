/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { LAUNCH_STEPS, LAUNCH_LABELS, launchPercent, startVerdict, stopPercent }
  from '../src/launch-progress.js'

test('阶段顺序与中文标签齐全', () => {
  assert.deepEqual(LAUNCH_STEPS, ['start', 'stream', 'done'])
  for (const s of LAUNCH_STEPS) assert.equal(typeof LAUNCH_LABELS[s], 'string')
})

test('launchPercent 在区间内单调不减', () => {
  for (const s of ['start', 'stream']) {
    let prev = -1
    for (const ms of [0, 100, 1000, 5000, 999999]) {
      const v = launchPercent(s, ms, 5000)
      assert.ok(v >= 0 && v <= 100)
      assert.ok(v >= prev, `${s} 在 ${ms}ms 回退了`)
      prev = v
    }
  }
})

test('阶段跑完到达该阶段上限，done 固定 100', () => {
  assert.equal(launchPercent('start', 5000, 5000), 55)
  assert.equal(launchPercent('stream', 5000, 5000), 95)
  assert.equal(launchPercent('done', 0, 1), 100)
})

test('未知阶段返回 0，非法耗时按 0 处理', () => {
  assert.equal(launchPercent('nope', 1000, 1000), 0)
  assert.equal(launchPercent('start', 1000, 0), 8)
})

test('stopPercent 从 5 推进到 95，且单调不减', () => {
  assert.equal(stopPercent(0, 8000), 5)
  assert.equal(stopPercent(8000, 8000), 95)
  let prev = -1
  for (const ms of [0, 100, 1000, 8000, 999999]) {
    const v = stopPercent(ms, 8000)
    assert.ok(v >= prev)
    prev = v
  }
  assert.equal(stopPercent(1000, 0), 5)
})

test('startVerdict：FAILED 或 crashed 立刻判失败', () => {
  assert.equal(startVerdict({ state: 'FAILED' }, 0, 5000), 'fail')
  assert.equal(startVerdict({ state: 'RUNNING', crashed: true }, 0, 5000), 'fail')
})

test('startVerdict：宽限期内 STOPPED 继续等，过了宽限期判失败', () => {
  assert.equal(startVerdict({ state: 'STOPPED' }, 1000, 5000), 'wait')
  assert.equal(startVerdict({ state: 'STOPPED' }, 5001, 5000), 'fail')
})

test('startVerdict：RUNNING 与 UNKNOWN 都继续等', () => {
  for (const state of ['RUNNING', 'UNKNOWN']) {
    assert.equal(startVerdict({ state }, 60_000, 5000), 'wait')
  }
  assert.equal(startVerdict(null, 1000, 5000), 'wait')
})