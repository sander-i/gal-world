/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { PHASES, PHASE_LABELS, bootPercent, nextPercent, viewForSession,
  interruptionFor, bootDecision, isCleanExit } from '../src/play-boot.js'

test('阶段顺序与中文标签齐全', () => {
  assert.deepEqual(PHASES, ['prepare', 'start', 'stream', 'player', 'frame'])
  for (const p of PHASES) assert.equal(typeof PHASE_LABELS[p], 'string')
})

test('bootPercent 在阶段区间内且随时间单调不减', () => {
  for (const p of PHASES) {
    let prev = -1
    for (const elapsed of [0, 100, 500, 2000, 999999]) {
      const v = bootPercent(p, elapsed, 4000)
      assert.ok(v >= 0 && v <= 100)
      assert.ok(v >= prev, `${p} 在 ${elapsed}ms 回退了`)
      prev = v
    }
  }
})

test('阶段跑完时到达该阶段上限，但不会越过下一阶段', () => {
  assert.equal(bootPercent('start', 4000, 4000), 35)
  assert.equal(bootPercent('stream', 8000, 8000), 70)
  assert.equal(bootPercent('frame', 10000, 10000), 100)
})

test('未知阶段返回 0，非法耗时按 0 处理', () => {
  assert.equal(bootPercent('nope', 1000, 1000), 0)
  assert.equal(bootPercent('start', 1000, 0), 10)
})

test('nextPercent 不回退', () => {
  assert.equal(nextPercent('stream', 12), 35)
  assert.equal(nextPercent('stream', 60), 60)
  assert.equal(nextPercent('nope', 42), 42)
})

test('打开页面时：未运行且未崩溃一律 not-started（不自动启动）', () => {
  assert.equal(viewForSession({ state: 'STOPPED', crashed: false }), 'not-started')
  assert.equal(viewForSession({ state: 'UNKNOWN', crashed: false }), 'not-started')
  assert.equal(viewForSession({ state: 'RUNNING', crashed: false }), 'booting')
})

test('打开页面时：FAILED 或 crashed 一律 crashed', () => {
  assert.equal(viewForSession({ state: 'FAILED', crashed: false }), 'crashed')
  assert.equal(viewForSession({ state: 'STOPPED', crashed: true }), 'crashed')
  assert.equal(viewForSession(null), 'error')
})

test('游玩轮询：空闲停止是 stopped，崩溃是 crashed', () => {
  assert.equal(interruptionFor({ state: 'STOPPED', crashed: false }), 'stopped')
  assert.equal(interruptionFor({ state: 'FAILED', crashed: false }), 'crashed')
  assert.equal(interruptionFor({ state: 'RUNNING', crashed: true }), 'crashed')
  assert.equal(interruptionFor({ state: 'RUNNING', crashed: false }), null)
})

test('游玩轮询：UNKNOWN 是 systemd 瞬时值，不中断画面', () => {
  assert.equal(interruptionFor({ state: 'UNKNOWN', crashed: false }), null)
})

test('启动判定：显式启动后的 UNKNOWN 继续等待', () => {
  assert.equal(bootDecision({ state: 'UNKNOWN', crashed: false }, true), 'wait')
  assert.equal(bootDecision({ state: 'UNKNOWN', crashed: false }, false), 'halt')
})

test('启动判定：停止即中断，异常为 crashed，运行中为 running', () => {
  assert.equal(bootDecision({ state: 'STOPPED', crashed: false }, true), 'halt')
  assert.equal(bootDecision({ state: 'FAILED', crashed: false }, false), 'crashed')
  assert.equal(bootDecision({ state: 'RUNNING', crashed: true }, true), 'crashed')
  assert.equal(bootDecision({ state: 'RUNNING', crashed: false }, false), 'running')
  assert.equal(bootDecision(null, true), 'wait')
})

test('正常退出判定：退出码 0 且无终止信号', () => {
  assert.equal(isCleanExit({ exitCode: 0, signal: null }), true)
  assert.equal(isCleanExit({ exitCode: 3, signal: null }), false)
  assert.equal(isCleanExit({ exitCode: 0, signal: 'SIGTERM(15)' }), false)
  assert.equal(isCleanExit(null), false)
})
