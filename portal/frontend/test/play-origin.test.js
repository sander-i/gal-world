/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { playLink, playBackTarget } from '../src/play-origin.js'

test('无来源的游玩链接不带参数，返回首页', () => {
  assert.equal(playLink('demo-game'), '/play/demo-game')
  const back = playBackTarget({ query: {}, params: { id: 'demo-game' } })
  assert.equal(back.path, '/')
  assert.equal(back.label, '← 游戏库')
})

test('从列表页进入：链接带筛选，返回列表并还原', () => {
  const link = playLink('demo-game', 'list', {
    tag: '汉化', company: 3, q: 'abc', density: 'compact',
  })
  assert.match(link, /^\/play\/demo-game\?/)
  const q = Object.fromEntries(new URL(link, 'http://x').searchParams)
  assert.equal(q.from, 'list')
  assert.equal(q.tag, '汉化')
  assert.equal(q.company, '3')
  const back = playBackTarget({ query: q, params: { id: 'demo-game' } })
  assert.equal(back.path, '/games')
  assert.deepEqual(back.query, { tag: '汉化', company: '3', q: 'abc', density: 'compact' })
  assert.equal(back.label, '← 全部游戏')
})

test('从详情页进入：返回该游戏详情', () => {
  assert.equal(playLink('demo-game', 'game'), '/play/demo-game?from=game')
  const back = playBackTarget({ query: { from: 'game' }, params: { id: 'demo-game' } })
  assert.equal(back.path, '/game/demo-game')
  assert.equal(back.label, '← 游戏详情')
})

test('从接入页进入：返回接入页', () => {
  assert.equal(playLink('demo-game', 'import'), '/play/demo-game?from=import')
  const back = playBackTarget({ query: { from: 'import' }, params: { id: 'demo-game' } })
  assert.equal(back.path, '/games/import')
  assert.equal(back.label, '← 接入游戏')
})