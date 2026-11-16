<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { confirmAction, notify } from '../feedback'
import { formatPlaytime } from '../format'
import { backTarget } from '../list-origin'
import { playLink } from '../play-origin'
import { useGameLaunch } from '../use-game-launch'
import { useLatency } from '../use-latency'
import { useSessionRefresh } from '../use-session-refresh'
import { companyEnabled, localizationEnabled } from '../feature-flags'
import { latencyTone } from '../latency'
import MediaRotator from '../components/MediaRotator.vue'

const props = defineProps({ id: { type: String, required: true } })
const router = useRouter()
const route = useRoute()

/**
 * 返回按钮的目标由 URL 的 ?from= 决定：
 * 从列表页进来回列表并还原筛选，从首页或直接访问则回首页。
 * 用计算属性是因为用户可能在同一页面内切换游戏（详情页之间跳转）。
 */
const back = computed(() => backTarget(route))

const game = ref(null)
const loading = ref(true)
const error = ref('')

const saves = ref({ saveDirs: [], backups: [] })
/** 存档面板默认收起：没有备份时它只是一块占位，把详情页撑得很长 */
const savesOpen = ref(false)
watch(() => saves.value.backups?.length, (n) => {
  if (n > 0) savesOpen.value = true
}, { immediate: true })
const saveLabel = ref('')
const saveDescription = ref('')
const savesBusy = ref(false)
/** 行内编辑：正在改标签/描述的备份名 */
const editingName = ref('')
const editLabel = ref('')
const editDescription = ref('')

const SAVE_KIND_TEXT = { MANUAL: '手动', ORIGINAL: '原始', PRE_RESTORE: '自动快照' }

/** 打包下载任务：两段式，先打包再下载 */
const downloadJob = ref(null)
const downloadBusy = ref(false)
/** 打包前预估：本地文件大小与预计耗时 */
const packageEstimate = ref(null)
let downloadTimer = null
const downloadPercent = computed(() => {
  const j = downloadJob.value
  if (!j || !j.totalBytes) return 0
  return Math.min(100, Math.round((j.doneBytes / j.totalBytes) * 100))
})
const playtime = ref(null)
const cgSound = ref(false)
const switchMode = ref('page')
const rotationEnabled = ref(true)
/** 详情页大屏展示偏好：cg=优先 CG，cover=只看封面。游戏级选择优先，未选择时跟随全局 */
const mediaMode = ref('cg')
/** CG 层是否已就绪（首个图片 onload / 首个视频 canplay） */
const cgReady = ref(false)
/** 用户手动点了 CG：即使还没就绪也先切过去，内容区显示加载进度 */
const manualCg = ref(false)
/** CG 首批数据加载进度（0-100；null 表示算不出，显示不确定态） */
const cgPercent = ref(null)
/** CG 全失败：回退封面，避免一直停在加载中 */
const cgFailed = ref(false)

const hasCg = computed(() => (game.value?.cgs || []).length > 0)
const hasCover = computed(() => (game.value?.covers || []).length > 0)
const bothMedia = computed(() => hasCg.value && hasCover.value)

/** 是否加载 CG：偏好 CG，或根本没有封面（退回 CG） */
const prefersCg = computed(() => hasCg.value && (mediaMode.value === 'cg' || !hasCover.value))
/** CG 是否已接管画面：就绪，或用户手动要求先看加载 */
const cgVisible = computed(() => prefersCg.value && (!hasCover.value || cgReady.value || manualCg.value))
/** 封面层：有封面，且 CG 还没接管 */
const coverShown = computed(() => hasCover.value && !cgVisible.value)
/** 内容区正在等 CG：显示加载提示与进度 */
const cgPending = computed(() => prefersCg.value && cgVisible.value && !cgReady.value && !cgFailed.value)
/** 按钮高亮跟随「实际展示的内容」，而不是偏好 */
const shownMode = computed(() => (cgVisible.value ? 'cg' : 'cover'))

