/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { begin, bindProgress, done, routeProgress, __resetForTests } from '../src/route-progress.js'

// 每个用例前恢复初始状态。mock timers 在用例内部才 enable，
// 所以这里的 clearTimeout/clearInterval 碰到的是上一个用例遗留的普通数值 id，无害。
test.beforeEach(() => __resetForTests())

test('快速导航（80ms 内完成）进度条从不出现', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] })
  begin()
  t.mock.timers.tick(50)
  done()
  assert.equal(routeProgress.active, false)
})

test('慢导航：100ms 时可见且已推进，done 后到 100%，220ms 后隐藏归零', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] })
  begin()
  t.mock.timers.tick(100)
  assert.equal(routeProgress.active, true)
  assert.ok(routeProgress.value > 8, `进度应已推进，实际 ${routeProgress.value}`)
  done()
  assert.equal(routeProgress.value, 100)
  t.mock.timers.tick(220)
  assert.equal(routeProgress.active, false)
  assert.equal(routeProgress.value, 0)
})

test('淡出窗口内再次 begin：保持可见且宽度不回跳', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] })
  begin()
  t.mock.timers.tick(100)
  assert.equal(routeProgress.active, true)
  done() // value = 100，进入 220ms 淡出窗口
  t.mock.timers.tick(100) // 未满 220ms，仍处于可见状态
  assert.equal(routeProgress.active, true)
  begin()
  // 关键断言：begin 不得把可见的 100% 重置回 8%
  assert.equal(routeProgress.active, true)
  assert.ok(routeProgress.value > 8, `宽度不应回跳到 8%，实际 ${routeProgress.value}`)
})

test('bindProgress 注入后所有写入都落在注入对象上', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] })
  // 用 Proxy 记录 set trap：Vue 的 reactive 正是靠 set trap 通知组件，
  // 若模块绕过注入目标直接写原始对象，这个用例会失败，浏览器里进度条也会不动
  const target = { active: false, value: 0 }
  const traps = []
  const injected = new Proxy(target, {
    set(obj, key, val) {
      traps.push(String(key))
      obj[key] = val
      return true
    },
  })
  bindProgress(injected)
  begin()
  t.mock.timers.tick(100)
  assert.equal(target.active, true)
  assert.ok(target.value > 8, `进度应已推进，实际 ${target.value}`)
  done()
  assert.equal(target.value, 100)
  assert.ok(traps.includes('active') && traps.includes('value'))
  // 原始导出对象保持初始值：模块不再绕过注入目标直接写它
  assert.equal(routeProgress.active, false)
  assert.equal(routeProgress.value, 0)
})
