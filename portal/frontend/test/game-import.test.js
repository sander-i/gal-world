/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { canSubmit, runPhase, mergeEvent, formatBytes, stepOf, altOptions } from '../src/game-import.js'

test('canSubmit 要求名称、exe 且分辨率合法', () => {
  assert.equal(canSubmit({ name: '标题', exe: 'game.exe', width: 1280, height: 720 }), true)
  assert.equal(canSubmit({ name: '', exe: 'game.exe', width: 1280, height: 720 }), false)
  assert.equal(canSubmit({ name: '标题', exe: '', width: 1280, height: 720 }), false)
  assert.equal(canSubmit({ name: '标题', exe: 'game.exe', width: 100, height: 720 }), false)
})

test('runPhase 映射 run 状态', () => {
  assert.equal(runPhase('QUEUED'), 'running')
  assert.equal(runPhase('RUNNING'), 'running')
  assert.equal(runPhase('SUCCESS'), 'done')
  assert.equal(runPhase('FAILED'), 'failed')
  assert.equal(runPhase('CANCELLED'), 'failed')
})

test('mergeEvent 归并 log / step / done', () => {
  let acc = { lines: [], steps: [], state: 'RUNNING' }
  acc = mergeEvent(acc, 'log', 'hello')
  assert.deepEqual(acc.lines, ['hello'])

  acc = mergeEvent(acc, 'step', JSON.stringify({ id: 'wineboot', state: 'RUNNING', title: '初始化' }))
  assert.equal(stepOf(acc.steps, 'wineboot').state, 'RUNNING')

  acc = mergeEvent(acc, 'step', JSON.stringify({ id: 'wineboot', state: 'SUCCESS', title: '初始化' }))
  assert.equal(stepOf(acc.steps, 'wineboot').state, 'SUCCESS')
  assert.equal(acc.steps.length, 1)

  acc = mergeEvent(acc, 'state', 'done')
  assert.equal(acc.state, 'SUCCESS')
})

test('mergeEvent 忽略坏 JSON，不抛异常', () => {
  const acc = mergeEvent({ lines: [], steps: [], state: 'RUNNING' }, 'step', '{bad')
  assert.deepEqual(acc.steps, [])
})

test('altOptions 排除当前主用项', () => {
  const exes = [{ path: 'a.exe', sizeBytes: 1 }, { path: 'b.exe', sizeBytes: 2 }]
  assert.deepEqual(altOptions(exes, 'a.exe').map((e) => e.path), ['b.exe'])
  assert.deepEqual(altOptions(undefined, 'a.exe'), [])
})

test('formatBytes 人类可读', () => {
  assert.equal(formatBytes(0), '0 B')
  assert.equal(formatBytes(1536), '1.5 KB')
  assert.equal(formatBytes(5 * 1024 ** 3), '5.0 GB')
})
