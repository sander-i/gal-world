<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
import { t } from '../i18n.js'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, getWriteToken } from '../api'
import GameUploadPanel from '../components/GameUploadPanel.vue'
import { notify } from '../feedback'
import { canSubmit, mergeEvent, runPhase, stepOf, formatBytes, altOptions } from '../game-import'
import { playLink } from '../play-origin'

const router = useRouter()

const loading = ref(true)
const error = ref('')
const step = ref('source')        // source | configure | running
const candidates = ref([])
const warnings = ref([])
const supported = ref(true)
const slots = ref(null)
const selected = ref(null)
// 上传来源的会话信息：返回上一步时保留「继续配置」入口
const uploadLimits = ref({})
const uploadOrigin = ref(null)
const cameFromConfigure = ref(false)
// gameId 仅用于展示预期目录名，提交仍用原始目录名
const form = ref({ name: '', exe: '', exeAlt: '', width: 1280, height: 720, framerate: '30', gameLang: '', gameId: '' })
// 中文汉化补丁走 ANSI/CP936，需要 zh_CN.UTF-8；日文原版 / UTF-8 游戏不勾
const chinesePatch = computed({
  get: () => form.value.gameLang === 'zh_CN.UTF-8',
  set: (v) => { form.value.gameLang = v ? 'zh_CN.UTF-8' : '' },
})
const runId = ref('')
const gameId = ref('')
const run = ref({ lines: [], steps: [], state: 'RUNNING' })
const busy = ref(false)

const STEP_IDS = ['validate', 'allocate', 'register', 'wineboot', 'fonts']
const STEP_TITLES = {
  validate: t('校验参数与目录'),
  allocate: t('分配显示号 / 端口 / 槽位'),
  register: t('写入 game.env 并入库'),
  wineboot: t('初始化 Wine 前缀'),
  fonts: t('安装中文字体'),
}
const stepsView = computed(() => STEP_IDS.map((id) => stepOf(run.value.steps, id)))
const phase = computed(() => runPhase(run.value.state))
const submitReady = computed(() => canSubmit(form.value) && !!selected.value)

let abort = null

async function loadCandidates() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.importCandidates()
    candidates.value = res.candidates || []
    warnings.value = res.warnings || []
    supported.value = res.supported !== false
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function pick(c, displayName) {
  selected.value = c
  cameFromConfigure.value = false
  form.value = {
    name: displayName || c.id,
    exe: c.exes?.[0]?.path || '',
    exeAlt: '',
    width: 1280,
    height: 720,
    framerate: '30',
    gameLang: '',
    gameId: c.gameId,
  }
  step.value = 'configure'
  loadSlots()
}

/** 上传完成：后端已把目录整理到 games/<id>/，直接进入确认配置 */
function onUploadReady(payload) {
  uploadOrigin.value = { uploadId: payload.uploadId, name: payload.name || payload.candidate?.gameId }
  pick(payload.candidate, payload.name)
}

/** 「上一步」回到来源页：上传完成的目录不删除，面板里保留「继续配置」 */
function backToSource() {
  cameFromConfigure.value = true
  step.value = 'source'
}

async function loadLimits() {
  try {
    uploadLimits.value = await api.uploadLimits()
  } catch {
    uploadLimits.value = {}
  }
}

async function loadSlots() {
  try {
    slots.value = await api.importSlots()
  } catch (e) {
    slots.value = null
    notify.error(t("无法分配槽位：{0}", e.message))
  }
}

async function submit() {
  if (!submitReady.value || busy.value) return
  busy.value = true
  try {
    const res = await api.importGame({
      id: selected.value.id,
      name: form.value.name,
      exe: form.value.exe,
      exeAlt: form.value.exeAlt || null,
      width: Number(form.value.width),
      height: Number(form.value.height),
      framerate: form.value.framerate,
      gameLang: form.value.gameLang || null,
    })
    runId.value = res.runId
    // 成功页链接必须用后端注册的 id（归一化后）；预览值只是兜底。
    gameId.value = res.gameId || selected.value.gameId || selected.value.id
    step.value = 'running'
    history.replaceState(null, '',
      `/games/import?run=${encodeURIComponent(res.runId)}&id=${encodeURIComponent(gameId.value)}`)
    await subscribe(res.runId)
  } catch (e) {
    notify.error(t("接入失败：{0}", e.message))
  } finally {
    busy.value = false
  }
}