/** 首个 CG 媒体加载完成：正式切到 CG */
function onCgItemReady() {
  cgReady.value = true
  cgPercent.value = 100
}

/** CG 全部加载失败：回退封面 */
function onCgFailed() {
  cgFailed.value = true
  manualCg.value = false
  cgReady.value = false
}

// 切换偏好或换游戏时重置，重新走「封面 → 加载 → CG」
watch([mediaMode, () => props.id], () => {
  cgReady.value = false
  manualCg.value = false
  cgFailed.value = false
  cgPercent.value = null
})

/** 切换立即写回游戏配置，刷新或换浏览器都保持 */
async function selectMedia(mode) {
  if (mode === 'cg') {
    // 未就绪时手动点 CG：立刻切过去，内容区显示加载进度
    if (!cgReady.value) manualCg.value = true
    if (mediaMode.value === mode) return
  } else {
    manualCg.value = false
    if (mediaMode.value === mode) return
  }
  mediaMode.value = mode
  try {
    await api.setMediaMode(props.id, mode)
  } catch (e) {
    notify.error('保存展示偏好失败：' + e.message)
  }
}

const STATE_TEXT = {
  RUNNING: '运行中',
  STOPPED: '已停止',
  FAILED: '异常',
  UNKNOWN: '未知',
  UNSUPPORTED: '不支持',
}

const running = computed(() => game.value?.state === 'RUNNING')

const { busy, launches, toggle: sessionToggle, restart: sessionRestart } = useGameLaunch({
  idOf: () => props.id,
  onError: (msg) => notify.error(msg),
  onStarted: () => {
    if (game.value) game.value = { ...game.value, state: 'RUNNING', crashed: false }
  },
  onStopped: () => {
    if (game.value) game.value = { ...game.value, state: 'STOPPED', crashed: false }
    notify.success('游戏已关闭')
  },
})

/** 当前进行中的启动/关闭进度 */
const action = computed(() => launches[props.id] || null)
const { latency } = useLatency(running)

