/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { api } from '../src/api.js'

globalThis.localStorage = {
  getItem: () => null,
  setItem: () => {},
  removeItem: () => {},
}

test('创建公司提交名称、别名、网址、简介四个字段', async () => {
  let body = null
  globalThis.fetch = async (_url, init) => {
    body = JSON.parse(init.body)
    return { ok: true, status: 200, json: async () => ({ id: 1 }), headers: new Map() }
  }
  await api.createCompany('Key', 'KEY', 'key.example.com', '老牌公司')
  assert.deepEqual(body, {
    name: 'Key', alias: 'KEY', website: 'key.example.com', description: '老牌公司',
    // 没传多语言覆盖时显式给 null：后端据此判断「不要改动已有覆盖」
    i18n: null,
  })
})

test('创建公司带上多语言覆盖', async () => {
  let body = null
  globalThis.fetch = async (_url, init) => {
    body = JSON.parse(init.body)
    return { ok: true, status: 200, json: async () => ({ id: 1 }), headers: new Map() }
  }
  await api.createCompany('ゆずソフト', '', 'yuzu-soft.com', '', { en: { name: 'Yuzusoft' } })
  assert.deepEqual(body.i18n, { en: { name: 'Yuzusoft' } })
})

test('更新公司时缺省字段提交空串', async () => {
  let body = null
  globalThis.fetch = async (_url, init) => {
    body = JSON.parse(init.body)
    return { ok: true, status: 200, json: async () => ({ id: 1 }), headers: new Map() }
  }
  await api.updateCompany(1, 'Key')
  assert.deepEqual(body, { name: 'Key', alias: '', website: '', description: '', i18n: null })
})
