/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

const BASE = ''

/** 部署接口的令牌头（与后端 DeploySecurity.HEADER 一致） */
const DEPLOY_HEADER = 'X-GalWorld-Deploy-Token'

/**
 * 写接口令牌（与后端 WriteSecurity.HEADER 一致）。
 *
 * 后端默认不启用保护；部署者配置 galworld.write.token 后，所有非 GET 的
 * /api/** 都要带这个头。前端从 localStorage 读——它属于"本机访问凭据"，
 * 与后台管理用的 sessionStorage 部署令牌分开存，互不影响。
 */
const WRITE_HEADER = 'X-GalWorld-Write-Token'
const WRITE_STORE = 'galworld.writeToken'

export function getWriteToken() {
  return localStorage.getItem(WRITE_STORE) || ''
}

export function setWriteToken(token) {
  const v = (token || '').trim()
  if (v) localStorage.setItem(WRITE_STORE, v)
  else localStorage.removeItem(WRITE_STORE)
}

/** 给写请求补上令牌头；只读请求不加，避免无谓的请求头变化 */
function writeHeaders(extra = {}) {
  const t = getWriteToken()
  return t ? { ...extra, [WRITE_HEADER]: t } : extra
}

// ---------------- 部署/维护期状态 ----------------
//
// 门户更新时会重启后端：已经打开的页面继续发请求，会拿到网络错误或
// 502/503/504。这类失败不是业务错误，不应该弹成红色报错；
// 统一转成 MaintenanceError，由 App.vue 的维护遮罩接管，恢复后自动刷新。

const MAINTENANCE_STATUS = new Set([502, 503, 504])
const maintenanceListeners = new Set()
let lastMaintenanceAt = 0

/**
 * 最近一次维护信号是否还在窗口内。
 *
 * 重启后后端恢复的头几秒，systemd 状态可能瞬时返回 FAILED/inactive；
 * 游玩页据此判定「异常终止」会误报，需要把这段刚恢复的窗口排除掉。
 */
export function inMaintenanceWindow(ms = 20000) {
  return Date.now() - lastMaintenanceAt < ms
}

/** 订阅「进入维护期」事件，返回取消订阅函数 */
export function onMaintenance(fn) {
  maintenanceListeners.add(fn)
  return () => maintenanceListeners.delete(fn)
}

function signalMaintenance() {
  lastMaintenanceAt = Date.now()
  for (const fn of maintenanceListeners) {
    try {
      fn()
    } catch {
      // 订阅方异常不能影响请求链
    }
  }
}

export class MaintenanceError extends Error {
  constructor(message = '门户正在更新维护') {
    super(message)
    this.name = 'MaintenanceError'
  }
}

export function isMaintenanceError(e) {
  return e instanceof MaintenanceError
}

/** 带维护判定的 fetch：网络中断与网关类 5xx 都视为维护期 */
async function guardedFetch(input, init) {
  let res
  try {
    res = await fetch(input, init)
  } catch (e) {
    // 主动取消（SSE 断开、页面卸载）不算维护
    if (e && e.name === 'AbortError') throw e
    signalMaintenance()
    throw new MaintenanceError()
  }
  if (MAINTENANCE_STATUS.has(res.status)) {
    signalMaintenance()
    throw new MaintenanceError()
  }
  return res
}

/** 探测门户是否已恢复；供维护遮罩轮询 */
export async function probeHealth(timeoutMs = 3000) {
  const ctl = new AbortController()
  const timer = setTimeout(() => ctl.abort(), timeoutMs)
  try {
    const res = await fetch('/api/health', { cache: 'no-store', signal: ctl.signal })
    return res.ok
  } catch {
    return false
  } finally {
    clearTimeout(timer)
  }
}

async function req(path, options = {}) {
  const method = (options.method || 'GET').toUpperCase()
  const isWrite = method !== 'GET' && method !== 'HEAD'
  const { headers: givenHeaders, ...rest } = options
  const base = { 'Content-Type': 'application/json', ...(givenHeaders || {}) }
  const headers = isWrite ? writeHeaders(base) : base
  const res = await guardedFetch(BASE + path, {
    // 列表/详情/fetch 一律不走 HTTP 缓存：编辑页改完顺序后，
    // 详情页若拿的是缓存里的旧 JSON，就会出现两边顺序不一致
    cache: 'no-store',
    ...rest,
    headers,
  })
  if (!res.ok) {
    if (res.status === 403 && isWrite) {
      throw new Error('写操作被拒绝：请在「后台管理」页填写或更新写接口令牌')
    }
    const text = await res.text().catch(() => '')
    throw new Error(`${res.status} ${res.statusText}${text ? ' — ' + text : ''}`)
  }
  if (res.status === 204) return null
  return res.json()
}

