<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, getWriteToken, setWriteToken } from '../api'
import { confirmAction, notify } from '../feedback'
import { formatBytes } from '../format'
import { releasesUrl, tagUrl } from '../repo-links'
import { runUninstallSteps, uninstallSequence } from '../uninstall-sequence'
import { installSequence, runInstallSteps } from '../install-sequence'
import { canApply, canOverride, confirmText, isActivePhase, overrideConfirmText, phaseLabel } from '../update'

const TOKEN_KEY = 'galworld.adminToken'
const token = ref(sessionStorage.getItem(TOKEN_KEY) || '')
const settings = ref(null)
const busy = ref(false)

const authed = computed(() => settings.value !== null)

/** 写接口令牌：存本机 localStorage；后台是唯一允许修改的位置 */
const savedWriteToken = ref(getWriteToken())
const writeTokenSaved = computed(() => !!savedWriteToken.value.trim())
/** 输入框不预填旧令牌：预填会让占位文案永远不可见，直接输入还会拼成旧+新 */
const writeToken = ref('')
const WRITE_TOKEN_CMD =
  "journalctl --user -u galworld-portal --no-pager | grep '写接口令牌：' | tail -1"
/** 部署令牌：未配置 GALWORLD_DEPLOY_TOKEN 时每次启动随机生成，只出现在启动日志里 */
const DEPLOY_TOKEN_CMD =
  "journalctl --user -u galworld-portal --no-pager | grep '随机令牌' | tail -1"
function saveWriteToken() {
  if (!writeToken.value.trim()) return
  setWriteToken(writeToken.value)
  savedWriteToken.value = getWriteToken()
  writeToken.value = ''
  notify.success('令牌已保存到本机')
}

async function clearWriteToken() {
  const ok = await confirmAction('清除本机的写接口令牌？清除后写操作会被服务端拒绝。', {
    title: '清除写接口令牌', confirmText: '清除',
  })
  if (!ok) return
  setWriteToken('')
  writeToken.value = ''
  savedWriteToken.value = ''
  notify.success('已清空令牌')
}

// ---------------- 环境信息 ----------------

/** 环境信息由后端按当前操作系统返回；后端不可用时显示空态 */
const environment = ref([])
const os = ref(null)
/** 查询命令只对 Linux/systemd 有效；Windows 部署由计划任务拉起，没有 journald */
const isWindows = computed(() => os.value?.kind === 'WINDOWS')
const envError = ref(false)
const selectedEnvKey = ref('')
const account = ref(null)
const selectedEnv = computed(() =>
  environment.value.find((s) => s.key === selectedEnvKey.value) || environment.value[0])

const STATUS_TEXT = {
  active: '已启用',
  installed: '已安装',
  planned: '规划中',
  rejected: '未采用',
}

const HEALTH_TEXT = {
  ok: '正常',
  warn: '注意',
  error: '异常',
  unknown: '未知',
}

/** 环境方案里当前展开的详情分组（一次只展开一个，便于排查） */
const openGroup = ref('')

watch(selectedEnvKey, () => {
  openGroup.value = ''
})

function toggleGroup(name) {
  openGroup.value = openGroup.value === name ? '' : name
}

/** 复制命令：环境页的命令只做展示，复制走剪贴板 API */
async function copyCommand(text) {
  try {
    await navigator.clipboard.writeText(text)
    notify.success('命令已复制')
  } catch {
    notify.error('复制失败，请手动选中')
  }
}

async function loadEnvironment() {
  try {
    const env = await api.environment(previewOs.value || undefined).catch(() => null)
    if (env?.schemes?.length) {
      environment.value = env.schemes
      account.value = env.account || null
      os.value = env.os || null
      envPreview.value = env.preview === true
      envError.value = false
      selectedEnvKey.value = env.schemes.some((s) => s.key === selectedEnvKey.value)
        ? selectedEnvKey.value : env.schemes[0].key
    } else {
      envError.value = true
    }
  } catch {
    envError.value = true
  }
}

/** 切换预览平台：'' 表示当前系统，只能看当前系统的部署/卸载面板。 */
const previewOs = ref('')
const envPreview = ref(false)
const previewLabel = computed(() => (
  previewOs.value === 'WINDOWS' ? 'Windows' : previewOs.value === 'LINUX' ? 'Linux' : ''))

async function switchEnvOs(kind) {
  if (previewOs.value === kind) return
  previewOs.value = kind
  await loadEnvironment()
}

// ---------------- 版本 ----------------

const about = ref({ version: '', repoUrl: '' })
const updateBusy = ref(false)
const updateResult = ref(null)

/** 仓库地址可能被「公开地址」开关隐藏；后台已解锁时用设置里的值兜底 */
const repoBase = computed(() =>
  (about.value.repoUrl || settings.value?.repoUrl || '').replace(/\/+$/, ''))

/** 发布页随仓库地址走；路径规则按托管平台（GitHub / Gitee / GitLab）自动选，见 repo-links.js */
const RELEASES_PAGE = computed(() => releasesUrl(repoBase.value))

/** 当前版本号直接链到对应 tag 页；SNAPSHOT 去掉后缀，版本号不可用时退回发布页 */
const versionTagUrl = computed(() => tagUrl(repoBase.value, about.value.version))

async function loadAbout() {
  try {
    about.value = await api.health()
  } catch {
    // 旧后端没有版本字段时保留默认值
  }
}

async function checkUpdate() {
  updateBusy.value = true
  updateResult.value = null
  // 用户点「检查更新」时 Release 源不可达属于外部依赖故障，不是门户维护：
  // 走 quiet 版，避免误弹维护遮罩并强制刷新页面
  const r = await api.checkUpdateQuiet({ refresh: true })
  if (r.ok && r.data) {
    updateResult.value = r.data
  } else if (r.ok) {
    // 200 但解析不出 JSON：多为网关返回的登录页/错误页，不能静默无反应
    notify.error('检查更新失败：返回内容无法解析')
  } else {
    notify.error('检查更新失败：' + (r.data?.message || (r.status ? `HTTP ${r.status}` : '网络不可达')))
  }
  updateBusy.value = false
}

// ---------------- 远程更新 ----------------
//
// 更新会重启 Portal：状态以 update-status.json 为准，页面只轮询展示；
// 轮询失败（重启中）是预期行为，不弹报错，等后端恢复后自然继续。

const updateApplying = ref(false)
const updateStatus = ref({ phase: 'idle' })
let updateTimer = 0

function stopUpdatePolling() {
  if (!updateTimer) return
  clearInterval(updateTimer)
  updateTimer = 0
}

function startUpdatePolling() {
  if (!updateTimer) updateTimer = setInterval(pollUpdateStatus, 2000)
}

async function pollUpdateStatus() {
  try {
    updateStatus.value = await api.updateStatus()
    // 阶段进入终态（或空闲）后停止轮询；页面刷新后看到进行中的任务会自动续上
    if (isActivePhase(updateStatus.value.phase)) startUpdatePolling()
    else stopUpdatePolling()
  } catch {
    // 更新期间 Portal 会重启，忽略失败继续轮询
  }
}

