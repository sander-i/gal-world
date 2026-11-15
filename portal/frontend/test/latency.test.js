/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { latencyTone } from '../src/latency.js'

test('时延分档：60ms 内良好，150ms 内一般，其余偏差', () => {
  assert.equal(latencyTone(1), 'good')
  assert.equal(latencyTone(60), 'good')
  assert.equal(latencyTone(61), 'warn')
  assert.equal(latencyTone(150), 'warn')
  assert.equal(latencyTone(151), 'bad')
})

test('无时延时不返回分档', () => {
  assert.equal(latencyTone(null), '')
  assert.equal(latencyTone(undefined), '')
})