/**
 * 上传图片/视频。
 *
 * 用 XHR 而不是 fetch：fetch 没有上传进度，100 MB 视频传几十秒时
 * 用户看不到任何反馈，会以为卡死并重试。XHR 能拿到 progress 事件。
 * 不设 Content-Type，交给浏览器自动带 multipart boundary。
 *
 * @param onProgress 可选，(0..1) => void
 * @param onXhr 可选，(xhr) => void，供调用方拿到句柄以支持取消
 */
function uploadFile(id, kind, file, onProgress, onXhr) {
  return new Promise((resolve, reject) => {
    const form = new FormData()
    form.append('file', file)
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${BASE}/api/games/${encodeURIComponent(id)}/${kind}`)
    // 上传是写操作，同样需要写接口令牌（未启用时后端放行，头为空也不影响）
    const wt = getWriteToken()
    if (wt) xhr.setRequestHeader(WRITE_HEADER, wt)
    if (onXhr) onXhr(xhr)

    if (onProgress && xhr.upload) {
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) onProgress(e.loaded / e.total)
      }
    }

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText))
        } catch {
          resolve(null)
        }
        return
      }
      if (MAINTENANCE_STATUS.has(xhr.status)) {
        signalMaintenance()
        reject(new MaintenanceError())
        return
      }
      let msg = `${xhr.status} ${xhr.statusText || ''}`.trim()
      try {
        const data = JSON.parse(xhr.responseText)
        if (data && data.message) msg = data.message
      } catch {
        // 非 JSON，保留状态码
      }
      if (xhr.status === 413) msg = '文件过大：请到「设置」页查看当前图片 / 视频上限'
      reject(new Error(msg))
    }
    xhr.onerror = () => reject(new Error('网络中断，上传未完成（大文件请检查连接后重试）'))
    xhr.ontimeout = () => reject(new Error('上传超时'))
    xhr.onabort = () => reject(new Error('上传已取消'))
    xhr.send(form)
  })
}

/**
 * 上传一个分片。
 *
 * 同样必须用 XHR：分片进度与暂停都要拿到句柄。服务端按 offset 直接追加，
 * 409 时把 actualOffset 带回给调用方，用于重对齐后续传。
 *
 * @param onProgress 可选，(loaded 字节) => void
 * @param onXhr 可选，(xhr) => void
 */
function uploadChunk(uploadId, fileId, offset, blob, sha256, onProgress, onXhr) {
  return new Promise((resolve, reject) => {
    const url = `${BASE}/api/games/import/uploads/${encodeURIComponent(uploadId)}`
      + `/files/${encodeURIComponent(fileId)}?offset=${offset}`
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', url)
    xhr.setRequestHeader('Content-Type', 'application/octet-stream')
    if (sha256) xhr.setRequestHeader('X-Chunk-SHA256', sha256)
    const wt = getWriteToken()
    if (wt) xhr.setRequestHeader(WRITE_HEADER, wt)
    if (onXhr) onXhr(xhr)

    if (onProgress && xhr.upload) {
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) onProgress(e.loaded)
      }
    }

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText))
        } catch {
          resolve(null)
        }
        return
      }
      if (MAINTENANCE_STATUS.has(xhr.status)) {
        signalMaintenance()
        reject(new MaintenanceError())
        return
      }
      let msg = `${xhr.status} ${xhr.statusText || ''}`.trim()
      let actualOffset
      try {
        const data = JSON.parse(xhr.responseText)
        if (data && data.message) msg = data.message
        if (data && typeof data.actualOffset === 'number') actualOffset = data.actualOffset
      } catch {
        // 非 JSON，保留状态码
      }
      const err = new Error(msg)
      err.status = xhr.status
      err.actualOffset = actualOffset
      reject(err)
    }
    xhr.onerror = () => reject(new Error('网络中断，分片上传失败'))
    xhr.ontimeout = () => reject(new Error('分片上传超时'))
    xhr.onabort = () => {
      const err = new Error('上传已暂停')
      err.aborted = true
      reject(err)
    }
    xhr.send(blob)
  })
}

export const api = {
  health: () => req('/api/health'),
  /** os 传 'LINUX' / 'WINDOWS' 可预览另一平台的方案（状态不可探测）。 */
  environment: (os) => req(`/api/environment${os ? `?os=${encodeURIComponent(os)}` : ''}`),
  /**
   * 静默检查更新：Release 源不可达时后端返回 502，那是外部依赖故障，
   * 不是门户维护。走 guardedFetch 会广播维护事件、弹出维护遮罩，
   * 遮罩探测到 /api/health 正常后强制刷新——设置页每进一次就死循环。
   * 因此这里直接 fetch，失败只返回结果，不抛异常也不广播。
   */
  checkUpdateQuiet: async (opts = {}) => {
    let res
    // refresh=1：手动点击时跳过后端 5 分钟缓存，刚发布的 Release 立刻可见
    const url = BASE + '/api/update/check' + (opts.refresh ? '?refresh=1' : '')
    try {
      res = await fetch(url, { cache: 'no-store' })
    } catch {
      return { ok: false, status: 0, data: null }
    }
    const data = await res.json().catch(() => null)
    return { ok: res.ok, status: res.status, data }
  },
  listGames: async (params = {}) => {
    const qs = new URLSearchParams()
    if (params.tag) qs.set('tag', params.tag)
    if (params.company != null && params.company !== '') qs.set('company', params.company)
    if (params.localization != null && params.localization !== '') qs.set('localization', params.localization)
    if (params.q) qs.set('q', params.q)
    if (params.offset != null) qs.set('offset', params.offset)
    if (params.limit != null) qs.set('limit', params.limit)
    const s = qs.toString()
    const res = await guardedFetch('/api/games' + (s ? '?' + s : ''), { cache: 'no-store' })
    if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
    const items = await res.json()
    // 总数来自响应头，响应体保持数组，旧的调用方仍可直接迭代
    const total = parseInt(res.headers.get('X-Total-Count') || String(items.length), 10)
    return { items, total }
  },
  /** 首页推荐；返回顺序即展示顺序，前端不排序不补位 */
  recommend: (limit = 10, exclude = '') => {
    const qs = new URLSearchParams({ limit: String(limit) })
    if (exclude) qs.set('exclude', exclude)
    return req('/api/recommend?' + qs.toString())
  },
  getGame: (id) => req(`/api/games/${encodeURIComponent(id)}`),
  /** 游玩页轮询：只有状态与串流端口就绪，不触发全量查询 */
  session: (id) => req(`/api/games/${encodeURIComponent(id)}/session`),
  /** 崩溃诊断：退出码、信号、日志尾部（只读） */
  sessionDiagnostics: (id) =>
    req(`/api/games/${encodeURIComponent(id)}/session/diagnostics`),
  deleteGame: (id) =>
    req(`/api/games/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  setMediaMode: (id, mode) =>
    req(`/api/games/${encodeURIComponent(id)}/media-mode`, {
      method: 'POST',
      body: JSON.stringify({ mode }),
    }),
  startGame: (id) => req(`/api/games/${encodeURIComponent(id)}/start`, { method: 'POST' }),
  stopGame: (id) => req(`/api/games/${encodeURIComponent(id)}/stop`, { method: 'POST' }),
  rescan: () => req('/api/games/scan', { method: 'POST' }),

  listTags: () => req('/api/tags'),
  createTag: (name, alias) =>
    req('/api/tags', {
      method: 'POST',
      body: JSON.stringify({ name, alias: alias || '' }),
    }),
  updateTag: (id, name, alias) =>
    req(`/api/tags/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify({ name, alias: alias || '' }),
    }),
  deleteTag: (id) =>
    req(`/api/tags/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  reorderTags: (ids) =>
    req('/api/tags/order', { method: 'PUT', body: JSON.stringify({ ids }) }),
  reorderCompanies: (ids) =>
    req('/api/companies/order', { method: 'PUT', body: JSON.stringify({ ids }) }),
  reorderLocalizationGroups: (ids) =>
    req('/api/localization-groups/order', { method: 'PUT', body: JSON.stringify({ ids }) }),
  reorderGameRelations: (id, kind, ids) =>
    req(`/api/games/${encodeURIComponent(id)}/relations/order`, {
      method: 'PUT',
      body: JSON.stringify({ kind, ids }),
    }),
  resetGameRelations: (id, kind) =>
    req(`/api/games/${encodeURIComponent(id)}/relations/order?kind=${encodeURIComponent(kind)}`, {
      method: 'DELETE',
    }),
  listCompanies: () => req('/api/companies'),
  createCompany: (name, alias, website, description) =>
    req('/api/companies', {
      method: 'POST',
      body: JSON.stringify({
        name,
        alias: alias || '',
        website: website || '',
        description: description || '',
      }),
    }),
  updateCompany: (id, name, alias, website, description) =>
    req(`/api/companies/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify({
        name,
        alias: alias || '',
        website: website || '',
        description: description || '',
      }),
    }),
  deleteCompany: (id) =>
    req(`/api/companies/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  listLocalizationGroups: () => req('/api/localization-groups'),
  createLocalizationGroup: (name, alias, website, description) =>
    req('/api/localization-groups', {
      method: 'POST',
      body: JSON.stringify({
        name,
        alias: alias || '',
        website: website || '',
        description: description || '',
      }),
    }),
  updateLocalizationGroup: (id, name, alias, website, description) =>
    req(`/api/localization-groups/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify({
        name,
        alias: alias || '',
        website: website || '',
        description: description || '',
      }),
    }),
  deleteLocalizationGroup: (id) =>
    req(`/api/localization-groups/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  hero: () => guardedFetch('/api/hero').then((r) => (r.status === 204 ? null : r.json())),
  updateMeta: (id, payload) =>
    req(`/api/games/${encodeURIComponent(id)}/meta`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  uploadCover: (id, file, onProgress) => uploadFile(id, 'cover', file, onProgress),
  uploadIcon: (id, file, onProgress) => uploadFile(id, 'icon', file, onProgress),
  uploadCg: (id, file, onProgress) => uploadFile(id, 'cg', file, onProgress),
  uploadCgHigh: (id, file, onProgress) => uploadFile(id, 'cg-high', file, onProgress),
  uploadCgLow: (id, file, onProgress) => uploadFile(id, 'cg-low', file, onProgress),
  clearImage: (id, kind) =>
    req(`/api/games/${encodeURIComponent(id)}/${kind}`, { method: 'DELETE' }),
  listSaves: (id) => req(`/api/games/${encodeURIComponent(id)}/saves`),
  backupSaves: (id, label, description) =>
    req(`/api/games/${encodeURIComponent(id)}/saves/backup`, {
      method: 'POST',
      body: JSON.stringify({ label: label || '', description: description || '' }),
    }),
  /** 切换存档：语义与恢复相同，切换前后端会自动快照当前存档 */
  switchSave: (id, name) =>
    req(`/api/games/${encodeURIComponent(id)}/saves/${encodeURIComponent(name)}/switch`, {
      method: 'POST',
    }),
  restoreSave: (id, name) =>
    req(`/api/games/${encodeURIComponent(id)}/saves/${encodeURIComponent(name)}/restore`, {
      method: 'POST',
    }),
  updateSave: (id, name, label, description) =>
    req(`/api/games/${encodeURIComponent(id)}/saves/${encodeURIComponent(name)}`, {
      method: 'PUT',
      body: JSON.stringify({ label: label || '', description: description || '' }),
    }),
  deleteSave: (id, name) =>
    req(`/api/games/${encodeURIComponent(id)}/saves/${encodeURIComponent(name)}`, {
      method: 'DELETE',
    }),

  /** 第一步：异步打包游戏目录，返回可轮询的 job */
  packageGame: (id) =>
    req(`/api/games/${encodeURIComponent(id)}/download/packages`, { method: 'POST' }),
  /** 打包前预估：本地待打包字节数、文件数与预计耗时 */
  packageEstimate: (id) =>
    req(`/api/games/${encodeURIComponent(id)}/download/packages/estimate`),
  /** 最近一次打包任务；没有时后端返回 204，此处得到 null。页面刷新后恢复进度用。 */
  currentDownloadPackage: (id) =>
    req(`/api/games/${encodeURIComponent(id)}/download/packages/current`),
  downloadJob: (id, jobId) =>
    req(`/api/games/${encodeURIComponent(id)}/download/packages/${encodeURIComponent(jobId)}`),
  /** 第二步：打包完成后由浏览器直接下载（GET，不需要写令牌） */
  downloadPackageUrl: (id, jobId) =>
    `/api/games/${encodeURIComponent(id)}/download/packages/${encodeURIComponent(jobId)}/file`,
  deleteDownloadPackage: (id, jobId) =>
    req(`/api/games/${encodeURIComponent(id)}/download/packages/${encodeURIComponent(jobId)}`, {
      method: 'DELETE',
    }),

  playtime: (id) => req(`/api/games/${encodeURIComponent(id)}/playtime`),
  heartbeat: (id) =>
    req(`/api/games/${encodeURIComponent(id)}/playtime/heartbeat`, { method: 'POST' }),
  leave: (id) =>
    req(`/api/games/${encodeURIComponent(id)}/playtime/leave`, { method: 'POST' }),

  settings: () => req('/api/settings'),
  saveSettings: (patch) =>
    req('/api/settings', { method: 'POST', body: JSON.stringify(patch) }),
  /** 派生图清理：仅开关关闭时可用 */
  imageVariants: () => req('/api/settings/image-variants'),
  clearImageVariants: () => req('/api/settings/image-variants', { method: 'DELETE' }),

  // ---------------- 后台管理 ----------------
  adminSettings: (token) =>
    req('/api/admin/settings', { headers: { [DEPLOY_HEADER]: token } }),
  saveAdminSettings: (patch, token) =>
    req('/api/admin/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [DEPLOY_HEADER]: token },
      body: JSON.stringify(patch),
    }),

  editView: (id) => req(`/api/games/${encodeURIComponent(id)}/edit`),
  updateGame: (id, patch) =>
    req(`/api/games/${encodeURIComponent(id)}/meta`, {
      method: 'POST',
      body: JSON.stringify(patch),
    }),

  /** 编辑页的 exe 候选 + 当前主用/备用 */
  gameExes: (id) => req(`/api/games/${encodeURIComponent(id)}/exes`),
  /** 游戏主窗口在串流画布上的矩形；未运行或取不到时返回 null（404 不是错误） */
  gameWindow: async (id) => {
    try {
      return await req(`/api/games/${encodeURIComponent(id)}/window`)
    } catch {
      return null
    }
  },
  /** 画布校准：manual=true 时即使全局关闭自动校准也执行 */
  calibrateCanvas: (id, manual = false) =>
    req(`/api/games/${encodeURIComponent(id)}/canvas/calibrate?manual=${manual ? 'true' : 'false'}`, {
      method: 'POST',
    }),
  uploadMedia: (id, kind, file, onProgress, onXhr) =>
    uploadFile(id, `media/${kind}`, file, onProgress, onXhr),
  /** 某类媒体列表；上传即时落库后用于取消上传后的对账 */
  listMedia: (id, kind) => req(`/api/games/${encodeURIComponent(id)}/media/${kind}`),
  deleteMedia: (id, mediaId) =>
    req(`/api/games/${encodeURIComponent(id)}/media/${mediaId}`, { method: 'DELETE' }),
  reorderMedia: (id, kind, ids) =>
    req(`/api/games/${encodeURIComponent(id)}/media/reorder`, {
      method: 'POST',
      body: JSON.stringify({ kind, ids }),
    }),
  uploadIconFile: (id, file, onProgress) => uploadFile(id, 'icon', file, onProgress),
  deleteIcon: (id) => req(`/api/games/${encodeURIComponent(id)}/icon`, { method: 'DELETE' }),

  // ---------------- 一键部署 ----------------
  deployStatus: (token) =>
    req('/api/deploy/status', { headers: { [DEPLOY_HEADER]: token } }),
  deployConfirm: (id, token) =>
    req(`/api/deploy/steps/${encodeURIComponent(id)}/confirm`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [DEPLOY_HEADER]: token },
      body: '{}',
    }),
  deployRun: (id, token, confirmToken, sudoPassword) =>
    req(`/api/deploy/steps/${encodeURIComponent(id)}/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [DEPLOY_HEADER]: token },
      body: JSON.stringify({ confirmToken: confirmToken || null, sudoPassword: sudoPassword || null }),
    }),
  deployCancel: (runId, token) =>
    req(`/api/deploy/runs/${encodeURIComponent(runId)}/cancel`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [DEPLOY_HEADER]: token },
      body: '{}',
    }),

  /**
   * 订阅部署日志（SSE）。
   *
   * EventSource 不能带自定义请求头，而部署接口要求令牌放在 header，
   * 所以这里用 fetch + ReadableStream 自己解析 SSE 的 `data:` 行。
   *
   * @param onLine 每收到一行日志回调
   * @param signal AbortSignal，用于中止订阅
   */
  async deployEvents(runId, token, onLine, signal) {
    const res = await guardedFetch(`/api/deploy/runs/${encodeURIComponent(runId)}/events`, {
      headers: { [DEPLOY_HEADER]: token, Accept: 'text/event-stream' },
      signal,
    })
    if (!res.ok || !res.body) {
      throw new Error(`${res.status} ${res.statusText}`)
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, idx).replace(/\r$/, '')
        buf = buf.slice(idx + 1)
        if (line.startsWith('data:')) {
          onLine(line.slice(5).replace(/^ /, ''))
        }
      }
    }
  },

  // ---------------- 远程更新 ----------------

  /** 申请更新：confirm 必须是 `UPDATE <版本号>`，服务端会再校验一次 */
  applyUpdate: (body) =>
    req('/api/update/apply', { method: 'POST', body: JSON.stringify(body) }),

  /** 更新期间 Portal 会重启，读失败属于正常现象，由调用方忽略 */
  updateStatus: () => req('/api/update/status'),

  // ---------------- 游戏接入 ----------------

  /** 候选目录会暴露服务器结构，读接口也要带写令牌 */
  importCandidates: () =>
    req('/api/games/import/candidates', { headers: writeHeaders() }),

  importSlots: () =>
    req('/api/games/import/slots', { headers: writeHeaders() }),

  importGame: (body) =>
    req('/api/games/import', { method: 'POST', body: JSON.stringify(body) }),

  importRun: (runId) =>
    req(`/api/games/import/runs/${encodeURIComponent(runId)}`, { headers: writeHeaders() }),

  importCancel: (runId) =>
    req(`/api/games/import/runs/${encodeURIComponent(runId)}/cancel`,
      { method: 'POST', body: '{}' }),

  importRetry: (runId) =>
    req(`/api/games/import/runs/${encodeURIComponent(runId)}/retry`,
      { method: 'POST', body: '{}' }),

  importRollback: (runId) =>
    req(`/api/games/import/runs/${encodeURIComponent(runId)}/rollback`,
      { method: 'POST', body: '{}' }),

  /**
   * 订阅接入日志（SSE）。
   *
   * 与 deployEvents 同样是 fetch + ReadableStream，区别是要区分 event 名
   * （log / step / state），所以解析 `event:` 行并交给 onEvent(name, data)。
   */
  async importRunEvents(runId, token, onEvent, signal) {
    const res = await guardedFetch(`/api/games/import/runs/${encodeURIComponent(runId)}/events`, {
      headers: {
        ...(token ? { [WRITE_HEADER]: token } : {}),
        Accept: 'text/event-stream',
      },
      signal,
    })
    if (!res.ok || !res.body) {
      throw new Error(`${res.status} ${res.statusText}`)
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    let eventName = 'log'
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, idx).replace(/\r$/, '')
        buf = buf.slice(idx + 1)
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          onEvent(eventName, line.slice(5).replace(/^ /, ''))
        } else if (line === '') {
          eventName = 'log'
        }
      }
    }
  },

  // ---------------- 游戏上传（分片协议） ----------------

  uploadLimits: () =>
    req('/api/games/import/uploads/limits', { headers: writeHeaders() }),

  /** 创建上传会话：压缩包 ARCHIVE 或文件夹 FOLDER */
  createUpload: (body) =>
    req('/api/games/import/uploads', { method: 'POST', body: JSON.stringify(body) }),

  /** 查询状态与已传偏移；刷新恢复、断点续传都靠它 */
  uploadStatus: (uploadId) =>
    req(`/api/games/import/uploads/${encodeURIComponent(uploadId)}`,
      { headers: writeHeaders() }),

  uploadChunk,

  completeUpload: (uploadId) =>
    req(`/api/games/import/uploads/${encodeURIComponent(uploadId)}/complete`,
      { method: 'POST', body: '{}' }),

  cancelUpload: (uploadId) =>
    req(`/api/games/import/uploads/${encodeURIComponent(uploadId)}`, { method: 'DELETE' }),

  /**
   * 订阅上传任务日志（SSE）。事件名与接入一致：log / step / state。
   */
  async uploadEvents(uploadId, token, onEvent, signal) {
    const res = await guardedFetch(
      `/api/games/import/uploads/${encodeURIComponent(uploadId)}/events`,
      {
        headers: {
          ...(token ? { [WRITE_HEADER]: token } : {}),
          Accept: 'text/event-stream',
        },
        signal,
      },
    )
    if (!res.ok || !res.body) {
      throw new Error(`${res.status} ${res.statusText}`)
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    let eventName = 'log'
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, idx).replace(/\r$/, '')
        buf = buf.slice(idx + 1)
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          onEvent(eventName, line.slice(5).replace(/^ /, ''))
        } else if (line === '') {
          eventName = 'log'
        }
      }
    }
  },
}
