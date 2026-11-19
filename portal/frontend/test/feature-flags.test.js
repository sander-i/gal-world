/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  applySettings,
  companyEnabled,
  localizationEnabled,
  loadSettings,
  primeSettings,
  invalidateSettings,
} from '../src/feature-flags.js'

test('设置缺省时公司/汉化组都启用', () => {
  applySettings({})
  assert.equal(companyEnabled.value, true)
  assert.equal(localizationEnabled.value, true)
})

test('显式 false 时对应维度停用', () => {
  applySettings({ companyEnabled: 'false', localizationGroupEnabled: 'false' })
  assert.equal(companyEnabled.value, false)
  assert.equal(localizationEnabled.value, false)
})

test('只有 true 才启用的值都视为启用', () => {
  applySettings({ companyEnabled: 'true', localizationGroupEnabled: 'true' })
  assert.equal(companyEnabled.value, true)
  assert.equal(localizationEnabled.value, true)
})

// 以下用例只走不依赖网络的路径：feature-flags.js 静态 import 了 api 模块，
// 无法在测试里替换它，所以不验证真实请求，只验证缓存语义。

test('primeSettings 后 loadSettings 返回同一对象，不再发请求', async () => {
  const saved = { companyEnabled: 'false', localizationGroupEnabled: 'false' }
  primeSettings(saved)

  const first = loadSettings()
  const second = loadSettings()
  assert.equal(first, second, '两次 loadSettings 应返回同一个 Promise')
  assert.equal(await first, saved, '应直接返回 prime 进去的对象，说明没有重新请求')
  assert.equal(companyEnabled.value, false, 'prime 同时应立即应用开关')
})

test('invalidateSettings 已导出且调用不抛错', () => {
  assert.equal(typeof invalidateSettings, 'function')
  assert.doesNotThrow(() => invalidateSettings())
})
