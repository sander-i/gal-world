/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { api, inMaintenanceWindow, isMaintenanceError, onMaintenance } from '../src/api.js'

test('网关 5xx 与网络中断都转成维护事件，而不是业务报错', async () => {
  let hits = 0
  const off = onMaintenance(() => { hits += 1 })

  globalThis.fetch = async () => ({
    ok: false,
    status: 503,
    statusText: 'Service Unavailable',
    headers: new Map(),
  })
  await assert.rejects(() => api.listGames(), (e) => isMaintenanceError(e))
  assert.equal(hits, 1, '503 应触发维护事件')

  globalThis.fetch = async () => { throw new TypeError('fetch failed') }
  await assert.rejects(() => api.hero(), (e) => isMaintenanceError(e))
  assert.equal(hits, 2, '网络中断应触发维护事件')
  assert.equal(inMaintenanceWindow(), true, '刚发生维护后应处于维护窗口内')
  assert.equal(inMaintenanceWindow(0), false, '窗口长度为 0 时不生效')

  off()
})

test('检查更新遇到 502 不广播维护事件（Release 源故障不是门户维护）', async () => {
  let hits = 0
  const off = onMaintenance(() => { hits += 1 })

  // 后端在拉不到 Release 时返回 502，同时带 message
  globalThis.fetch = async () => ({
    ok: false,
    status: 502,
    statusText: 'Bad Gateway',
    json: async () => ({ message: '拉取 Release 失败' }),
  })
  const r = await api.checkUpdateQuiet()
  assert.equal(r.ok, false)
  assert.equal(r.status, 502)
  assert.equal(r.data.message, '拉取 Release 失败')
  assert.equal(hits, 0, '502 不应触发维护事件')

  // 网络中断同样只返回结果
  globalThis.fetch = async () => { throw new TypeError('fetch failed') }
  const r2 = await api.checkUpdateQuiet()
  assert.equal(r2.ok, false)
  assert.equal(r2.status, 0)
  assert.equal(hits, 0, '网络中断不应触发维护事件')

  // 正常返回时透传数据
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    statusText: 'OK',
    json: async () => ({ hasUpdate: true, latestVersion: 'v0.1.3' }),
  })
  const r3 = await api.checkUpdateQuiet()
  assert.equal(r3.ok, true)
  assert.equal(r3.data.hasUpdate, true)

  off()
})
