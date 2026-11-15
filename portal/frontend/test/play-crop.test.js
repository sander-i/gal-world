/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { cropPlan } from '../src/play-crop.js'

const box = { width: 1600, height: 800 }

test('没有窗口信息时按画布比例铺满（旧行为）', () => {
  const p = cropPlan(box, { width: 1280, height: 720 }, null)
  assert.equal(p.viewW, 1422)
  assert.equal(p.viewH, 800)
  assert.equal(p.frameW, 1422)
  assert.equal(p.frameH, 800)
  assert.equal(p.left, 0)
  assert.equal(p.top, 0)
})

test('窗口小于画布时放大并按窗口位置位移', () => {
  // 画布 1280x720，窗口 968x593 居中于 (156,63)
  const p = cropPlan(box, { width: 1280, height: 720 },
    { x: 156, y: 63, width: 968, height: 593 })
  // 按高度贴合：800 / 593 = 1.3491 倍 -> 视口宽 1305
  assert.equal(p.viewH, 800)
  assert.equal(p.viewW, 1305)
  assert.equal(p.frameW, 1726)
  assert.equal(p.frameH, 971)
  assert.equal(p.left, -210)
  assert.equal(p.top, -85)
})

test('窗口等于画布时是恒等变换', () => {
  const p = cropPlan(box, { width: 1280, height: 720 },
    { x: 0, y: 0, width: 1280, height: 720 })
  assert.equal(p.viewW, p.frameW)
  assert.equal(p.viewH, p.frameH)
  assert.equal(p.left, 0)
  assert.equal(p.top, 0)
})

test('窗口宽高为 0 时退回旧行为', () => {
  const p = cropPlan(box, { width: 1280, height: 720 }, { x: 0, y: 0, width: 0, height: 0 })
  assert.equal(p.viewW, 1422)
  assert.equal(p.left, 0)
})
