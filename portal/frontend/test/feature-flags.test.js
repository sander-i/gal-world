/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { applySettings, companyEnabled, localizationEnabled } from '../src/feature-flags.js'

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
