/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeRepoBase, normalizeTagVersion, releasesUrl, tagUrl } from '../src/repo-links.js'

test('仓库地址为空时不生成链接', () => {
  for (const base of ['', '   ', null, undefined]) {
    assert.equal(releasesUrl(base), '')
    assert.equal(tagUrl(base, '0.0.1'), '')
  }
})

test('去掉尾部斜杠，避免拼出双斜杠', () => {
  assert.equal(normalizeRepoBase('https://gitee.com/sander-o/gal-world///'), 'https://gitee.com/sander-o/gal-world')
  assert.equal(releasesUrl('https://gitee.com/sander-o/gal-world/'), 'https://gitee.com/sander-o/gal-world/releases')
})

test('Gitee 与 GitHub 用 releases 路径', () => {
  assert.equal(releasesUrl('https://gitee.com/sander-o/gal-world'), 'https://gitee.com/sander-o/gal-world/releases')
  assert.equal(releasesUrl('https://github.com/o/r'), 'https://github.com/o/r/releases')
  assert.equal(tagUrl('https://gitee.com/sander-o/gal-world', '0.0.1'),
    'https://gitee.com/sander-o/gal-world/releases/tag/v0.0.1')
  assert.equal(tagUrl('https://github.com/o/r', '1.2.3'),
    'https://github.com/o/r/releases/tag/v1.2.3')
})

test('GitLab 用 /-/ 前缀路径', () => {
  assert.equal(releasesUrl('https://gitlab.com/o/r'), 'https://gitlab.com/o/r/-/releases')
  assert.equal(releasesUrl('https://gitlab.example.com/o/r'), 'https://gitlab.example.com/o/r/-/releases')
  assert.equal(tagUrl('https://gitlab.com/o/r', '0.0.1'), 'https://gitlab.com/o/r/-/tags/v0.0.1')
})

test('版本号去 v 前缀与 SNAPSHOT 后缀', () => {
  assert.equal(normalizeTagVersion('0.0.1'), '0.0.1')
  assert.equal(normalizeTagVersion('v0.0.1'), '0.0.1')
  assert.equal(normalizeTagVersion('V1.2.3'), '1.2.3')
  assert.equal(normalizeTagVersion('0.0.1-SNAPSHOT'), '0.0.1')
  assert.equal(tagUrl('https://gitee.com/o/r', '0.0.1-SNAPSHOT'), 'https://gitee.com/o/r/releases/tag/v0.0.1')
})

test('版本号不可用时退回发布页', () => {
  assert.equal(normalizeTagVersion('dev'), '')
  assert.equal(normalizeTagVersion(''), '')
  assert.equal(normalizeTagVersion(null), '')
  assert.equal(tagUrl('https://gitee.com/o/r', 'dev'), 'https://gitee.com/o/r/releases')
  assert.equal(tagUrl('https://gitlab.com/o/r', ''), 'https://gitlab.com/o/r/-/releases')
})
