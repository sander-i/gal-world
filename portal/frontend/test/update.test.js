/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  canApply, canOverride, confirmText, isStaleStatus, overrideConfirmText, phaseLabel, updateMessage,
} from '../src/update.js'

test('confirmText strips v prefix and pre-release suffix', () => {
  assert.equal(confirmText('v0.1.1'), 'UPDATE 0.1.1')
  // 后端 Version.normalize 也会截掉 - 之后的部分，前端必须一致，否则确认文本对不上
  assert.equal(confirmText('v0.1.4-pr7'), 'UPDATE 0.1.4')
})

test('canApply requires update and idle status', () => {
  assert.equal(canApply({ hasUpdate: true }, { phase: 'idle' }), true)
  assert.equal(canApply({ hasUpdate: false }, { phase: 'idle' }), false)
})

test('canApply blocks a fresh active phase', () => {
  const now = new Date().toISOString()
  assert.equal(
    canApply({ hasUpdate: true },
      { phase: 'downloading', startedAt: now, phaseStartedAt: now }),
    false,
  )
})

test('canApply allows a stale active phase', () => {
  const old = new Date(Date.now() - 11 * 60 * 1000).toISOString()
  assert.equal(
    canApply({ hasUpdate: true },
      { phase: 'queued', startedAt: old, phaseStartedAt: old }),
    true,
  )
})

test('canApply allows active phase without usable timestamps', () => {
  assert.equal(canApply({ hasUpdate: true }, { phase: 'queued' }), true)
  assert.equal(
    canApply({ hasUpdate: true }, { phase: 'queued', phaseStartedAt: 'not-a-date' }),
    true,
  )
})

test('isStaleStatus prefers phaseStartedAt over startedAt', () => {
  const old = new Date(Date.now() - 60 * 60 * 1000).toISOString()
  const fresh = new Date().toISOString()
  assert.equal(isStaleStatus({ startedAt: old, phaseStartedAt: fresh }), false)
  assert.equal(isStaleStatus({ startedAt: old }), true)
})

test('phaseLabel maps known phases', () => {
  assert.equal(phaseLabel('downloading'), '下载更新包')
  assert.equal(phaseLabel('success'), '更新完成')
})

test('overrideConfirmText 原样保留输入', () => {
  assert.equal(overrideConfirmText('v0.1.4-pr7'), 'OVERRIDE v0.1.4-pr7')
  assert.equal(overrideConfirmText('  0.1.3 '), 'OVERRIDE 0.1.3')
  assert.equal(overrideConfirmText(''), 'OVERRIDE ')
})

test('canOverride 需要开关、非空输入与空闲状态', () => {
  assert.equal(canOverride(true, 'v0.1.3', { phase: 'idle' }), true)
  assert.equal(canOverride(false, 'v0.1.3', { phase: 'idle' }), false)
  assert.equal(canOverride(true, '   ', { phase: 'idle' }), false)
  const now = new Date().toISOString()
  assert.equal(
    canOverride(true, 'v0.1.3', { phase: 'downloading', phaseStartedAt: now }),
    false,
  )
})

test('updateMessage 重排更新器写入的固定句式', () => {
  assert.equal(updateMessage({ message: '更新完成 0.1.3 -> 0.1.4' }), '更新完成 0.1.3 -> 0.1.4')
  assert.equal(updateMessage({ message: '已回滚到 0.1.3' }), '已回滚到 0.1.3')
  assert.equal(updateMessage({ message: '当前 0.1.3，目标 0.1.4' }), '当前 0.1.3，目标 0.1.4')
})

test('updateMessage 认不出的原因原样保留，空值返回空串', () => {
  assert.equal(updateMessage({ message: 'jar 大小不匹配' }), 'jar 大小不匹配')
  assert.equal(updateMessage({}), '')
  assert.equal(updateMessage(null), '')
  assert.equal(updateMessage({ message: '   ' }), '')
})
