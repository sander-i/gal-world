/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  BUILTIN_THEMES, deriveVars, parseStoredTheme, resolveTheme, THEME_STORAGE_KEY,
} from '../src/theme.js'

test('内置主题包含四套且 id 唯一', () => {
  assert.equal(BUILTIN_THEMES.length, 4)
  assert.equal(new Set(BUILTIN_THEMES.map((t) => t.id)).size, 4)
  assert.deepEqual(BUILTIN_THEMES.map((t) => t.base).sort(), ['dark', 'dark', 'dark', 'light'])
})

test('未知 id 回退到默认主题 paper', () => {
  assert.equal(resolveTheme('nope', []).id, 'paper')
})

test('自定义主题按 id 命中', () => {
  const custom = { id: 'custom-1', name: '我的', base: 'dark', vars: {} }
  assert.equal(resolveTheme('custom-1', [custom]).name, '我的')
})

test('deriveVars 由 9 个种子色产生完整变量且全部引用种子', () => {
  const vars = deriveVars({
    bg: '#101010', surface: '#181818', line: '#282828', text: '#eeeeee',
    muted: '#888888', accent: '#cc8844', ok: '#66bb88', warn: '#cc8855', err: '#cc6666',
  })
  for (const k of ['--bg', '--surface', '--surface-2', '--line', '--text', '--muted',
    '--accent', '--accent-hi', '--accent-ink', '--ok', '--shadow', '--cover-gradient']) {
    assert.ok(vars[k], `缺少 ${k}`)
  }
  assert.equal(vars['--bg'], '#101010')
  assert.match(vars['--accent-hi'], /color-mix|#/)
})

test('parseStoredTheme 接受合法 JSON 并拒绝坏数据', () => {
  assert.equal(parseStoredTheme('not-json'), null)
  assert.equal(parseStoredTheme('{"id":"x"}'), null)
  const ok = parseStoredTheme(JSON.stringify(
    { id: 'custom-1', name: 'n', base: 'dark', vars: { '--bg': '#000' } }))
  assert.equal(ok.id, 'custom-1')
  assert.equal(THEME_STORAGE_KEY, 'galworld.theme')
})