/** 状态监听：定时拉 /session，刷新详情页游戏状态 */
useSessionRefresh(
  () => [props.id],
  (id, state, st) => {
    if (!game.value) return
    if (state === 'UNKNOWN' && game.value.state === 'RUNNING') return
    if (game.value.state === state && !!game.value.crashed === !!st.crashed) return
    game.value = { ...game.value, state, crashed: !!st.crashed }
  },
  { intervalMs: 3000 },
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    game.value = await api.getGame(props.id)
    saves.value = await api.listSaves(props.id)
    playtime.value = await api.playtime(props.id)
    const s = await api.settings()
    switchMode.value = s.switchMode || 'page'
    cgSound.value = s.cgSound === 'true'
    rotationEnabled.value = s.rotationEnabled !== 'false'
    // 游戏级选择优先；未单独选过则跟随全局默认（全局也没配就按 CG）
    if (game.value.mediaMode === 'cover' || game.value.mediaMode === 'cg') {
      mediaMode.value = game.value.mediaMode
    } else {
      mediaMode.value = s.defaultMediaMode === 'cover' ? 'cover' : 'cg'
    }
    await resumeDownload()
    await loadEstimate()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function start() {
  const started = await sessionToggle({ id: props.id, state: game.value?.state || 'STOPPED' })
  if (started) openPlay()
}

/** 按设置决定当前页跳转还是新标签页 */
function openPlay() {
  const href = playLink(props.id, 'game')
  if (switchMode.value === 'tab') {
    window.open(`${location.origin}${href}`, '_blank')
  } else {
    router.push(href)
  }
}

/** 崩溃后重启：先关闭残留会话，再启动并进入 */
async function restart() {
  const started = await sessionRestart({ id: props.id, state: game.value?.state })
  if (started) openPlay()
}

async function stop() {
  await sessionToggle({ id: props.id, state: 'RUNNING' })
}

async function doBackup() {
  savesBusy.value = true
  try {
    saves.value = await api.backupSaves(props.id, saveLabel.value, saveDescription.value)
    saveLabel.value = ''
    saveDescription.value = ''
    notify.success('存档已备份')
  } catch (e) {
    notify.error('备份失败：' + e.message)
  } finally {
    savesBusy.value = false
  }
}

function startEdit(b) {
  editingName.value = b.name
  editLabel.value = b.label || ''
  editDescription.value = b.description || ''
}

function cancelEdit() {
  editingName.value = ''
  editLabel.value = ''
  editDescription.value = ''
}

async function saveEdit(name) {
  savesBusy.value = true
  try {
    saves.value = await api.updateSave(props.id, name, editLabel.value, editDescription.value)
    cancelEdit()
    notify.success('备份信息已更新')
  } catch (e) {
    notify.error('更新失败：' + e.message)
  } finally {
    savesBusy.value = false
  }
}

/** 切换存档：后端会先把当前存档快照成 pre-restore，再解压目标备份 */
async function doSwitch(b) {
  const desc = b.label ? `「${b.label}」` : '该备份'
  if (!await confirmAction(
    `切换当前存档到${desc}？当前进度会先自动快照一份（pre-restore），切错了可以再切回来。`,
    { title: '切换存档', confirmText: '切换' },
  )) return
  savesBusy.value = true
  try {
    await api.switchSave(props.id, b.name)
    saves.value = await api.listSaves(props.id)
    notify.success('已切换存档（切换前的状态已自动备份）')
  } catch (e) {
    notify.error('切换失败：' + e.message)
  } finally {
    savesBusy.value = false
  }
}

async function doDelete(b) {
  if (!await confirmAction(`删除备份 ${b.name}？此操作不可撤销。`, {
    title: '删除备份', confirmText: '删除',
  })) return
  savesBusy.value = true
  try {
    saves.value = await api.deleteSave(props.id, b.name)
    if (editingName.value === b.name) cancelEdit()
    notify.success('备份已删除')
  } catch (e) {
    notify.error('删除失败：' + e.message)
  } finally {
    savesBusy.value = false
  }
}

// ---------------- 游戏打包下载 ----------------

/** 打包任务进度只存后端内存；刷新后 jobId 丢了，靠这个接口找回并继续轮询 */
async function resumeDownload() {
  try {
    const job = await api.currentDownloadPackage(props.id)
    if (!job) {
      downloadJob.value = null
      downloadBusy.value = false
      return
    }
    downloadJob.value = job
    const running = job.state !== 'READY' && job.state !== 'FAILED' && job.state !== 'CANCELLED'
    downloadBusy.value = running
    if (running) pollDownload()
  } catch {
    // 查询失败不影响页面其它内容；用户重新点打包即可
  }
}

async function prepareDownload() {
  downloadBusy.value = true
  try {
    downloadJob.value = await api.packageGame(props.id)
    pollDownload()
  } catch (e) {
    downloadBusy.value = false
    notify.error('打包失败：' + e.message)
  }
}

/** 预估失败可忽略：面板只是少一行提示，不影响打包 */
async function loadEstimate() {
  try {
    packageEstimate.value = await api.packageEstimate(props.id)
  } catch {
    packageEstimate.value = null
  }
}

function pollDownload() {
  clearTimeout(downloadTimer)
  downloadTimer = setTimeout(async () => {
    if (!downloadJob.value) return
    try {
      const job = await api.downloadJob(props.id, downloadJob.value.jobId)
      downloadJob.value = job
      if (job.state === 'READY') {
        downloadBusy.value = false
        notify.success('打包完成，可以下载压缩包')
      } else if (job.state === 'FAILED' || job.state === 'CANCELLED') {
        downloadBusy.value = false
        notify.error(job.state === 'FAILED'
          ? '打包失败：' + (job.error || '未知错误')
          : '打包已取消')
      } else {
        pollDownload()
      }
    } catch (e) {
      downloadBusy.value = false
      notify.error('查询打包进度失败：' + e.message)
    }
  }, 1000)
}

function startDownload() {
  if (!downloadJob.value) return
  window.location.href = api.downloadPackageUrl(props.id, downloadJob.value.jobId)
}

onBeforeUnmount(() => clearTimeout(downloadTimer))

function fmtSize(n) {
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(2) + ' MB'
  return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

/** 预计耗时的口语化展示；调用方自己加「约」等前缀 */
function fmtDuration(totalSeconds) {
  const s = Math.max(0, Math.round(totalSeconds))
  if (s < 60) return s + ' 秒'
  const m = Math.floor(s / 60)
  if (m < 60) return s % 60 ? `${m} 分 ${s % 60} 秒` : `${m} 分钟`
  const h = Math.floor(m / 60)
  return m % 60 ? `${h} 小时 ${m % 60} 分` : `${h} 小时`
}

function fmtTime(iso) {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime()) || d.getFullYear() < 2000) return '—'
  const p = (x) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

onMounted(load)
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <router-link :to="back" class="ghost-btn">{{ back.label }}</router-link>
      <div class="spacer" />
      <router-link :to="`/game/${id}/edit`" class="ghost-btn gear-btn" title="编辑此游戏">⚙ 编辑</router-link>
    </header>

    <div v-if="loading" class="loading">加载中…</div>
    <div v-else-if="error" class="empty">{{ error }}</div>
    <div v-else-if="!game" class="empty">没有这个游戏</div>

    <template v-else>
      <div class="detail-hero">
        <!-- 左：标题 + 时长 + 操作 -->
        <div class="hero-left">
          <div class="hero-title">
            <img
              v-if="game.iconPath"
              :src="game.iconPath"
              class="hero-icon"
              :alt="game.name"
            />
            <h1>{{ game.name }}</h1>
          </div>
          <div class="sub">
            {{ game.width }}×{{ game.height }} ·
            状态 {{ STATE_TEXT[game.state] || game.state }}
            <span
              v-if="running && latency != null"
              class="latency-inline"
              :class="latencyTone(latency)"
            >· 时延 {{ latency }} ms</span>
          </div>

          <!-- 公司 / 标签信息卡片 -->
          <div class="game-meta-card">
            <div v-if="companyEnabled" class="meta-line">
              <span class="meta-label">公司</span>
              <span class="meta-values">
                <template v-for="c in (game.companies || [])" :key="c.id">
                  <a
                    v-if="c.website"
                    :href="c.website"
                    target="_blank"
                    rel="noopener"
                    class="meta-link"
                    :title="c.description || undefined"
                  >{{ c.name }}<span v-if="c.alias" class="company-alias">（{{ c.alias }}）</span></a>
                  <span v-else :title="c.description || undefined">{{ c.name }}<span v-if="c.alias" class="company-alias">（{{ c.alias }}）</span></span>
                </template>
                <span v-if="!game.companies || !game.companies.length" class="muted-sm">未填写</span>
              </span>
            </div>
            <div v-if="localizationEnabled" class="meta-line">
              <span class="meta-label">汉化组</span>
              <span class="meta-values">
                <template v-for="g in (game.localizations || [])" :key="g.id">
                  <a
                    v-if="g.website"
                    :href="g.website"
                    target="_blank"
                    rel="noopener"
                    class="meta-link"
                    :title="g.description || undefined"
                  >{{ g.name }}<span v-if="g.alias" class="company-alias">（{{ g.alias }}）</span></a>
                  <span v-else :title="g.description || undefined">{{ g.name }}<span v-if="g.alias" class="company-alias">（{{ g.alias }}）</span></span>
                </template>
                <span v-if="!game.localizations || !game.localizations.length" class="muted-sm">未填写</span>
              </span>
            </div>
            <div class="meta-line">
              <span class="meta-label">标签</span>
              <span class="meta-values">
                <span v-for="t in (game.tags || [])" :key="t" class="mini-tag">{{ t }}</span>
                <span v-if="!game.tags || !game.tags.length" class="muted-sm">未填写</span>
              </span>
            </div>
          </div>

          <div class="stats" v-if="playtime">
            <div class="stat">
              <span class="stat-label">累计游玩</span>
              <span class="stat-value">{{ formatPlaytime(playtime.totalSeconds) }}</span>
            </div>
            <div class="stat">
              <span class="stat-label">今日</span>
              <span class="stat-value">{{ formatPlaytime(playtime.todaySeconds) }}</span>
            </div>
            <div class="stat">
              <span class="stat-label">游玩次数</span>
              <span class="stat-value">{{ playtime.sessions }}</span>
            </div>
          </div>

          <div class="actions">
            <button v-if="running" class="btn primary sm" :disabled="busy || !!action" @click="openPlay">进入游戏</button>
            <button v-else-if="game.state === 'FAILED' || game.crashed" class="btn primary sm" :disabled="busy || !!action" @click="restart">
              {{ action?.action === 'start' ? '启动中 ' + action.percent + '%' : '重新启动' }}
            </button>
            <button v-else class="btn primary sm" :disabled="busy || !!action" @click="start">
              {{ action?.action === 'start' ? '启动中 ' + action.percent + '%' : '启动游戏' }}
            </button>
            <button v-if="running" class="btn danger" :disabled="busy || !!action" @click="stop">
              {{ action?.action === 'stop' ? '关闭中 ' + action.percent + '%' : '关闭游戏' }}
            </button>
          </div>
          <div v-if="action" class="action-progress">
            <div class="action-progress-track">
              <span class="action-progress-fill" :style="{ width: action.percent + '%' }" />
            </div>
            <div class="action-progress-label">
              <span>{{ action.label }}</span>
              <span>{{ action.percent }}%</span>
            </div>
          </div>

          <div v-if="!running && (game.state === 'FAILED' || game.crashed)" class="warn-box">
            会话已退出（游戏自己关闭或崩溃）。游戏内进度若未存档会丢失，
            点击「重新启动」重新拉起会话；存档可在下方「存档备份」里恢复。
          </div>
          <div v-else-if="!running" class="muted-sm" style="margin-top:14px">
            游戏未启动，点击「启动游戏」开始会话。
          </div>

          <p style="color:var(--muted);font-size:12.5px;margin-top:22px">
            离开页面不会中断游戏，随时回来都能继续。
          </p>
        </div>

        <!-- 中：封面 / CG 大屏展示，右上角切换 -->
        <div class="hero-media">
          <div v-if="bothMedia" class="seg" style="align-self:flex-end">
            <button
              :class="{ on: shownMode === 'cover' }"
              @click="selectMedia('cover')"
            >封面</button>
            <button
              :class="{ on: shownMode === 'cg' }"
              @click="selectMedia('cg')"
            >CG</button>
          </div>
          <div class="hero-center">
            <MediaRotator
              v-if="coverShown"
              key="cover"
              :items="game.covers"
              :name="game.name"
              :interval="game.coverRotationInterval || 6"
              :mode="game.coverRotationMode || 'seq'"
              video-behavior="autoNext"
              :show-dots="game.covers.length > 1"
              :enabled="rotationEnabled"
              sizes="(max-width: 900px) 100vw, 45vw"
              priority="high"
              original-only
            />
            <MediaRotator
              v-if="prefersCg"
              key="cg"
              :class="['hero-cg-layer', { 'is-hidden': !cgVisible }]"
              :items="game.cgs"
              :name="game.name"
              :interval="8"
              :mode="game.cgRotationOrder || 'seq'"
              :video-behavior="game.cgRotationMode || 'autoNext'"
              :default-sound="cgSound"
              :show-dots="game.cgs.length > 1"
              :enabled="rotationEnabled"
              sizes="(max-width: 900px) 100vw, 45vw"
              priority="high"
              original-only
              @item-ready="onCgItemReady"
              @load-progress="(p) => { cgPercent = p }"
              @all-failed="onCgFailed"
            />
            <div v-if="cgPending" class="cg-loading">
              <div class="cg-loading-title">CG 正在加载中…</div>
              <div class="cg-loading-track">
                <span
                  class="cg-loading-fill"
                  :class="{ indeterminate: cgPercent === null }"
                  :style="cgPercent === null ? {} : { width: cgPercent + '%' }"
                />
              </div>
              <div class="muted-sm">
                {{ cgPercent === null ? '正在读取数据…' : cgPercent + '%' }}
              </div>
            </div>
            <div v-if="!hasCover && !hasCg" class="hero-placeholder">
              <span>{{ game.name.slice(0, 1) }}</span>
              <p class="muted-sm">还没有宣传图或 CG</p>
            </div>
          </div>
        </div>
      </div>

      <section class="panel">
        <div class="panel-head">
          <h2>简介</h2>
        </div>
        <div v-if="game.description" class="desc">{{ game.description }}</div>
        <div v-else class="desc" style="color:var(--muted)">暂无简介。</div>
      </section>

      <!-- 游戏文件下载：先打包，再下载 -->
      <section class="panel">
        <div class="panel-head">
          <h2>游戏文件下载</h2>
          <span class="muted-sm">打包成 zip 后下载</span>
        </div>
        <p class="muted-sm">
          把游戏目录原样打包。门户生成的 <code>game.env</code> 与 <code>wineprefix/</code>
          不包含在内，换机器后需要重新接入。
        </p>
        <p
          v-if="packageEstimate && packageEstimate.totalBytes > 0"
          class="muted-sm"
          style="margin-top:6px"
        >
          本地文件 {{ fmtSize(packageEstimate.totalBytes) }} ·
          {{ packageEstimate.totalFiles }} 个文件<span
            v-if="packageEstimate.estimatedSeconds > 0"
          > · 预计打包约 {{ fmtDuration(packageEstimate.estimatedSeconds) }}</span>
        </p>
        <div class="backup-bar" style="margin-top:12px;align-items:center">
          <button class="btn" :disabled="downloadBusy || running" @click="prepareDownload">
            {{ downloadBusy
              ? '打包中 ' + downloadPercent + '%'
              : (downloadJob?.state === 'READY' ? '重新打包' : '第一步：打包为 zip') }}
          </button>
          <button
            v-if="downloadJob?.state === 'READY'"
            class="btn primary sm"
            @click="startDownload"
          >第二步：下载压缩包</button>
          <span v-if="running" class="muted-sm">请先关闭游戏再打包</span>
          <span v-else-if="downloadJob?.state === 'READY'" class="muted-sm">
            {{ fmtSize(downloadJob.doneBytes)
            }}<template v-if="downloadJob.totalFiles > 0"> · {{ downloadJob.totalFiles }} 个文件</template>
          </span>
        </div>
        <div
          v-if="downloadBusy || (downloadJob && downloadJob.state !== 'READY' && downloadJob.state !== 'FAILED')"
          class="action-progress"
        >
          <div class="action-progress-track">
            <span class="action-progress-fill" :style="{ width: downloadPercent + '%' }" />
          </div>
          <div class="action-progress-label">
            <span>{{ downloadJob?.state === 'QUEUED' ? '排队中' : '正在打包游戏文件' }}</span>
            <span>{{ downloadPercent }}%</span>
          </div>
        </div>
        <p v-if="downloadJob?.state === 'FAILED'" class="muted-sm" style="color:var(--warn)">
          {{ downloadJob.error || '打包失败' }}
        </p>
        <p class="muted-sm" style="margin-top:10px">
          打包完成后可下载 {{ downloadJob?.fileName || '游戏压缩包' }}；产物保留 24 小时，过期自动清理。
        </p>
      </section>

      <!-- 存档管理与切换 -->
      <section class="panel">
        <div class="panel-head">
          <h2>存档管理与切换</h2>
          <span class="muted-sm">
            存档目录：{{ saves.saveDirs.length ? saves.saveDirs.join('、') : '未找到' }}
          </span>
          <button class="link-btn" @click="savesOpen = !savesOpen">
            {{ savesOpen ? '收起 ▴' : '展开 ▾' }}
          </button>
        </div>

        <p v-if="!savesOpen" class="muted-sm">
          {{ saves.backups?.length ? saves.backups.length + ' 份备份（可切换）' : '暂无存档备份' }}
        </p>

        <template v-else>
          <div class="backup-bar">
            <input
              v-model="saveLabel"
              class="input"
              placeholder="标签（可选，如 第一章结束）"
              maxlength="40"
              @keyup.enter="doBackup"
            />
            <input
              v-model="saveDescription"
              class="input"
              placeholder="描述（可选，如 全 CG 收集 / 走完 A 线）"
              maxlength="500"
              @keyup.enter="doBackup"
            />
            <button class="btn" :disabled="savesBusy || !saves.saveDirs.length" @click="doBackup">
              立即备份
            </button>
          </div>

          <p v-if="!saves.saveDirs.length" class="muted-sm" style="margin-top:10px">
            游戏目录下没有 savedata_cn / savedata_jp / savedata / save 目录，无法备份。
          </p>

          <table v-else-if="saves.backups.length" class="saves">
            <thead>
              <tr>
                <th>时间</th><th>类型</th><th>标签与描述</th><th>大小</th>
                <th style="text-align:right">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="b in saves.backups" :key="b.name">
                <td class="mono">{{ fmtTime(b.createdAt) }}</td>
                <td>
                  <span class="save-kind" :class="b.kind">
                    {{ SAVE_KIND_TEXT[b.kind] || b.kind }}
                  </span>
                </td>
                <td>
                  <template v-if="editingName === b.name">
                    <input
                      v-model="editLabel"
                      class="input"
                      placeholder="标签"
                      maxlength="40"
                      style="margin-bottom:6px"
                    />
                    <input
                      v-model="editDescription"
                      class="input"
                      placeholder="描述"
                      maxlength="500"
                    />
                  </template>
                  <template v-else>
                    <span v-if="b.label" class="tag">{{ b.label }}</span>
                    <span v-else class="muted-sm">无标签</span>
                    <div v-if="b.description" class="muted-sm" style="margin-top:4px">
                      {{ b.description }}
                    </div>
                  </template>
                </td>
                <td class="mono">{{ fmtSize(b.sizeBytes) }}</td>
                <td style="text-align:right; white-space:nowrap">
                  <template v-if="editingName === b.name">
                    <button class="link-btn" :disabled="savesBusy" @click="saveEdit(b.name)">保存</button>
                    <button class="link-btn" :disabled="savesBusy" @click="cancelEdit">取消</button>
                  </template>
                  <template v-else>
                    <button class="link-btn" :disabled="savesBusy" @click="doSwitch(b)">切换到此存档</button>
                    <button class="link-btn" :disabled="savesBusy" @click="startEdit(b)">编辑</button>
                    <button class="link-btn danger" :disabled="savesBusy" @click="doDelete(b)">删除</button>
                  </template>
                </td>
              </tr>
            </tbody>
          </table>

          <p v-else class="muted-sm" style="margin-top:10px">还没有备份。</p>

          <p class="muted-sm" style="margin-top:14px">
            切换前会自动对当前存档做一份 <code>pre-restore</code> 快照，切错了可以再切回来；
            游戏运行中会拒绝切换（先关闭游戏）。「原始」是导入游戏时自动留存的原始存档。
          </p>
        </template>
      </section>
    </template>
  </div>
</template>
