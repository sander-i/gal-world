/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { formatMessage, normalizeLocale } from '../src/i18n.js'

test('normalizeLocale 覆盖简繁与常见变体', () => {
  assert.equal(normalizeLocale('zh-hans'), 'zh-CN')
  assert.equal(normalizeLocale('zh_Hant_TW'), 'zh-TW')
  assert.equal(normalizeLocale('en-US'), 'en')
  assert.equal(normalizeLocale('ja-JP'), 'ja')
  assert.equal(normalizeLocale(''), '')
  assert.equal(normalizeLocale(null), '')
  assert.equal(normalizeLocale('ko'), 'ko')
  assert.equal(normalizeLocale('pt-br'), 'pt-BR')
})

test('formatMessage 按位置替换', () => {
  assert.equal(formatMessage('{0} 分 {1} 秒', [3, 2]), '3 分 2 秒')
  assert.equal(formatMessage('{0} 个文件', [7]), '7 个文件')
})

test('formatMessage 缺参数时保留占位符', () => {
  assert.equal(formatMessage('{0} / {1}', ['a']), 'a / {1}')
})

test('formatMessage 支持具名参数', () => {
  assert.equal(formatMessage('{name} 已删除', { name: 'Key' }), 'Key 已删除')
})

test('formatMessage 无参数原样返回', () => {
  assert.equal(formatMessage('设置', undefined), '设置')
  assert.equal(formatMessage('', []), '')
})