async function submitUpdate(version, confirm, override, prompt, confirmLabel) {
  if (!version) return
  const ok = await confirmAction(prompt, { title: '远程更新', confirmText: confirmLabel })
  if (!ok) return
  updateApplying.value = true
  // 先起轮询再发 POST：门户可能在响应返回前就开始重启，不能等 POST 成功才起定时器。
  startUpdatePolling()
  try {
    await api.applyUpdate({ version, confirm, override })
  } catch (e) {
    // POST 失败可能只是重启导致的连接中断：状态文件显示活跃阶段时不算启动失败。
    let known = false
    try {
      updateStatus.value = await api.updateStatus()
      known = true
    } catch {
      // Portal 正在重启，状态暂时读不到
    }
    if (known && isActivePhase(updateStatus.value.phase)) {
      startUpdatePolling()
    } else {
      notify.error('启动更新失败：' + e.message)
    }
  } finally {
    updateApplying.value = false
  }
  await pollUpdateStatus()
}

async function applyUpdate() {
  const version = updateResult.value?.latestVersion
  if (!version || !canApply(updateResult.value, updateStatus.value)) return
  await submitUpdate(
    version,
    confirmText(version),
    false,
    `更新到 ${version}？门户会重启，正在游玩的会话不受影响。`,
    '立即更新',
  )
}

const overrideRef = ref('')

async function applyOverrideUpdate() {
  const target = overrideRef.value.trim()
  if (!canOverride(updateResult.value?.overrideEnabled, target, updateStatus.value)) return
  await submitUpdate(
    target,
    overrideConfirmText(target),
    true,
    `覆盖更新到 ${target}？会降级或重装当前版本，失败自动回滚；目标版本必须自带更新器。`,
    '覆盖更新',
  )
}

// ---------------- 项目仓库 ----------------

/** 项目仓库地址：可改，默认取 GALWORLD_REPO_URL；显示在「设置 → 关于」 */
const repoUrl = ref('')

function syncRepoUrl() {
  repoUrl.value = settings.value?.repoUrl || ''
}

async function saveRepoUrl() {
  await saveAdmin({ repoUrl: repoUrl.value.trim() })
}

function setRepoUrlPublic(on) {
  saveAdmin({ repoUrlPublic: on ? 'true' : 'false' })
}

// ---------------- 远程更新源 ----------------

/**
 * 更新源三项：发布接口根地址、仓库路径、仓库网页根地址。
 * 默认取 GALWORLD_UPDATE_API_BASE / _REPO / _WEB_BASE，管理页改完立即生效，
 * 内网或镜像部署替换掉 GitHub 即可。
 */
const updateApiBase = ref('')
const updateRepo = ref('')
const updateWebBase = ref('')

function syncUpdateSource() {
  updateApiBase.value = settings.value?.updateApiBase || ''
  updateRepo.value = settings.value?.updateRepo || ''
  updateWebBase.value = settings.value?.updateWebBase || ''
}

async function saveUpdateSource() {
  await saveAdmin({
    updateApiBase: updateApiBase.value.trim(),
    updateRepo: updateRepo.value.trim(),
    updateWebBase: updateWebBase.value.trim(),
  })
}

// ---------------- 元件下载源 ----------------

/** 元件下载源：设置里存的是逗号分隔，界面按「每行一个」编辑 */
const artifactNexus = ref('')
const artifactMirrors = ref('')

function syncArtifactSources() {
  artifactNexus.value = settings.value?.artifactNexusBase || ''
  artifactMirrors.value = (settings.value?.artifactMirrors || '')
    .split(',').map((s) => s.trim()).filter(Boolean).join('\n')
}

async function saveArtifactSources() {
  const mirrors = artifactMirrors.value
    .split(/[\n,]+/).map((s) => s.trim()).filter(Boolean)
  await saveAdmin({
    artifactNexusBase: artifactNexus.value.trim(),
    artifactMirrors: mirrors.join(','),
  })
}

// ---------------- 一键部署 ----------------

const deployOs = ref('')
const deploySteps = ref([])
/** 提权入口状态：{ path, present, version, expected } */
const deployElevation = ref(null)
const deployError = ref('')
const deployLoading = ref(false)
const deployRunId = ref('')
const deployRunStep = ref('')
const deployRunState = ref('')
/** 服务端是否允许页面输入 sudo 密码 */
const allowSudo = ref(false)
/** 一次性 sudo 密码输入框内容（提交后立刻清空） */
const deploySudo = ref('')
const deployLines = ref([])
let deployAbort = null

/** 卸载步骤单独分组，避免与安装步骤混在一起误点 */
const installSteps = computed(() => deploySteps.value.filter((s) => !s.uninstall))
const uninstallSteps = computed(() => deploySteps.value.filter((s) => s.uninstall))

/**
 * 提权入口提示：没装或版本与 jar 不一致时，一键部署会退回要 sudo 密码的老路径。
 * 版本号由服务端比对（jar 里的期望值 vs 磁盘脚本头部标记），这里只负责显示。
 */
const elevationWarning = computed(() => {
  const e = deployElevation.value
  if (!e || deployOs.value !== 'LINUX') return ''
  if (!e.present) {
    return `未发现提权入口 ${e.path}：一键安装会要求 sudo 密码。重跑 deploy.sh（需要一次 root）可自动安装。`
  }
  if (e.version !== e.expected) {
    return `提权入口版本 ${e.version}，当前需要 ${e.expected}。重跑 deploy.sh 更新（需要一次 root，安装这一步本身要管理员权限）`
  }
  return ''
})
const uninstalling = ref(false)
const installing = ref(false)

/** 待执行且需要提权的安装步骤数量：0 时不必填 sudo 密码 */
const pendingAdminSteps = computed(() => installSteps.value.filter(
  (s) => s.state !== 'READY' && s.runAs === 'ADMIN',
).length)

