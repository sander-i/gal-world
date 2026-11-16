import test from 'node:test'
import assert from 'node:assert/strict'
import { api } from '../src/api.js'

globalThis.localStorage = {
  getItem: () => null,
  setItem: () => {},
  removeItem: () => {},
}

function capture() {
  const calls = []
  globalThis.fetch = async (url, init) => {
    calls.push({ url, method: init?.method || 'GET', body: init?.body })
    return { ok: true, status: 200, json: async () => ({}), headers: new Map() }
  }
  return calls
}

test('备份存档同时提交标签与描述', async () => {
  const calls = capture()
  await api.backupSaves('demo game', '第一章 结束', '走完 A 线')
  assert.deepEqual(JSON.parse(calls[0].body), { label: '第一章 结束', description: '走完 A 线' })
  assert.equal(calls[0].url, '/api/games/demo%20game/saves/backup')
})

test('切换存档走 /switch 接口，默认不自动快照', async () => {
  const calls = capture()
  await api.switchSave('demo', '20260101-010101_a.tar.gz')
  assert.equal(calls[0].method, 'POST')
  assert.equal(calls[0].url, '/api/games/demo/saves/20260101-010101_a.tar.gz/switch?snapshot=false')
})

test('切换存档可显式要求切换前快照', async () => {
  const calls = capture()
  await api.switchSave('demo', 'a.tar.gz', true)
  assert.equal(calls[0].url, '/api/games/demo/saves/a.tar.gz/switch?snapshot=true')
})

test('编辑备份信息走 PUT', async () => {
  const calls = capture()
  await api.updateSave('demo', 'a.tar.gz', '新标签', '新描述')
  assert.equal(calls[0].method, 'PUT')
  assert.deepEqual(JSON.parse(calls[0].body), { label: '新标签', description: '新描述' })
})

test('打包下载两步接口的 URL 正确且带编码', async () => {
  const calls = capture()
  await api.packageGame('demo game')
  assert.equal(calls[0].method, 'POST')
  assert.equal(calls[0].url, '/api/games/demo%20game/download/packages')
  assert.equal(
    api.downloadPackageUrl('demo game', 'dl-1'),
    '/api/games/demo%20game/download/packages/dl-1/file',
  )
})

test('刷新后按游戏找回当前打包任务', async () => {
  const calls = capture()
  await api.currentDownloadPackage('demo game')
  assert.equal(calls[0].method, 'GET')
  assert.equal(calls[0].url, '/api/games/demo%20game/download/packages/current')
})

test('打包预估走 estimate 接口', async () => {
  const calls = capture()
  await api.packageEstimate('demo game')
  assert.equal(calls[0].method, 'GET')
  assert.equal(calls[0].url, '/api/games/demo%20game/download/packages/estimate')
})
