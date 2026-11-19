/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { BUILTIN_THEMES, DEFAULT_THEME_ID } from '../src/theme.js'

/*
 * index.html 的内联防闪脚本在 localStorage 无主题时写死了一份 paper 的骨架变量，
 * 与 theme.js 的 paper 种子重复。两边必须同步，否则改了默认主题后首屏会闪旧色。
 */
const INLINE_HTML = readFileSync(new URL('../index.html', import.meta.url), 'utf8')

const MISMATCH = 'index.html 内联默认主题与 theme.js 不一致：'
  + '改 DEFAULT_THEME_ID 或 paper 种子色后，必须同步更新 index.html 防闪脚本里的 '
  + "`var d = { ... }`、`root.dataset.theme = 'paper'` 与 `root.dataset.themeBase = 'light'`"

/** 从内联脚本里解析 `var d = { ... }` 的键值；值都是简单字符串，单双引号都兼容 */
function parseInlineVars(html) {
  const block = html.match(/var\s+d\s*=\s*\{([\s\S]*?)\}/)
  assert.ok(block, 'index.html 里找不到内联默认主题定义 `var d = { ... }`')
  const vars = {}
  for (const m of block[1].matchAll(/['"](--[\w-]+)['"]\s*:\s*(['"])([\s\S]*?)\2/g)) {
    vars[m[1]] = m[3]
  }
  return vars
}

const inlineVars = parseInlineVars(INLINE_HTML)
const paper = BUILTIN_THEMES.find((theme) => theme.id === 'paper')

test('DEFAULT_THEME_ID 为 paper，且内置主题里有 paper', () => {
  assert.equal(DEFAULT_THEME_ID, 'paper', MISMATCH)
  assert.ok(paper, MISMATCH)
})

test('index.html 内联默认主题恰好包含 7 个骨架变量', () => {
  assert.deepEqual(
    Object.keys(inlineVars).sort(),
    ['--bg', '--line', '--line-strong', '--surface', '--surface-2', '--surface-hover', '--text'],
    MISMATCH,
  )
})

test('index.html 内联变量与 paper 主题逐项相等', () => {
  // --surface-hover 不在 deriveVars 里（只在 styles.css 的 :root 由公式定义），故跳过比对
  for (const key of ['--bg', '--surface', '--line', '--text', '--surface-2', '--line-strong']) {
    assert.equal(inlineVars[key], paper.vars[key], `${MISMATCH}\n变量：${key}`)
  }
})

test('index.html 内联脚本写死 paper / light，防止换默认主题时漏改', () => {
  assert.match(INLINE_HTML, /root\.dataset\.theme\s*=\s*'paper'/, MISMATCH)
  assert.match(INLINE_HTML, /root\.dataset\.themeBase\s*=\s*'light'/, MISMATCH)
})
