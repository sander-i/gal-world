/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { uninstallSequence, runUninstallSteps } from '../src/uninstall-sequence.js'

const steps = [
  { id: 'lin.packages', uninstall: false, state: 'READY' },
  { id: 'lin.uninstall', uninstall: true, state: 'NOT_READY' },
  { id: 'lin.uninstall-admin', uninstall: true, state: 'BLOCKED' },
  { id: 'lin.selfcheck', state: 'READY' },
]

test('只挑卸载步骤并保持后端顺序', () => {
  assert.deepEqual(uninstallSequence(steps), ['lin.uninstall', 'lin.uninstall-admin'])
})

test('已清理的步骤跳过', () => {
  const done = steps.map((s) => (s.id === 'lin.uninstall' ? { ...s, state: 'READY' } : s))
  assert.deepEqual(uninstallSequence(done), ['lin.uninstall-admin'])
})

test('非数组输入返回空数组', () => {
  assert.deepEqual(uninstallSequence(null), [])
  assert.deepEqual(uninstallSequence(undefined), [])
})

test('步骤查不到视为失败，不能静默跳过', async () => {
  const result = await runUninstallSteps(
    ['lin.uninstall', 'lin.uninstall-admin'],
    [{ id: 'lin.uninstall', state: 'NOT_READY' }],
    async () => true,
  )
  assert.deepEqual(result, { ok: false, failed: 'lin.uninstall-admin', reason: 'missing' })
})

test('某步失败后不再执行后续步骤', async () => {
  const ran = []
  const result = await runUninstallSteps(
    ['a', 'b'],
    [{ id: 'a', state: 'NOT_READY' }, { id: 'b', state: 'NOT_READY' }],
    async (s) => { ran.push(s.id); return s.id !== 'a' },
  )
  assert.deepEqual(result, { ok: false, failed: 'a', reason: 'failed' })
  assert.deepEqual(ran, ['a'])
})

test('已就绪的步骤跳过，其余全部成功', async () => {
  const ran = []
  const result = await runUninstallSteps(
    ['a', 'b'],
    [{ id: 'a', state: 'READY' }, { id: 'b', state: 'NOT_READY' }],
    async (s) => { ran.push(s.id); return true },
  )
  assert.deepEqual(result, { ok: true })
  assert.deepEqual(ran, ['b'])
})
