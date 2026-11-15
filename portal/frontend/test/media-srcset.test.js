/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { srcsetOf } from '../src/media-srcset.js'

const item = {
  original: '/media/a.png?v=1',
  variants: [{ w: 640, url: '/media/a.png.s640?v=1' }],
}

test('普通模式按派生图生成 srcset', () => {
  assert.equal(srcsetOf(item), '/media/a.png.s640?v=1 640w')
})

test('originalOnly 模式不生成 srcset，浏览器只能加载原图', () => {
  assert.equal(srcsetOf(item, true), undefined)
})

test('没有派生图时返回 undefined', () => {
  assert.equal(srcsetOf({ original: '/media/a.png', variants: [] }), undefined)
  assert.equal(srcsetOf(null), undefined)
})