/** 展开查看命令的步骤 id 集合；命令文本来自后端 status，前端只做展示 */
const openCmds = ref(new Set())
function cmdOpen(id) {
  return openCmds.value.has(id)
}
function toggleCmd(id) {
  const next = new Set(openCmds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  openCmds.value = next
}
/** 后端返回的命令行；为空时给一句说明，避免展开后一片空白 */
function commandsOf(step) {
  const lines = Array.isArray(step.commands) ? step.commands : []
  return lines.length ? lines.join('\n') : '该步骤没有可直接展示的命令'
}

const DEPLOY_LABEL = {
  READY: '已就绪', NOT_READY: '待执行', BLOCKED: '等待前置', QUEUED: '排队中',
  RUNNING: '执行中', SUCCESS: '成功', FAILED: '失败', NEED_ELEVATION: '需提权',
  CANCELLED: '已取消',
}
const DEPLOY_BADGE = {
  READY: 'ok', SUCCESS: 'ok', RUNNING: 'run', QUEUED: 'run',
  BLOCKED: 'warn', NEED_ELEVATION: 'warn', FAILED: 'bad', CANCELLED: 'bad',
}

async function loadDeployStatus() {
  const t = token.value.trim()
  if (!t) {
    deploySteps.value = []
    deployElevation.value = null
    return
  }
  deployLoading.value = true
  deployError.value = ''
  try {
    const d = await api.deployStatus(t)
    deployOs.value = d.os || ''
    allowSudo.value = d.allowSudoPassword === true
    deployElevation.value = d.elevation || null
    deploySteps.value = d.steps || []
  } catch (e) {
    deployError.value = '读取部署状态失败：' + e.message
    deploySteps.value = []
  } finally {
    deployLoading.value = false
  }
}

async function runDeployStep(step, sudoPassword, opts = {}) {
  if (deployRunId.value) return false
  const t = token.value.trim()
  if (!t) {
    notify.error('先填写部署令牌')
    return false
  }
  try {
    let confirmToken = null
    if (step.destructive) {
      if (!opts.skipConfirm) {
        const ok = await confirmAction(
          `${step.title}\n\n${step.summary}\n\n这是破坏性步骤，确认执行？`,
          { title: '确认部署', confirmText: '执行' },
        )
        if (!ok) return false
      }
      const c = await api.deployConfirm(step.id, t)
      confirmToken = c.confirmToken
    }
    deployLines.value = []
    deployRunStep.value = step.title || step.id
    deployRunState.value = 'QUEUED'
    const r = await api.deployRun(step.id, t, confirmToken, sudoPassword || null)
    if (!opts.keepSudo) deploySudo.value = ''
    deployRunId.value = r.runId
    await followRun(r.runId, t)
    // 状态刷新失败时不能当作成功：宁可停下报错，也不能谎报卸载完成
    const latest = deploySteps.value.find((s) => s.id === step.id)
    return latest?.lastState === 'SUCCESS'
  } catch (e) {
    if (!opts.keepSudo) deploySudo.value = ''
    notify.error('执行失败：' + e.message)
    deployRunId.value = ''
    return false
  }
}

async function followRun(runId, t) {
  deployAbort?.abort()
  deployAbort = new AbortController()
  try {
    await api.deployEvents(runId, t, (line) => {
      if (line === 'done') return
      deployLines.value = [...deployLines.value, line]
    }, deployAbort.signal)
  } catch (e) {
    if (e.name !== 'AbortError') {
      deployLines.value = [...deployLines.value, '日志流中断：' + e.message]
    }
  } finally {
    deployRunState.value = '完成'
    deployRunId.value = ''
    await loadDeployStatus()
  }
}

async function cancelDeploy() {
  if (!deployRunId.value) return
  const ok = await confirmAction('中止当前部署步骤？', { title: '中止部署', confirmText: '中止' })
  if (!ok) return
  try {
    await api.deployCancel(deployRunId.value, token.value.trim())
  } catch (e) {
    notify.error('中止失败：' + e.message)
  }
  deployAbort?.abort()
}

/** 一键安装：按依赖拓扑序串行执行，跳过已就绪的步骤，任一步失败即停 */
async function runInstallAll() {
  if (deployRunId.value || installing.value || uninstalling.value) return
  const snapshot = deploySteps.value.slice()
  const seq = installSequence(snapshot)
  const pending = seq.filter((id) => snapshot.find((s) => s.id === id)?.state !== 'READY')
  if (!pending.length) {
    notify.success('所有组件都已就绪')
    return
  }
  const titles = pending.map((id) => snapshot.find((s) => s.id === id)?.title || id)
  const needsSudo = pending.some((id) => snapshot.find((s) => s.id === id)?.runAs === 'ADMIN')
  const sudoHint = needsSudo && allowSudo.value && !deploySudo.value
    ? '\n注意：这批里有需要提权的步骤，但上面没填 sudo 密码，可能会停在「需提权」。'
    : ''
  const ok = await confirmAction(
    `将依次执行 ${titles.length} 个步骤：\n${titles.join('\n')}\n\n`
      + '只安装运行时组件，不会改动 games/ 与 data/ 里的游戏与存档。\n'
      + '需要提权的步骤复用同一份 sudo 凭据；页面要求输入密码时请先填好再点开始。'
      + sudoHint,
    { title: '一键安装环境', confirmText: '开始安装' },
  )
  if (!ok) return
  installing.value = true
  // 密码整轮复用：中途清空会让后面的 ADMIN 步骤无法提权
  const sudoPassword = allowSudo.value ? deploySudo.value : ''
  try {
    const result = await runInstallSteps(seq, snapshot, (step) => {
      const sudo = step.runAs === 'ADMIN' ? sudoPassword : ''
      return runDeployStep(step, sudo, { skipConfirm: true, keepSudo: true })
    })
    if (!result.ok) {
      const failed = snapshot.find((s) => s.id === result.failed)
      notify.error(`${failed?.title || result.failed} 未完成，已停止后续安装`)
      return
    }
    deploySudo.value = ''
    notify.success('安装完成')
  } finally {
    installing.value = false
  }
}

/** 一键卸载：按启动时快照顺序串行执行，任一步失败即停 */
async function runUninstallAll() {
  if (deployRunId.value || uninstalling.value) return
  const seq = uninstallSequence(deploySteps.value)
  if (!seq.length) {
    notify.success('没有需要卸载的组件')
    return
  }
  const snapshot = deploySteps.value.slice()
  const titles = seq.map((id) => snapshot.find((s) => s.id === id)?.title || id)
  const detail = deployOs.value === 'WINDOWS'
    ? '会删除：Moonlight Web、Sunshine 服务与残留目录、Sunshine 防火墙规则、虚拟声卡驱动、元件缓存。\n'
      + '只保留：门户本体（jar、计划任务、数据库、证书）、游戏与存档、Java。\n'
      + '虚拟声卡是驱动，卸载后需要重启一次才彻底移除。\n'
      + '卸载后 portal 仍可访问，但串流环境需要重新「一键安装」才能恢复。'
    : '会删除：Wine、Selkies venv、音频与游戏单元、元件缓存，'
      + '并 purge xvfb / PulseAudio / python3-venv / 中文字体与全部 i386 包（含 i386 架构）。\n'
      + '只保留：门户本体（jar、单元、数据库、证书）、游戏与存档、JDK、linger。\n'
      + 'Linux 会移除当前账号的 audio 组关系；若该账号还用于其他音频用途，需事后手工加回。\n'
      + '卸载后 portal 仍可访问，但串流环境需要重新「一键安装」才能恢复。'
  const ok = await confirmAction(
    `将依次执行：\n${titles.join('\n')}\n\n${detail}`,
    { title: '一键卸载环境', confirmText: '开始卸载' },
  )
  if (!ok) return
  uninstalling.value = true
  // 密码整轮复用：中途清空会让后面的 ADMIN 步骤无法提权
  const sudoPassword = allowSudo.value ? deploySudo.value : ''
  try {
    const result = await runUninstallSteps(seq, snapshot, (step) => {
      const sudo = step.runAs === 'ADMIN' ? sudoPassword : ''
      return runDeployStep(step, sudo, { skipConfirm: true, keepSudo: true })
    })
    if (!result.ok) {
      const failed = snapshot.find((s) => s.id === result.failed)
      notify.error(`${failed?.title || result.failed} 未完成，已停止后续卸载`)
      return
    }
    deploySudo.value = ''
    notify.success('卸载完成')
  } finally {
    uninstalling.value = false
  }
}

// ---------------- 图片派生图 ----------------

/** 派生图清理：仅在开关关闭后可用，列出已有文件路径并支持一次性删除 */
const variantScan = ref(null)
const variantLoading = ref(false)
const clearingVariants = ref(false)

async function loadVariants() {
  variantLoading.value = true
  try {
    variantScan.value = await api.imageVariants()
  } catch (e) {
    variantScan.value = null
    notify.error('读取派生图失败：' + e.message)
  } finally {
    variantLoading.value = false
  }
}

watch(() => settings.value?.imageVariantEnabled, (v) => {
  if (v === 'false') {
    loadVariants()
  } else {
    variantScan.value = null
  }
})

async function clearVariants() {
  const info = variantScan.value
  if (!info || !info.count) return
  const ok = await confirmAction(
    `删除全部 ${info.count} 个派生图（共 ${formatBytes(info.bytes)}）？\n原图不受影响；再次开启优化时会重新生成。`,
    { title: '清理派生图', confirmText: '清理' },
  )
  if (!ok) return
  clearingVariants.value = true
  try {
    const r = await api.clearImageVariants()
    notify.success(`已清理 ${r.deleted} 个派生图，释放 ${formatBytes(r.bytes)}`)
    await loadVariants()
  } catch (e) {
    notify.error('清理失败：' + e.message)
  } finally {
    clearingVariants.value = false
  }
}

// ---------------- 设置读写 ----------------

async function unlock() {
  const t = token.value.trim()
  if (!t) return
  busy.value = true
  try {
    settings.value = await api.adminSettings(t)
    sessionStorage.setItem(TOKEN_KEY, t)
    syncRepoUrl()
    syncUpdateSource()
    syncArtifactSources()
    loadAbout()
    loadEnvironment()
    loadDeployStatus()
    pollUpdateStatus()
  } catch (e) {
    settings.value = null
    notify.error(e.message.includes('403') ? '令牌无效或来源不在允许网段' : '读取失败：' + e.message)
  } finally {
    busy.value = false
  }
}

function lock() {
  sessionStorage.removeItem(TOKEN_KEY)
  token.value = ''
  settings.value = null
  deploySteps.value = []
  deployLines.value = []
  deployAbort?.abort()
  stopUpdatePolling()
}

async function saveAdmin(patch) {
  busy.value = true
  try {
    settings.value = await api.saveAdminSettings(patch, token.value.trim())
    syncRepoUrl()
    syncUpdateSource()
    syncArtifactSources()
    // 仓库地址改动要立刻反映到下方「代码仓库」链接
    loadAbout()
    notify.success('已保存')
  } catch (e) {
    notify.error('保存失败：' + e.message)
  } finally {
    busy.value = false
  }
}

function setImageVariantEnabled(on) {
  saveAdmin({ imageVariantEnabled: on ? 'true' : 'false' })
}

/** 数字项：超出范围时提示，不提交 */
function setNumber(key, raw, min, max, label) {
  const n = Number(raw)
  if (!Number.isInteger(n) || n < min || n > max) {
    notify.warning(`${label}范围 ${min}-${max}`)
    return
  }
  saveAdmin({ [key]: String(n) })
}

onMounted(() => {
  // unlock() 内部会拉更新状态；未登录时也要显示只读的更新进度，故单独轮询一次
  if (token.value) unlock()
  else pollUpdateStatus()
})

onBeforeUnmount(() => {
  deployAbort?.abort()
  stopUpdatePolling()
})
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <router-link to="/" class="ghost-btn">← 游戏库</router-link>
      <div class="spacer" />
      <div class="brand" style="font-size:15px;letter-spacing:.1em">后台管理</div>
      <button v-if="authed" class="ghost-btn" @click="lock">锁定</button>
    </header>

    <div class="admin-warn">
      此页面的修改会影响所有用户与服务器状态：上传上限、磁盘占用、下载源、系统部署。
    </div>

    <section v-if="!authed" class="panel">
      <div class="panel-head"><h2>需要部署令牌</h2></div>
      <p v-if="about.deployTokenConfigured" class="muted-sm" style="margin-bottom:14px">
        部署者已通过 <code>GALWORLD_DEPLOY_TOKEN</code> 或 <code>galworld.deploy.token</code>
        固定令牌，启动日志不再打印，请向部署者索取。
      </p>
      <template v-else>
        <p class="muted-sm" style="margin-bottom:14px">
          未配置固定令牌：每次启动随机生成，只出现在启动日志里，重启后即失效。
        </p>
        <div class="env-command" style="margin-bottom:14px">
          <div class="env-command-head">
            <span>{{ isWindows ? '服务器查询命令（Windows 不支持 journalctl）' : '服务器查询命令' }}</span>
            <span v-if="!isWindows" class="env-command-lang">bash</span>
            <button v-if="!isWindows" class="link-btn env-copy" @click="copyCommand(DEPLOY_TOKEN_CMD)">复制</button>
          </div>
          <pre v-if="!isWindows">{{ DEPLOY_TOKEN_CMD }}</pre>
        </div>
        <p v-if="isWindows" class="muted-sm" style="margin-bottom:14px">
          Windows 部署由计划任务拉起门户，没有 journald；请查看启动控制台输出，
          或设置环境变量 <code>GALWORLD_DEPLOY_TOKEN</code> 固定令牌。
        </p>
        <p v-else class="muted-sm" style="margin-bottom:14px">
          输出行冒号后即当前令牌。命令需在门户运行用户下执行（通常不是登录账号，
          例如 <code>sudo -u claude XDG_RUNTIME_DIR=/run/user/$(id -u claude) ...</code>）。
        </p>
      </template>
      <div class="deploy-token-row">
        <input v-model="token" class="input" type="password" placeholder="部署令牌"
               @keyup.enter="unlock" />
        <button class="btn primary sm" :disabled="busy || !token" @click="unlock">
          {{ busy ? '验证中…' : '解锁' }}
        </button>
      </div>
    </section>

    <template v-else>
      <section id="write-token" class="panel">
        <div class="panel-head">
          <h2>写接口令牌</h2>
          <span class="muted-sm">与部署令牌不同 · 仅本机浏览器</span>
        </div>
        <p class="muted-sm" style="margin-bottom:14px">
          服务端配置了 <code>GALWORLD_WRITE_TOKEN</code>（或 <code>galworld.write.token</code>）时，
          启动、上传、改设置都会被拒绝（403），需要在这里填入同一个令牌。
          这里只保存到本机 localStorage，不会修改服务端配置。
        </p>
        <div class="deploy-token-row">
          <input
            v-model="writeToken"
            class="input"
            type="password"
            autocomplete="off"
            :placeholder="writeTokenSaved ? '已填写，输入新令牌可覆盖' : '写接口令牌（未启用保护时留空）'"
            @keyup.enter="saveWriteToken"
          />
          <button class="btn primary sm" :disabled="!writeToken.trim()" @click="saveWriteToken">保存</button>
          <button class="btn" :disabled="!writeTokenSaved" @click="clearWriteToken">清除</button>
        </div>
        <p class="muted-sm" style="margin-top:10px">
          本机：{{ writeTokenSaved ? '已填写（保存在本机 localStorage）' : '未填写' }}
          · 服务端：{{ about.writeTokenConfigured ? '已启用写接口保护' : '未启用写接口保护' }}
        </p>

        <div class="env-command" style="margin-top:16px">
          <div class="env-command-head">
            <span>{{ isWindows ? '服务器查询命令（Windows 不支持 journalctl）' : '服务器查询命令' }}</span>
            <span v-if="!isWindows" class="env-command-lang">bash</span>
            <span v-if="!isWindows" class="muted-sm">在服务器上执行，输出行冒号后即当前令牌</span>
            <button v-if="!isWindows" class="link-btn env-copy" @click="copyCommand(WRITE_TOKEN_CMD)">复制</button>
          </div>
          <pre v-if="!isWindows">{{ WRITE_TOKEN_CMD }}</pre>
        </div>
        <p v-if="isWindows" class="muted-sm" style="margin-top:8px">
          Windows 部署由计划任务拉起门户，没有 journald；请查看启动控制台输出或自行重定向日志。
        </p>
        <p v-else class="muted-sm" style="margin-top:8px">
          输出为空表示服务端未启用写接口保护。命令读取的是门户启动日志，
          令牌变更后需重启一次 <code>galworld-portal</code> 才会出现在最新日志里。
        </p>
      </section>

      <section id="upload-limits" class="panel">
        <div class="panel-head">
          <h2>上传限制</h2>
        </div>
        <p class="muted-sm" style="margin-bottom:14px">
          作用于所有游戏的编辑页。图片包含封面、图标和图片类宣传 CG；数量为单个游戏的上限。
        </p>
        <div class="setting-row">
          <span>图片单张上限</span>
          <input
            class="input" type="number" min="1" max="64"
            :value="settings.imageMaxMb"
            @change="setNumber('imageMaxMb', $event.target.value, 1, 64, '图片单张上限')"
          >
          <span class="muted-sm">MB</span>
        </div>
        <div class="setting-row">
          <span>视频单张上限</span>
          <input
            class="input" type="number" min="1" max="500"
            :value="settings.videoMaxMb"
            @change="setNumber('videoMaxMb', $event.target.value, 1, 500, '视频单张上限')"
          >
          <span class="muted-sm">MB</span>
        </div>
        <div class="setting-row">
          <span>封面数量上限</span>
          <input
            class="input" type="number" min="1" max="30"
            :value="settings.coverMaxCount"
            @change="setNumber('coverMaxCount', $event.target.value, 1, 30, '封面数量上限')"
          >
          <span class="muted-sm">张</span>
        </div>
        <div class="setting-row">
          <span>宣传 CG 数量上限</span>
          <input
            class="input" type="number" min="1" max="30"
            :value="settings.cgMaxCount"
            @change="setNumber('cgMaxCount', $event.target.value, 1, 30, '宣传 CG 数量上限')"
          >
          <span class="muted-sm">个</span>
        </div>
        <p class="muted-sm" style="margin-top:12px">
          当前：图片 {{ settings.imageMaxMb }} MB · 视频 {{ settings.videoMaxMb }} MB ·
          封面 {{ settings.coverMaxCount }} 张 · 宣传 CG {{ settings.cgMaxCount }} 个
        </p>
      </section>

      <section id="image-variant" class="panel">
        <div class="panel-head">
          <h2>图片预加载优化</h2>
        </div>
        <p class="muted-sm" style="margin-bottom:14px">
          按显示尺寸生成派生图，先出画面再补清晰度。
        </p>
        <div class="seg">
          <button
            :class="{ on: settings.imageVariantEnabled !== 'false' }"
            :disabled="busy"
            @click="setImageVariantEnabled(true)"
          >开启</button>
          <button
            :class="{ on: settings.imageVariantEnabled === 'false' }"
            :disabled="busy"
            @click="setImageVariantEnabled(false)"
          >关闭</button>
        </div>
        <p class="muted-sm" style="margin-top:12px">
          当前：{{ settings.imageVariantEnabled === 'false' ? '已关闭（全部使用原图）' : '已开启' }}
        </p>
        <p class="muted-sm" style="margin-top:6px">
          代价：磁盘多占用约 10%；极端情况下首帧清晰度略低于原图。
          关闭后立即回退原图，已生成的派生图不删除。
        </p>

        <div v-if="settings.imageVariantEnabled === 'false'" class="variant-cleanup">
          <div class="variant-cleanup-head">
            <h3>清理派生图</h3>
            <span v-if="variantScan" class="muted-sm">
              {{ variantScan.count }} 个文件 · {{ formatBytes(variantScan.bytes) }}
            </span>
          </div>
          <p class="muted-sm">
            关闭后不再生成新的派生图，已有文件仍占磁盘。清理只删除派生图，原图不受影响。
          </p>

          <div v-if="variantLoading" class="muted-sm">加载中…</div>
          <div v-else-if="variantScan && variantScan.files.length" class="variant-list">
            <div v-for="f in variantScan.files" :key="f.path" class="variant-row">
              <code>{{ f.path }}</code>
              <span class="muted-sm">{{ formatBytes(f.bytes) }}</span>
            </div>
          </div>
          <p v-else class="muted-sm">没有已生成的派生图。</p>

          <button
            class="btn danger"
            style="margin-top:12px"
            :disabled="clearingVariants || variantLoading || !variantScan || !variantScan.count"
            @click="clearVariants"
          >{{ clearingVariants ? '清理中…' : '清理全部派生图' }}</button>
        </div>
      </section>

      <section id="environment" class="panel">
        <div class="panel-head">
          <h2>环境信息</h2>
          <span class="muted-sm">按当前操作系统返回，点击切换方案</span>
        </div>

        <div class="env-os-switch">
          <button
            class="btn sm"
            :class="{ active: !previewOs }"
            @click="switchEnvOs('')"
          >当前系统</button>
          <button
            class="btn sm"
            :class="{ active: previewOs === 'LINUX' }"
            @click="switchEnvOs('LINUX')"
          >Linux</button>
          <button
            class="btn sm"
            :class="{ active: previewOs === 'WINDOWS' }"
            @click="switchEnvOs('WINDOWS')"
          >Windows</button>
        </div>

        <p v-if="envPreview" class="muted-sm env-preview-note">
          正在预览 {{ previewLabel }}：组件状态与权限不可探测，方案命令仅作参考，不能在当前机器上执行。
        </p>

        <div v-if="os" class="env-os">
          <span class="env-os-badge">{{ os.kind === 'WINDOWS' ? 'Windows' : os.kind === 'LINUX' ? 'Linux' : os.kind }}</span>
          <span v-if="!os.version?.includes(os.name)" class="env-os-name">{{ os.name }}</span>
          <span v-if="os.version" class="env-os-meta">{{ os.version }}</span>
          <span class="env-os-meta">{{ os.arch }}</span>
          <span class="env-os-meta">{{ os.host }}</span>
        </div>

        <div v-if="account && !envPreview" class="env-account">
          <span>当前账号 <b>{{ account.user }}</b></span>
          <span
            v-for="c in (account.checks || [])"
            :key="c.name"
            class="env-account-check"
            :class="{ ok: c.ok }"
          >
            {{ c.name }}：{{ c.detail }}
          </span>
        </div>

        <div class="artifact-sources">
          <h3>项目仓库</h3>
          <p class="muted-sm">
            显示在「设置 → 关于」的「代码仓库」链接。默认取部署配置 `GALWORLD_REPO_URL`，未配置时留空。
          </p>
          <label class="artifact-label">仓库地址</label>
          <input
            v-model="repoUrl"
            class="input"
            placeholder="https://example.com/owner/gal-world"
          />
          <div class="backup-bar" style="margin-top:10px">
            <button class="btn" :disabled="busy" @click="saveRepoUrl">保存仓库地址</button>
          </div>
          <p class="muted-sm" style="margin-top:14px">
            /api/health 是否下发仓库地址（未认证即可读取）。关闭后「设置 → 关于」显示为未公开。
          </p>
          <div class="seg">
            <button
              :class="{ on: settings.repoUrlPublic !== 'false' }"
              :disabled="busy"
              @click="setRepoUrlPublic(true)"
            >公开</button>
            <button
              :class="{ on: settings.repoUrlPublic === 'false' }"
              :disabled="busy"
              @click="setRepoUrlPublic(false)"
            >隐藏</button>
          </div>
          <p class="muted-sm" style="margin-top:12px">
            当前：{{ settings.repoUrlPublic === 'false' ? '已隐藏（health 不含 repoUrl）' : '已公开' }}
          </p>
        </div>

        <div class="artifact-sources">
          <h3>远程更新源</h3>
          <p class="muted-sm">
            「检查更新 / 远程更新」从这个发布接口取 Release。默认取部署配置
            `GALWORLD_UPDATE_API_BASE`、`GALWORLD_UPDATE_REPO`、`GALWORLD_UPDATE_WEB_BASE`；
            连不上默认站时改成可用的镜像或自建实例，保存后 Portal 与更新器都按新地址走。
          </p>
          <label class="artifact-label">发布接口根地址</label>
          <input
            v-model="updateApiBase"
            class="input"
            placeholder="https://gitee.com/api/v5/repos"
          />
          <label class="artifact-label">仓库路径</label>
          <input
            v-model="updateRepo"
            class="input"
            placeholder="owner/gal-world"
          />
          <label class="artifact-label">仓库网页根地址</label>
          <input
            v-model="updateWebBase"
            class="input"
            placeholder="https://gitee.com"
          />
          <div class="backup-bar" style="margin-top:10px">
            <button class="btn" :disabled="busy" @click="saveUpdateSource">保存更新源</button>
          </div>
          <p class="muted-sm" style="margin-top:12px">
            接口需兼容 GitHub / Gitee 的 Release API：`/repos/{owner}/{repo}/releases`。
            私有仓库的只读令牌仍由 `GALWORLD_UPDATE_TOKEN` 提供，不在这里配置。
          </p>
        </div>

        <div class="artifact-sources">
          <h3>元件下载源</h3>
          <p class="muted-sm">
            Nexus 优先；失败后按顺序回退镜像。私有仓库可在下方配置只读账号。
          </p>
          <label class="artifact-label">Nexus 仓库根地址</label>
          <input
            v-model="artifactNexus"
            class="input"
            placeholder="http://127.0.0.1:8081/repository/galworld-artifacts"
          />
          <label class="artifact-label">镜像前缀（每行一个，按顺序回退）</label>
          <textarea
            v-model="artifactMirrors"
            class="input artifact-mirrors"
            rows="3"
            placeholder="https://gh-proxy.com"
          />
          <div class="backup-bar" style="margin-top:10px">
            <button class="btn" :disabled="busy" @click="saveArtifactSources">保存下载源</button>
          </div>
          <p class="muted-sm" style="margin-top:8px">
            镜像规则：前缀 + 上游完整 URL，例如 https://gh-proxy.com/https://github.com/...
          </p>
        </div>

        <div v-if="!envPreview" class="deploy-panel">
          <div class="deploy-head">
            <h3>一键部署</h3>
            <span class="muted-sm">
              {{ deployOs ? deployOs + ' · ' : '' }}命令固定在后端，前端只能触发
            </span>
          </div>
          <p v-if="elevationWarning" class="muted-sm deploy-error">{{ elevationWarning }}</p>
          <div class="backup-bar">
            <button class="btn" :disabled="deployLoading" @click="loadDeployStatus">
              {{ deployLoading ? '读取中…' : '刷新部署状态' }}
            </button>
            <button
              class="btn primary"
              :disabled="!!deployRunId || installing || uninstalling || !deploySteps.length"
              @click="runInstallAll"
            >{{ installing ? '安装中…' : '一键安装' }}</button>
          </div>
          <!-- 服务端允许页面提权时，这里提前收密码：一键安装中途弹不出输入框，
               等到 NEED_ELEVATION 才填会打断整批执行 -->
          <div v-if="allowSudo" class="deploy-sudo deploy-sudo-bar">
            <input
              v-model="deploySudo"
              class="input"
              type="password"
              autocomplete="off"
              placeholder="sudo 密码（仅本次使用，不保存；留空则尝试免密 sudo）"
            />
            <span v-if="pendingAdminSteps" class="muted-sm">
              还有 {{ pendingAdminSteps }} 个步骤需要提权
            </span>
          </div>
          <p v-if="deployError" class="muted-sm deploy-error">{{ deployError }}</p>
          <p v-else-if="!deploySteps.length" class="muted-sm">
            解锁后显示可执行步骤。
          </p>

          <div v-if="deploySteps.length" class="deploy-steps">
            <div
              v-for="s in installSteps"
              :key="s.id"
              class="deploy-step"
              :class="{ 'cmds-open': cmdOpen(s.id) }"
            >
              <div
                class="deploy-step-main"
                role="button"
                tabindex="0"
                :aria-expanded="cmdOpen(s.id)"
                :title="cmdOpen(s.id) ? '收起命令' : '展开查看要执行的命令'"
                @click="toggleCmd(s.id)"
                @keydown.enter.prevent="toggleCmd(s.id)"
                @keydown.space.prevent="toggleCmd(s.id)"
              >
                <div class="deploy-step-title">
                  <span class="deploy-caret">{{ cmdOpen(s.id) ? '▾' : '▸' }}</span>
                  <b>{{ s.title }}</b>
                  <span class="deploy-badge" :class="DEPLOY_BADGE[s.state]">
                    {{ DEPLOY_LABEL[s.state] || s.state }}
                  </span>
                  <span class="deploy-badge kind">{{ s.kind }}</span>
                  <span v-if="s.destructive" class="deploy-badge bad">破坏性</span>
                </div>
                <p class="muted-sm">{{ s.summary }}</p>
                <p v-if="s.error" class="muted-sm deploy-error">{{ s.error }}</p>
              </div>
              <div v-if="s.state === 'NEED_ELEVATION' && allowSudo" class="deploy-sudo">
                <input
                  v-model="deploySudo"
                  class="input"
                  type="password"
                  autocomplete="off"
                  placeholder="sudo 密码（仅本次使用，不保存）"
                  @keyup.enter="runDeployStep(s, deploySudo)"
                />
                <button
                  class="btn sm"
                  :disabled="!!deployRunId || !deploySudo"
                  @click="runDeployStep(s, deploySudo)"
                >提权执行</button>
              </div>
              <button
                class="btn sm"
                :disabled="!!deployRunId || s.state === 'BLOCKED' || s.state === 'READY' || s.state === 'RUNNING'"
                @click="runDeployStep(s)"
              >运行</button>
              <pre v-if="cmdOpen(s.id)" class="deploy-cmd">{{ commandsOf(s) }}</pre>
            </div>
          </div>

          <div v-if="deployLines.length || deployRunId" class="deploy-log">
            <div class="deploy-log-head">
              <span>{{ deployRunStep }} · {{ deployRunId ? '执行中' : deployRunState }}</span>
              <button v-if="deployRunId" class="btn sm danger" @click="cancelDeploy">中止</button>
            </div>
            <pre>{{ deployLines.join('\n') }}</pre>
          </div>
        </div>

        <div v-if="!envPreview && uninstallSteps.length" class="deploy-panel danger-zone">
          <div class="deploy-head">
            <h3>卸载环境</h3>
            <span class="muted-sm">删除运行时组件，保留门户、游戏与存档</span>
          </div>
          <div class="deploy-steps">
            <div
              v-for="s in uninstallSteps"
              :key="s.id"
              class="deploy-step"
              :class="{ 'cmds-open': cmdOpen(s.id) }"
            >
              <div
                class="deploy-step-main"
                role="button"
                tabindex="0"
                :aria-expanded="cmdOpen(s.id)"
                :title="cmdOpen(s.id) ? '收起命令' : '展开查看要执行的命令'"
                @click="toggleCmd(s.id)"
                @keydown.enter.prevent="toggleCmd(s.id)"
                @keydown.space.prevent="toggleCmd(s.id)"
              >
                <div class="deploy-step-title">
                  <span class="deploy-caret">{{ cmdOpen(s.id) ? '▾' : '▸' }}</span>
                  <b>{{ s.title }}</b>
                  <span class="deploy-badge" :class="DEPLOY_BADGE[s.state]">
                    {{ DEPLOY_LABEL[s.state] || s.state }}
                  </span>
                  <span class="deploy-badge bad">破坏性</span>
                </div>
                <p class="muted-sm">{{ s.summary }}</p>
                <p v-if="s.error" class="muted-sm deploy-error">{{ s.error }}</p>
              </div>
              <div v-if="s.state === 'NEED_ELEVATION' && allowSudo" class="deploy-sudo">
                <input
                  v-model="deploySudo"
                  class="input"
                  type="password"
                  autocomplete="off"
                  placeholder="sudo 密码（仅本次使用，不保存）"
                  @keyup.enter="runDeployStep(s, deploySudo)"
                />
                <button
                  class="btn sm"
                  :disabled="!!deployRunId || !deploySudo"
                  @click="runDeployStep(s, deploySudo)"
                >提权执行</button>
              </div>
              <button
                class="btn sm"
                :disabled="!!deployRunId || uninstalling || s.state === 'BLOCKED' || s.state === 'READY'"
                @click="runDeployStep(s)"
              >运行</button>
              <pre v-if="cmdOpen(s.id)" class="deploy-cmd">{{ commandsOf(s) }}</pre>
            </div>
          </div>
          <div class="backup-bar" style="margin-top:10px">
            <button
              class="btn danger"
              :disabled="!!deployRunId || uninstalling || installing"
              @click="runUninstallAll"
            >{{ uninstalling ? '卸载中…' : '一键卸载' }}</button>
          </div>
        </div>

        <p v-if="envError" class="muted-sm">
          后端不可用，无法读取环境信息。请确认 galworld-portal 服务正在运行。
        </p>

        <div v-if="environment.length" class="env-schemes">
          <button
            v-for="s in environment"
            :key="s.key"
            class="env-scheme"
            :class="{ active: selectedEnvKey === s.key }"
            @click="selectedEnvKey = s.key"
          >
            <span class="env-scheme-top">
              <span class="env-name">{{ s.name }}</span>
              <span class="env-status" :class="s.status">
                {{ STATUS_TEXT[s.status] || s.status }}
              </span>
            </span>
            <span class="env-tagline">{{ s.tagline }}</span>
          </button>
        </div>

        <div v-if="selectedEnv" class="env-detail-panel">
          <div class="env-detail-head">
            <h3>{{ selectedEnv.name }}</h3>
            <span class="env-status" :class="selectedEnv.status">
              {{ STATUS_TEXT[selectedEnv.status] || selectedEnv.status }}
            </span>
          </div>
          <p class="env-tagline" style="margin:0 0 14px">{{ selectedEnv.tagline }}</p>

          <p v-if="selectedEnv.description" class="env-description">
            {{ selectedEnv.description }}
          </p>

          <div v-if="selectedEnv.requirements?.length" class="env-block">
            <div class="env-group-name">前置条件</div>
            <ul class="env-requirements">
              <li v-for="r in selectedEnv.requirements" :key="r">{{ r }}</li>
            </ul>
          </div>

          <div v-if="selectedEnv.pros?.length || selectedEnv.cons?.length" class="env-proscons">
            <div class="env-proscons-col">
              <div class="env-group-name">优点</div>
              <ul>
                <li v-for="p in (selectedEnv.pros || [])" :key="p">{{ p }}</li>
              </ul>
            </div>
            <div class="env-proscons-col">
              <div class="env-group-name">缺点 / 取舍</div>
              <ul class="cons">
                <li v-for="c in (selectedEnv.cons || [])" :key="c">{{ c }}</li>
              </ul>
            </div>
          </div>

          <div v-for="g in selectedEnv.groups" :key="g.name" class="env-group">
            <button class="env-group-toggle" @click="toggleGroup(g.name)">
              <span class="env-group-name">{{ g.name }}</span>
              <span v-if="g.summary" class="env-group-summary">{{ g.summary }}</span>
              <span v-if="g.health" class="env-status" :class="g.health">
                {{ HEALTH_TEXT[g.health] || g.health }}
              </span>
              <span class="env-group-arrow">{{ openGroup === g.name ? '−' : '+' }}</span>
            </button>
            <div v-if="openGroup === g.name" class="env-items">
              <div v-for="it in g.items" :key="it.name" class="env-item">
                <div class="env-head">
                  <span class="env-name">{{ it.name }}</span>
                  <span
                    class="env-status"
                    :class="it.health && selectedEnv.status === 'active' ? it.health : it.status"
                  >
                    {{ it.health && selectedEnv.status === 'active'
                      ? (HEALTH_TEXT[it.health] || it.health)
                      : (STATUS_TEXT[it.status] || it.status) }}
                  </span>
                </div>
                <div class="env-detail mono">{{ it.detail }}</div>
                <ul v-if="it.details?.length" class="env-details">
                  <li v-for="d in it.details" :key="d">{{ d }}</li>
                </ul>
              </div>
            </div>
          </div>

          <div v-if="selectedEnv.commands?.length" class="env-block">
            <div class="env-group-name">
              部署命令（参考，不会自动执行）
            </div>
            <div v-for="c in selectedEnv.commands" :key="c.title" class="env-command">
              <div class="env-command-head">
                <span>{{ c.title }}</span>
                <span v-if="c.shell" class="env-command-lang">{{ c.shell }}</span>
                <span v-if="c.note" class="muted-sm">{{ c.note }}</span>
                <button class="link-btn env-copy" @click="copyCommand(c.command)">复制</button>
              </div>
              <pre>{{ c.command }}</pre>
            </div>
          </div>

          <div v-if="selectedEnv.permissions?.length" class="env-block">
            <div class="env-group-name">
              {{ selectedEnv.status === 'active' ? '部署目录检查' : '权限前置检查' }}
            </div>
            <div class="env-perms">
              <div
                v-for="p in selectedEnv.permissions"
                :key="p.name"
                class="env-perm"
                :class="{ ok: p.ok }"
              >
                <span class="env-perm-dot">{{ p.ok ? '✓' : '!' }}</span>
                <span class="env-perm-name">{{ p.name }}</span>
                <span class="env-perm-detail">{{ p.detail }}</span>
              </div>
            </div>
          </div>

          <div v-if="selectedEnv.elevate?.length" class="env-block">
            <div class="env-group-name">前置提权</div>
            <div v-for="c in selectedEnv.elevate" :key="c.title" class="env-command">
              <div class="env-command-head">
                <span>{{ c.title }}</span>
                <span v-if="c.shell" class="env-command-lang">{{ c.shell }}</span>
                <span v-if="c.note" class="muted-sm">{{ c.note }}</span>
                <button class="link-btn env-copy" @click="copyCommand(c.command)">复制</button>
              </div>
              <pre>{{ c.command }}</pre>
            </div>
          </div>

          <div v-if="selectedEnv.restore?.length" class="env-block">
            <div class="env-group-name">部署后恢复权限</div>
            <div v-for="c in selectedEnv.restore" :key="c.title" class="env-command">
              <div class="env-command-head">
                <span>{{ c.title }}</span>
                <span v-if="c.shell" class="env-command-lang">{{ c.shell }}</span>
                <span v-if="c.note" class="muted-sm">{{ c.note }}</span>
                <button class="link-btn env-copy" @click="copyCommand(c.command)">复制</button>
              </div>
              <pre>{{ c.command }}</pre>
            </div>
          </div>
        </div>
      </section>

      <section id="update" class="panel">
        <div class="panel-head">
          <h2>版本与检查更新</h2>
        </div>
        <div class="setting-row">
          <span>版本</span>
          <a v-if="versionTagUrl" :href="versionTagUrl" target="_blank" rel="noopener" class="mono meta-link">
            {{ about.version || 'dev' }}
          </a>
          <span v-else class="mono">{{ about.version || 'dev' }}</span>
          <button class="btn compact" :disabled="updateBusy" @click="checkUpdate">
            {{ updateBusy ? '检查中…' : '检查更新' }}
          </button>
        </div>
        <div
          v-if="updateResult"
          class="update-result"
          :class="{ ok: updateResult.hasUpdate }"
        >
          <span>{{ updateResult.message }}</span>
          <a
            v-if="updateResult.hasUpdate"
            :href="updateResult.releaseUrl"
            target="_blank"
            rel="noopener"
            class="meta-link"
          >查看 Release</a>
        </div>
        <div v-if="updateResult?.hasUpdate" class="update-actions">
          <button
            class="btn primary sm"
            :disabled="updateApplying || !canApply(updateResult, updateStatus)"
            @click="applyUpdate"
          >{{ updateApplying ? '启动中…' : '立即更新' }}</button>
        </div>
        <div v-if="updateResult?.overrideEnabled" class="update-actions">
          <input
            v-model="overrideRef"
            class="input"
            placeholder="v0.1.4-pr7 或 0.1.3"
            :disabled="updateApplying"
          >
          <button
            class="btn sm"
            :disabled="updateApplying || !canOverride(updateResult?.overrideEnabled, overrideRef, updateStatus)"
            @click="applyOverrideUpdate"
          >覆盖更新</button>
        </div>
        <p v-if="updateResult?.overrideEnabled" class="muted-sm">
          覆盖更新允许降级与同版本重装；目标版本必须自带更新接口，否则更新器会在停服前拒绝。
        </p>
        <div v-if="updateStatus.phase !== 'idle'" class="update-actions">
          <span class="muted-sm">
            {{ phaseLabel(updateStatus.phase) }} {{ updateStatus.message }}
          </span>
        </div>
        <div class="setting-row">
          <span>代码仓库</span>
          <a v-if="repoBase" :href="repoBase" target="_blank" rel="noopener" class="meta-link">
            {{ repoBase }}
          </a>
          <span v-else class="muted-sm">未设置</span>
        </div>
        <div class="setting-row">
          <span>发布版本</span>
          <a v-if="RELEASES_PAGE" :href="RELEASES_PAGE" target="_blank" rel="noopener" class="meta-link">
            {{ RELEASES_PAGE }}
          </a>
          <span v-else class="muted-sm">未设置仓库地址</span>
        </div>
      </section>
    </template>
  </div>
</template>
