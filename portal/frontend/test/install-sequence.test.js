/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { installSequence, runInstallSteps } from '../src/install-sequence.js'

const steps = [
  { id: 'lin.preflight', requires: [], uninstall: false, state: 'NOT_READY' },
  { id: 'lin.packages', requires: ['lin.preflight'], uninstall: false, state: 'READY' },
  { id: 'lin.snd-aloop', requires: ['lin.preflight'], uninstall: false, state: 'BLOCKED' },
  { id: 'lin.python-venv', requires: ['lin.packages'], uninstall: false, state: 'NOT_READY' },
  { id: 'lin.selkies', requires: ['lin.python-venv'], uninstall: false, state: 'BLOCKED' },
  { id: 'lin.selfcheck', requires: ['lin.packages', 'lin.selkies'], uninstall: false, state: 'BLOCKED' },
  { id: 'lin.uninstall', requires: [], uninstall: true, state: 'NOT_READY' },
]

/** 返回 id -> 下标，便于断言先后关系 */
function indexMap(seq) {
  return new Map(seq.map((id, i) => [id, i]))
}

test('依赖永远排在依赖它的步骤之前', () => {
  const seq = installSequence(steps)
  const at = indexMap(seq)
  assert.equal(seq.length, 6)
  assert.ok(at.get('lin.preflight') < at.get('lin.packages'))
  assert.ok(at.get('lin.preflight') < at.get('lin.snd-aloop'))
  assert.ok(at.get('lin.packages') < at.get('lin.python-venv'))
  assert.ok(at.get('lin.python-venv') < at.get('lin.selkies'))
  assert.ok(at.get('lin.selkies') < at.get('lin.selfcheck'))
})

test('卸载步骤不进入一键安装', () => {
  assert.ok(!installSequence(steps).includes('lin.uninstall'))
})

test('同层保持步骤表原有顺序', () => {
  const seq = installSequence(steps)
  const at = indexMap(seq)
  assert.ok(at.get('lin.snd-aloop') < at.get('lin.python-venv'))
})

test('依赖指向未知 id 时忽略，不影响整轮顺序', () => {
  const seq = installSequence([
    { id: 'a', requires: ['win.not-on-this-os'], uninstall: false },
    { id: 'b', requires: ['a'], uninstall: false },
  ])
  assert.deepEqual(seq, ['a', 'b'])
})

test('依赖成环时不会排完全部步骤', () => {
  const seq = installSequence([
    { id: 'a', requires: ['b'], uninstall: false },
    { id: 'b', requires: ['a'], uninstall: false },
  ])
  assert.ok(seq.length < 2)
})

test('非数组输入返回空数组', () => {
  assert.deepEqual(installSequence(null), [])
  assert.deepEqual(installSequence(undefined), [])
})

test('执行时跳过已就绪，失败即停', async () => {
  const ran = []
  const ok = await runInstallSteps(['lin.preflight', 'lin.packages', 'lin.snd-aloop'], steps, async (s) => {
    ran.push(s.id)
    return s.id !== 'lin.snd-aloop'
  })
  assert.deepEqual(ran, ['lin.preflight', 'lin.snd-aloop'], 'READY 的 packages 必须跳过')
  assert.deepEqual(ok, { ok: false, failed: 'lin.snd-aloop', reason: 'failed' })
})

test('快照里查不到的步骤按失败处理', async () => {
  const res = await runInstallSteps(['lin.not-exist'], steps, async () => true)
  assert.deepEqual(res, { ok: false, failed: 'lin.not-exist', reason: 'missing' })
})
