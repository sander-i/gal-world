/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { filterOrgs, isSelected, toggleOrg } from '../src/org-select.js'

const key = { id: 1, name: 'まどそふと', alias: 'Madosoft', website: 'https://madosoft.net/' }
const other = { id: 2, name: 'Key', alias: 'Key社', website: '' }

test('按名称或别名过滤，忽略大小写', () => {
  assert.deepEqual(filterOrgs([key, other], 'key'), [other])
  assert.deepEqual(filterOrgs([key, other], 'MADO'), [key])
  assert.deepEqual(filterOrgs([key, other], '  '), [key, other])
})

test('中文名称可直接搜索', () => {
  assert.deepEqual(filterOrgs([key, other], 'まど'), [key])
})

test('按 id 判断已选，无 id 时按名称+别名+网址', () => {
  assert.equal(isSelected([{ ...key }], key), true)
  assert.equal(isSelected([{ id: null, name: 'Key', alias: 'Key社', website: '' }], other), true)
  assert.equal(isSelected([], key), false)
})

test('切换选中：未选则加入，已选则移除', () => {
  const added = toggleOrg([], key)
  assert.equal(added.length, 1)
  assert.equal(added[0].name, 'まどそふと')
  const removed = toggleOrg(added, key)
  assert.deepEqual(removed, [])
})

import { findExactMatch, resolveEnterAction, sortByMaintenanceOrder } from '../src/org-select.js'

const a = { id: 1, name: 'Key', alias: 'Key社', website: '' }
const b = { id: 2, name: 'Key社', alias: '', website: '' }
const c = { id: 3, name: 'leaf', alias: '', website: '' }

test('按维护顺序重排已选项，未在字典中的项保留相对顺序并放末尾', () => {
  const fresh = { id: null, name: '新公司', alias: '', website: '' }
  const picked = [fresh, c, a]
  const sorted = sortByMaintenanceOrder(picked, [a, b, c])
  assert.deepEqual(sorted.map((x) => x.name), ['Key', 'leaf', '新公司'])
})

test('重置顺序不修改原数组', () => {
  const picked = [c, a]
  sortByMaintenanceOrder(picked, [a, b, c])
  assert.deepEqual(picked.map((x) => x.name), ['leaf', 'Key'])
})

test('findExactMatch 名称或别名都算精确命中', () => {
  assert.equal(findExactMatch([a, b], ' key '), a)
  assert.equal(findExactMatch([a, b], 'KEY社'), a)
  assert.equal(findExactMatch([a, b], 'key社'), a)
  assert.equal(findExactMatch([a], 'Key 社'), null)
  assert.equal(findExactMatch([a], ''), null)
})

test('resolveEnterAction：精确命中只选中，不新建', () => {
  assert.deepEqual(resolveEnterAction([a, b], 'Key社', true), { kind: 'select', org: a })
  assert.deepEqual(resolveEnterAction([a], 'key', true), { kind: 'select', org: a })
})

test('resolveEnterAction：唯一近似匹配选中', () => {
  assert.deepEqual(resolveEnterAction([c], 'le', true), { kind: 'select', org: c })
})

test('resolveEnterAction：多个近似匹配不新建也不选中', () => {
  assert.equal(resolveEnterAction([a, b], 'ke', true), null)
})

test('resolveEnterAction：无匹配且允许新建时给出创建动作', () => {
  assert.deepEqual(resolveEnterAction([], '  新公司 ', true), { kind: 'create', name: '新公司' })
})

test('resolveEnterAction：不允许新建时无匹配返回 null', () => {
  assert.equal(resolveEnterAction([], '新公司', false), null)
})

test('resolveEnterAction：空白查询返回 null', () => {
  assert.equal(resolveEnterAction([], '   ', true), null)
})