async function subscribe(id) {
  abort?.abort()
  abort = new AbortController()
  try {
    await api.importRunEvents(id, getWriteToken(), (name, data) => {
      if (name === 'state' && data === 'done') {
        finish()
        return
      }
      run.value = mergeEvent(run.value, name, data)
    }, abort.signal)
  } catch (e) {
    if (e.name !== 'AbortError') notify.error(t("日志流中断：{0}", e.message))
  }
}

/** 页面刷新/断线后按 runId 恢复 */
async function resume(id) {
  try {
    const snap = await api.importRun(id)
    run.value = { lines: snap.lines || [], steps: snap.steps || [], state: snap.state }
    // 快照里的 gameId 才是后端实际注册的 id；URL 里的 id 可能是旧页面的原目录名
    if (snap.gameId) adoptGameId(snap.gameId)
    step.value = 'running'
    loadCandidates()          // 回滚/进入游戏时需要目录名，先把候选补齐
    if (['SUCCESS', 'FAILED', 'CANCELLED'].includes(snap.state)) return
    await subscribe(id)
  } catch (e) {
    notify.error(t("恢复接入任务失败：{0}", e.message))
  }
}

function adoptGameId(id) {
  if (!id || id === gameId.value) return
  gameId.value = id
  history.replaceState(null, '',
    `/games/import?run=${encodeURIComponent(runId.value)}&id=${encodeURIComponent(id)}`)
}

async function finish() {
  try {
    const snap = await api.importRun(runId.value)
    run.value = { lines: snap.lines || [], steps: snap.steps || [], state: snap.state }
    if (snap.gameId) adoptGameId(snap.gameId)
  } catch {
    // 快照失败时保留流里的状态
  }
}

async function retry() {
  try {
    const res = await api.importRetry(runId.value)
    runId.value = res.runId
    run.value = { lines: [], steps: [], state: 'RUNNING' }
    history.replaceState(null, '',
      `/games/import?run=${encodeURIComponent(res.runId)}&id=${encodeURIComponent(gameId.value)}`)
    await subscribe(res.runId)
  } catch (e) {
    notify.error(t("重试失败：{0}", e.message))
  }
}

async function rollback() {
  try {
    await api.importRollback(runId.value)
    notify.success(t('已回滚注册，游戏文件与 wineprefix 保留'))
    step.value = 'source'
    run.value = { lines: [], steps: [], state: 'RUNNING' }
    runId.value = ''
    gameId.value = ''
    uploadOrigin.value = null
    cameFromConfigure.value = false
    history.replaceState(null, '', '/games/import')
    await loadCandidates()
  } catch (e) {
    notify.error(t("回滚失败：{0}", e.message))
  }
}

async function cancel() {
  try {
    await api.importCancel(runId.value)
    await finish()
  } catch (e) {
    notify.error(t("取消失败：{0}", e.message))
  }
}

onMounted(() => {
  const q = new URLSearchParams(location.search)
  const id = q.get('run')
  if (id) {
    runId.value = id
    gameId.value = q.get('id') || ''
    resume(id)
  } else {
    loadCandidates()
    loadLimits()
  }
})

onBeforeUnmount(() => abort?.abort())
</script>

