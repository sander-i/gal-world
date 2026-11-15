/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { backTarget, gameLink, listQuery, readListQuery } from '../src/list-origin.js'

test('首页进入的详情链接不带 from 参数', () => {
  assert.equal(gameLink('demo-game'), '/game/demo-game')
  assert.equal(gameLink('demo-game', null), '/game/demo-game')
})

test('列表页进入的详情链接带上筛选与密度', () => {
  const link = gameLink('demo-game', {
    tag: '汉化', company: 3, q: 'abc', density: 'compact',
  })
  assert.match(link, /^\/game\/demo-game\?/)
  const q = Object.fromEntries(new URL(link, 'http://x').searchParams)
  assert.equal(q.from, 'list')
  assert.equal(q.tag, '汉化')
  assert.equal(q.company, '3')
  assert.equal(q.q, 'abc')
  assert.equal(q.density, 'compact')
})

test('列表页的空筛选不写进链接', () => {
  const link = gameLink('demo-game', { tag: '', company: null, q: '', density: '' })
  const q = Object.fromEntries(new URL(link, 'http://x').searchParams)
  assert.deepEqual(q, { from: 'list' })
})

test('从列表页进详情，返回目标是列表页并还原筛选', () => {
  const link = gameLink('demo-game', {
    tag: '汉化', company: 3, q: 'abc', density: 'compact',
  })
  const query = Object.fromEntries(new URL(link, 'http://x').searchParams)
  const back = backTarget({ query })
  assert.equal(back.path, '/games')
  assert.deepEqual(back.query, { tag: '汉化', company: '3', q: 'abc', density: 'compact' })
  assert.equal(back.label, '← 全部游戏')
})

test('直接访问或从首页进入，返回目标是首页', () => {
  for (const query of [{}, { query: {} }]) {
    const back = backTarget(Array.isArray(query) ? {} : query)
    assert.equal(back.path, '/')
    assert.deepEqual(back.query, {})
    assert.equal(back.label, '← 游戏库')
  }
})

test('列表页从 URL 还原时不把非法 company 当筛选', () => {
  assert.equal(readListQuery({ query: { company: 'abc' } }).company, null)
  assert.equal(readListQuery({ query: {} }).company, null)
  assert.equal(readListQuery({ query: { company: '3' } }).company, 3)
})

test('列表页回写 URL 时省略空值', () => {
  assert.deepEqual(listQuery({ tag: '', company: null, q: '', density: '' }), {})
  assert.deepEqual(
    listQuery({ tag: '汉化', company: 3, q: 'abc', density: 'compact' }),
    { tag: '汉化', company: '3', q: 'abc', density: 'compact' })
})

test('汉化组筛选随列表链接往返', () => {
  const link = gameLink('demo-game', { localization: 9 })
  const q = Object.fromEntries(new URL(link, 'http://x').searchParams)
  assert.equal(q.localization, '9')
  assert.equal(readListQuery({ query: q }).localization, 9)
  assert.equal(listQuery({ localization: 9 }).localization, '9')
})

test('非数字的汉化组参数视为未筛选', () => {
  assert.equal(readListQuery({ query: { localization: 'abc' } }).localization, null)
})
