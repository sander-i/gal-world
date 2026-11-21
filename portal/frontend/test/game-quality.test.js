/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { qualityPatch, qualityForm } from '../src/game-quality.js'

test('全部留空时不下发任何键（门户不管理）', () => {
  assert.deepEqual(qualityPatch({
    videoCrf: '', videoPaintoverCrf: '', rateControlMode: '', videoBitrate: '', keyframeInterval: '',
  }), {})
})

test('填了值的项才进补丁，其余项不出现', () => {
  const p = qualityPatch({
    videoCrf: '10', videoPaintoverCrf: '', rateControlMode: 'cbr', videoBitrate: ' 8000 ', keyframeInterval: '30',
  })
  assert.deepEqual(p, {
    videoCrf: 10, rateControlMode: 'cbr', videoBitrate: '8000', keyframeInterval: 30,
  })
  assert.equal('videoPaintoverCrf' in p, false)
})

test('关键帧 0 有效（无限 GOP），不能当空值丢掉', () => {
  const p = qualityPatch({
    videoCrf: '', videoPaintoverCrf: '', rateControlMode: '', videoBitrate: '', keyframeInterval: '0',
  })
  assert.deepEqual(p, { keyframeInterval: 0 })
})

test('小数取整，非数字当空值', () => {
  const p = qualityPatch({
    videoCrf: '10.6', videoPaintoverCrf: 'abc', rateControlMode: '', videoBitrate: '   ', keyframeInterval: '',
  })
  assert.deepEqual(p, { videoCrf: 11 })
})

test('后端 null 字段显示成空字符串，0 保持可见', () => {
  assert.deepEqual(qualityForm({
    videoCrf: null, videoPaintoverCrf: 8, rateControlMode: null, videoBitrate: null, keyframeInterval: 0,
  }), {
    videoCrf: '', videoPaintoverCrf: '8', rateControlMode: '', videoBitrate: '', keyframeInterval: '0',
  })
})

test('qualityForm 容忍缺字段的对象', () => {
  assert.deepEqual(qualityForm({}), {
    videoCrf: '', videoPaintoverCrf: '', rateControlMode: '', videoBitrate: '', keyframeInterval: '',
  })
  assert.deepEqual(qualityForm(null), {
    videoCrf: '', videoPaintoverCrf: '', rateControlMode: '', videoBitrate: '', keyframeInterval: '',
  })
})

test('qualityForm 再经 qualityPatch 往返不丢信息', () => {
  const src = { videoCrf: 12, videoPaintoverCrf: 8, rateControlMode: 'crf', videoBitrate: '40000,5000-80000', keyframeInterval: 60 }
  assert.deepEqual(qualityPatch(qualityForm(src)), src)
})
