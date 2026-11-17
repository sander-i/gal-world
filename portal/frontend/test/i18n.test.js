/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  formatMessage, loadI18n, normalizeLocale, setLocale, t,
} from '../src/i18n.js'

/** 用假的 fetch 喂语言接口；packs 形如 { en: {...}, ja: {...} } */
function stubApi(packs, defaultLocale = 'en') {
  const calls = []
  globalThis.fetch = async (url) => {
    calls.push(String(url))
    const m = /\/api\/i18n\/(.+)$/.exec(String(url))
    if (!m) {
      return { ok: true, json: async () => ({
        defaultLocale,
        languages: Object.keys(packs).map((id) => ({ id, name: id })),
      }) }
    }
    const id = decodeURIComponent(m[1])
    if (!packs[id]) return { ok: false, status: 404, statusText: 'Not Found' }
    return { ok: true, json: async () => ({ messages: packs[id] }) }
  }
  globalThis.location = { search: '' }
  globalThis.localStorage = { getItem: () => null, setItem: () => {} }
  // node 里 navigator 是只读 getter，要覆盖得走 defineProperty
  Object.defineProperty(globalThis, 'navigator', {
    value: { languages: ['en'], language: 'en' }, configurable: true, writable: true,
  })
  globalThis.document = { documentElement: {}, title: '' }
  return calls
}

test('normalizeLocale 覆盖简繁与常见变体', () => {
  assert.equal(normalizeLocale('zh-hans'), 'zh-CN')
  assert.equal(normalizeLocale('zh_Hant_TW'), 'zh-TW')
  assert.equal(normalizeLocale('en-US'), 'en')
  assert.equal(normalizeLocale('ja-JP'), 'ja')
  assert.equal(normalizeLocale(''), '')
  assert.equal(normalizeLocale(null), '')
  assert.equal(normalizeLocale('ko'), 'ko')
  assert.equal(normalizeLocale('pt-br'), 'pt-BR')
})

test('formatMessage 按位置替换', () => {
  assert.equal(formatMessage('{0} 分 {1} 秒', [3, 2]), '3 分 2 秒')
  assert.equal(formatMessage('{0} 个文件', [7]), '7 个文件')
})

test('formatMessage 缺参数时保留占位符', () => {
  assert.equal(formatMessage('{0} / {1}', ['a']), 'a / {1}')
})

test('formatMessage 支持具名参数', () => {
  assert.equal(formatMessage('{name} 已删除', { name: 'Key' }), 'Key 已删除')
})

test('formatMessage 无参数原样返回', () => {
  assert.equal(formatMessage('设置', undefined), '设置')
  assert.equal(formatMessage('', []), '')
})

test('兜底链：缺键回退中文原文，不回退英文（与后端一致）', async () => {
  stubApi({
    en: { '保存': 'Save', '设置': 'Settings' },
    ja: { '保存': '保存する' },
  })
  await loadI18n()
  assert.equal(t('保存'), 'Save')
  await setLocale('ja')
  // 当前语言有的键用当前语言
  assert.equal(t('保存'), '保存する')
  // ja 缺的键回退中文原文，不是英文——便于一眼看出哪些还没翻译
  assert.equal(t('设置'), '设置')
  // 谁都没有的键同样回退键名
  assert.equal(t('不存在的键'), '不存在的键')
})

test('整包拉不到时退到站点默认语言', async () => {
  const packs = { en: { '保存': 'Save' }, ja: { '保存': '保存する' } }
  stubApi(packs)
  await loadI18n()
  // 让 ja 的包请求 404，模拟外部语言包被删
  delete packs.ja
  await setLocale('ja')
  assert.equal(t('保存'), 'Save')
})