<template>
  <div class="shell import-page">
    <header class="topbar">
      <router-link to="/" class="ghost-btn">{{ t("← 游戏库") }}</router-link>
      <span class="title">{{ t("接入游戏") }}</span>
      <div class="spacer" />
      <router-link to="/games" class="ghost-btn">{{ t("全部游戏") }}</router-link>
    </header>

    <p v-if="!supported" class="import-note">
      {{ t("当前平台不支持游戏接入：只能在 Linux 服务器上使用。") }}</p>

    <!-- 第一步：上传或选择目录 -->
    <section v-if="step === 'source'" class="import-section">
      <h2>{{ t("1. 上传游戏或选择服务器目录") }}</h2>

      <GameUploadPanel
        v-if="supported"
        :limits="uploadLimits"
        :supported="supported"
        :auto-resume="!cameFromConfigure"
        @ready="onUploadReady"
      />

      <div class="import-divider"><span>{{ t("或选择服务器上已有目录") }}</span></div>

      <p class="import-note">
        {{ t("已经用 scp/sftp 传到服务器") }}<code>{{ t("~/galworld/games/&lt;目录名&gt;/") }}</code> {{ t("的游戏，点「重新扫描」后直接在下方选择。") }}</p>
      <div class="import-actions">
        <button class="btn" :disabled="loading" @click="loadCandidates">{{ t("重新扫描") }}</button>
      </div>
      <p v-if="error" class="import-error">{{ error }}</p>
      <p v-else-if="loading" class="import-note">{{ t("正在扫描…") }}</p>
      <p v-else-if="!candidates.length" class="import-note">{{ t("没有发现未注册的游戏目录。") }}</p>
      <div v-else class="import-candidates">
        <button
          v-for="c in candidates"
          :key="c.id"
          class="import-card"
          @click="pick(c)"
        >
          <span class="import-card-id">{{ c.id }}</span>
          <span class="import-card-meta">{{ t("{0} · {1} 个 exe", formatBytes(c.sizeBytes), c.exes.length) }}</span>
          <span v-if="c.gameId && c.gameId !== c.id" class="import-card-meta">
            {{ t("接入为 {0}", c.gameId) }}</span>
        </button>
      </div>
      <ul v-if="warnings.length" class="import-warnings">
        <li v-for="w in warnings" :key="w">{{ w }}</li>
      </ul>
    </section>

    <!-- 第二步：确认配置 -->
    <section v-else-if="step === 'configure'" class="import-section">
      <h2>{{ t("2. 确认游戏配置") }}</h2>
      <div class="import-form">
        <label>{{ t("显示名") }}<input v-model="form.name" maxlength="120" /></label>
        <label>
          {{ t("可执行文件") }}<select v-model="form.exe">
            <option v-for="e in selected?.exes || []" :key="e.path" :value="e.path">
              {{ e.path }} ({{ formatBytes(e.sizeBytes) }})
            </option>
          </select>
        </label>
        <label>
          {{ t("备用可执行文件（可选）") }}<select v-model="form.exeAlt">
            <option value="">{{ t("（无）") }}</option>
            <option
              v-for="e in altOptions(selected?.exes, form.exe)"
              :key="e.path"
              :value="e.path"
            >{{ e.path }}</option>
          </select>
        </label>
        <label>{{ t("宽度") }}<input v-model.number="form.width" type="number" min="640" max="3840" /></label>
        <label>{{ t("高度") }}<input v-model.number="form.height" type="number" min="480" max="2160" /></label>
        <label>{{ t("帧率") }}<input v-model="form.framerate" /></label>
        <label class="import-check">
          <input v-model="chinesePatch" type="checkbox" />
          {{ t("中文汉化补丁（文本为 GBK/CP936 时勾选）") }}</label>
        <div class="import-slots">
          {{ t("接入目录名：") }}<code>{{ selected?.gameId || selected?.id }}</code>
          <span v-if="selected?.gameId && selected.gameId !== selected.id">{{ t("（原目录 {0} 将被重命名）", selected.id) }}</span>
        </div>
        <div class="import-slots">
          {{ t("自动分配：") }}<span v-if="slots">display :{{ slots.displayNum }} · port {{ slots.port }} · slot {{ slots.loopbackSlot }}</span>
          <span v-else>{{ t("无法分配") }}</span>
        </div>
      </div>
      <p v-if="!selected?.exes?.length" class="import-error">
        {{ t("该目录没有找到 exe：请确认解压是否完整。") }}</p>
      <div class="import-actions">
        <button class="btn" @click="backToSource">{{ t("上一步") }}</button>
        <button class="btn primary sm" :disabled="!submitReady || busy" @click="submit">{{ t("开始接入") }}</button>
      </div>
    </section>

    <!-- 第三步：执行 -->
    <section v-else class="import-section">
      <h2>{{ t("3. 执行接入") }}</h2>
      <ol class="import-steps">
        <li v-for="s in stepsView" :key="s.id" :class="'state-' + s.state.toLowerCase()">
          {{ STEP_TITLES[s.id] || s.title }} — {{ s.state }}
        </li>
      </ol>
      <pre class="import-log">{{ run.lines.join('\n') }}</pre>

      <div v-if="phase === 'done'" class="import-actions">
        <button class="btn primary sm" @click="router.push(playLink(gameId, 'import'))">{{ t("进入游戏") }}</button>
        <button class="btn" @click="router.push(`/game/${gameId}`)">{{ t("查看详情") }}</button>
      </div>
      <div v-else-if="phase === 'failed'" class="import-actions">
        <button class="btn primary sm" @click="retry">{{ t("重试") }}</button>
        <button class="btn" @click="rollback">{{ t("回滚注册") }}</button>
      </div>
      <div v-else class="import-actions">
        <button class="btn" @click="cancel">{{ t("取消") }}</button>
      </div>
    </section>
  </div>
</template>
