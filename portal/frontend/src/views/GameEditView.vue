<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
/**
 * 游戏编辑页。
 *
 * 所有改动（文字字段、封面、图标、宣传 CG、排序）都先留在页面里，
 * 只有点「保存」才写服务器。这样新增/删除媒体不会触发整页重载，
 * 滚动位置也不会被弹回顶部；点「取消」则全部丢弃。
 *
 * 允许改：名称、分辨率、简介、公司、标签、封面（多图）、图标、宣传 CG（多份）、轮换规则。
 * 删除游戏：移除注册信息与媒体，保留游戏本体目录，之后可重新注册。
 */
import { t } from '../i18n.js'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { confirmAction, notify } from '../feedback'
import { companyChip } from '../company-chip'
import { sortByMaintenanceOrder } from '../org-select'
import { recommendedCanvas } from '../canvas-hint'
import { companyEnabled, localizationEnabled } from '../feature-flags'
import HoverPreview from '../components/HoverPreview.vue'
import MediaLightbox from '../components/MediaLightbox.vue'
import MediaSection from '../components/MediaSection.vue'
import OrgMultiSelect from '../components/OrgMultiSelect.vue'

const props = defineProps({ id: { type: String, required: true } })
const router = useRouter()

const data = ref(null)
const loading = ref(true)
const saving = ref(false)
const deleting = ref(false)
const error = ref('')

const form = ref({
  name: '', width: 1280, height: 720, description: '',
  tags: [], companies: [], localizations: [],
})
// 启动程序：exeInfo.exes 是目录内候选（相对路径），exeCurrent/exeAlt 也存相对路径
const exeInfo = ref({ dir: '', exes: [], current: '', alt: '' })
const exeCurrent = ref('')
const exeAlt = ref('')
// 游戏运行时的主窗口矩形；用来解释「画布」与「实际窗口」的区别
const winRect = ref(null)
const allTags = ref([])
const allCompanies = ref([])
const allLocalizations = ref([])
// 本地暂存：persisted 项来自服务器，pending 项只存在于浏览器
const coverList = ref([])
const cgList = ref([])
const iconItem = ref(null)
const iconRemoved = ref(false)
const removedCoverIds = ref([])
const removedCgIds = ref([])
let tempSeq = 0

// 轮换规则
const globalSettings = ref({
  coverMode: 'seq', coverInterval: 6, cgMode: 'autoNext', cgOrder: 'seq',
})
const globalRotationEnabled = ref(true)
const limits = ref({ coverMaxCount: 8, cgMaxCount: 5, imageMaxMb: 8, videoMaxMb: 120 })
const followGlobal = ref(true)
const coverRotMode = ref('seq')
const coverRotValue = ref(6)
const coverRotUnit = ref(1)
const cgRotMode = ref('autoNext')
const cgRotOrder = ref('seq')

const uploadPct = ref(null)
const uploadLabel = ref('')

// 批量上传：队列忙闲状态（忙时禁止保存、离开需确认）；上传器句柄归 MediaSection 内部管理
const uploadBusy = ref({ cover: false, cg: false })

// 预览
const lightboxVisible = ref(false)
const lightboxKind = ref('cover')
const lightboxIndex = ref(0)

const resolutions = [
  { w: 1280, h: 720, label: '1280×720' },
  { w: 1920, h: 1080, label: '1920×1080' },
  { w: 1024, h: 768, label: '1024×768' },
]

const IMAGE_RE = /\.(png|jpe?g|webp|gif)$/i
const VIDEO_RE = /\.(mp4|webm)$/i
const MB = 1024 * 1024

/**
 * 游戏运行时只给两个选项：当前画布、按实际窗口算出的推荐画布。
 * 没运行时不允许选比当前更大的画布——画布放大不会放大游戏，只会加黑边。
 */
const availableResolutions = computed(() => {
  const cur = { w: form.value.width, h: form.value.height }
  const current = { w: cur.w, h: cur.h, label: t("当前画布 {0}×{1}", cur.w, cur.h) }
  const list = [current]
  if (winRect.value) {
    const rec = recommendedCanvas(winRect.value.width, winRect.value.height)
    if (rec && (rec.width !== cur.w || rec.height !== cur.h)) {
      list.push({
        w: rec.width,
        h: rec.height,
        label: t("推荐 {0}×{1}（匹配实际窗口）", rec.width, rec.height),
      })
    }
    return list
  }
  // 未运行时也保留当前值，避免校准后的非预设尺寸让下拉框空白
  for (const r of resolutions) {
    if (r.w === cur.w && r.h === cur.h) continue
    if (r.w <= cur.w && r.h <= cur.h
        && Math.abs(cur.w / cur.h - r.w / r.h) < 0.02) {
      list.push(r)
    }
  }
  return list
})

/** 待保存的改动数量：上传即时生效，这里只统计删除与图标暂存 */
const stagedCount = computed(() => {
  const removed = removedCoverIds.value.length + removedCgIds.value.length
  return removed + (iconItem.value ? 1 : 0) + (iconRemoved.value ? 1 : 0)
})

const hasPersistedIcon = computed(() => !!data.value?.iconPath && !iconRemoved.value)

/** 刚上传、排序尚未提交的数量（服务器路径面板里给个提示） */
const coverFreshCount = computed(() => coverList.value.filter((x) => x.fresh).length)
const cgFreshCount = computed(() => cgList.value.filter((x) => x.fresh).length)

/** 灯箱条目：只放可预览的媒体，按列表顺序 */
function visibleList(kind) {
  return (kind === 'cover' ? coverList.value : cgList.value)
}

const lightboxItems = computed(() => {
  if (lightboxKind.value === 'icon') {
    const item = iconItem.value
    if (item?.url) return [{ key: item.key, url: item.url, video: false, filename: item.filename }]
    if (data.value?.iconPath) {
      return [{ key: 'icon', url: data.value.iconPath, video: false, filename: 'icon' }]
    }
    return []
  }
  return visibleList(lightboxKind.value).map((m) => ({
    key: m.key,
    url: m.url,
    video: isVideoItem(m),
    filename: m.filename,
  }))
})

/** 悬停小窗用的条目（灯箱与预览共用） */
function hoverItemOf(m) {
  return { url: m.url, video: isVideoItem(m), filename: m.filename }
}

const iconHoverItem = computed(() => {
  if (iconItem.value?.url) {
    return { url: iconItem.value.url, video: false, filename: iconItem.value.filename }
  }
  if (data.value?.iconPath && !iconRemoved.value) {
    return { url: data.value.iconPath, video: false, filename: 'icon' }
  }
  return null
})

function openLightbox(kind, index) {
  lightboxKind.value = kind
  lightboxIndex.value = Math.max(0, index)
  lightboxVisible.value = true
}

function openIconLightbox() {
  if (!lightboxItems.value.length && !iconHoverItem.value) return
  lightboxKind.value = 'icon'
  lightboxIndex.value = 0
  lightboxVisible.value = true
}

function intOr(v, fallback) {
  const n = parseInt(v, 10)
  return Number.isFinite(n) && n > 0 ? n : fallback
}

async function loadExes() {
  try {
    exeInfo.value = await api.gameExes(props.id)
    const dir = exeInfo.value.dir || ''
    const toRel = (abs) => {
      if (!abs) return ''
      if (dir && abs.startsWith(dir + '/')) return abs.slice(dir.length + 1)
      const hit = (exeInfo.value.exes || []).find((e) => abs.endsWith('/' + e.path))
      return hit ? hit.path : ''
    }
    exeCurrent.value = toRel(exeInfo.value.current)
    exeAlt.value = toRel(exeInfo.value.alt)
  } catch {
    // 目录不可读时留空，页面其余部分照常
  }
}

/** 游戏在跑时读主窗口尺寸；没跑就是 null，只显示通用说明 */
async function loadWindow() {
  winRect.value = await api.gameWindow(props.id)
}

/** 交换主用 / 备用；仍需点保存才落盘 */
function swapExe() {
  if (!exeAlt.value) return
  const cur = exeCurrent.value
  exeCurrent.value = exeAlt.value
  exeAlt.value = cur
}

async function load() {
  loading.value = true
  error.value = ''
  loadExes()
  loadWindow()
  try {
    const [d, t, c, g, s] = await Promise.all([
      api.editView(props.id), api.listTags(), api.listCompanies(),
      api.listLocalizationGroups(), api.settings(),
    ])
    allTags.value = t
    allCompanies.value = c
    allLocalizations.value = g
    globalSettings.value = {
      coverMode: s.coverRotationMode || s.rotationMode || 'seq',
      coverInterval: intOr(s.coverRotationInterval || s.rotationInterval, 6),
      cgMode: s.cgRotationMode || 'autoNext',
      cgOrder: s.cgRotationOrder || 'seq',
    }
    globalRotationEnabled.value = s.rotationEnabled !== 'false'
    limits.value = {
      coverMaxCount: intOr(s.coverMaxCount, 8),
      cgMaxCount: intOr(s.cgMaxCount, 5),
      imageMaxMb: intOr(s.imageMaxMb, 8),
      videoMaxMb: intOr(s.videoMaxMb, 120),
    }
    applyEdit(d)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

/** 把 /edit 返回的完整视图写进表单；不进入整页 loading 分支 */
function applyEdit(d) {
  releasePendingUrls()
  data.value = d
  form.value = {
    name: d.name,
    width: d.width,
    height: d.height,
    description: d.description,
    tags: [...(d.tags || [])],
    companies: (d.companies || []).map(companyChip),
    localizations: (d.localizations || []).map(companyChip),
  }
  coverList.value = (d.covers || []).map((m) => ({
    key: 'c' + m.id,
    id: m.id,
    filename: m.filename,
    url: m.path,
    persisted: true,
  }))
  cgList.value = (d.cgs || []).map((m) => ({
    key: 'g' + m.id,
    id: m.id,
    filename: m.filename,
    url: m.path,
    persisted: true,
  }))
  iconItem.value = null
  iconRemoved.value = false
  removedCoverIds.value = []
  removedCgIds.value = []
  // 单游戏是否已定制：新字段优先，旧字段兼容
  followGlobal.value = !(d.coverRotationMode || d.coverRotationInterval
    || d.cgRotationMode || d.cgRotationOrder || d.cgRotationInterval
    || d.rotationMode || d.rotationInterval)
  coverRotMode.value = d.coverRotationMode || d.rotationMode || globalSettings.value.coverMode
  const coverSec = d.coverRotationInterval || d.rotationInterval || globalSettings.value.coverInterval
  coverRotUnit.value = coverSec >= 3600 && coverSec % 3600 === 0 ? 3600
    : coverSec >= 60 && coverSec % 60 === 0 ? 60 : 1
  coverRotValue.value = coverSec / coverRotUnit.value

  const cgRaw = d.cgRotationMode || d.rotationMode || ''
  cgRotMode.value = ['autoNext', 'repeat', 'stop'].includes(cgRaw) ? cgRaw
    : cgRaw === 'random' || cgRaw === 'seq' || cgRaw === 'videoEnd' ? 'autoNext'
    : globalSettings.value.cgMode
  cgRotOrder.value = d.cgRotationOrder
    || (cgRaw === 'random' ? 'random' : cgRaw === 'seq' || cgRaw === 'videoEnd' ? 'seq' : '')
    || globalSettings.value.cgOrder
}

function releasePendingUrls() {
  for (const it of [...coverList.value, ...cgList.value]) {
    if (!it.persisted && it.url) URL.revokeObjectURL(it.url)
  }
  if (iconItem.value?.url) URL.revokeObjectURL(iconItem.value.url)
}

function flashProgress(label, pct) {
  uploadLabel.value = label
  uploadPct.value = pct
}

async function save() {  if (saving.value) return
  if (uploadBusy.value.cover || uploadBusy.value.cg) {
    notify.warning(t('上传仍在进行，完成后再保存更稳妥'))
    return
  }
  saving.value = true
  uploadPct.value = null
  try {
    const patch = {
      name: form.value.name,
      width: form.value.width,
      height: form.value.height,
      description: form.value.description,
      tags: form.value.tags,
      // 维度停用时不下发，避免隐藏字段把已有关联清空
      ...(companyEnabled.value ? { companies: form.value.companies } : {}),
      ...(localizationEnabled.value ? { localizations: form.value.localizations } : {}),
      // 空字符串表示「清掉备用」；未选到候选时保持不动，避免把 EXE 写坏
      exe: exeCurrent.value || null,
      exeAlt: exeAlt.value || '',
    }
    // 封面 / CG 轮换规则分开保存
    if (followGlobal.value) {
      patch.followGlobal = true
      patch.coverRotationMode = null
      patch.coverRotationInterval = null
      patch.cgRotationMode = null
      patch.cgRotationOrder = null
      patch.cgRotationInterval = null
    } else {
      patch.followGlobal = false
      patch.coverRotationMode = coverRotMode.value
      patch.coverRotationInterval = Math.round(coverRotValue.value * coverRotUnit.value)
      patch.cgRotationMode = cgRotMode.value
      patch.cgRotationOrder = cgRotOrder.value
    }
    const r = await api.updateGame(props.id, patch)

    await saveIcon()
    await saveMediaList('cover', coverList, removedCoverIds)
    await saveMediaList('cg', cgList, removedCgIds)

    uploadPct.value = null
    uploadLabel.value = ''
    notify.success(r?.restartRequired
      ? t('已保存。启动程序/分辨率改动需要重启会话才生效')
      : t('已保存'))
    // 保存成功回到游戏详情页，由详情页重新拉取最新数据
    router.push(`/game/${encodeURIComponent(props.id)}`)
  } catch (e) {
    // 失败时保留页面里的暂存状态，方便用户改完重试；
    // 已成功的步骤会即时从暂存里摘掉
    uploadPct.value = null
    uploadLabel.value = ''
    notify.error(t("保存失败：{0}", e.message))
  } finally {
    saving.value = false
  }
}

async function saveIcon() {
  if (iconItem.value?.file) {
    flashProgress(t('上传图标'), 0)
    await api.uploadIconFile(props.id, iconItem.value.file, (p) => { uploadPct.value = p })
    URL.revokeObjectURL(iconItem.value.url)
    iconItem.value = null
    iconRemoved.value = false
  } else if (iconRemoved.value) {
    await api.deleteIcon(props.id)
    iconRemoved.value = false
  }
}

/**
 * 提交封面 / CG 的删除与排序。
 *
 * 上传已由 MediaUploader 即时完成，这里只剩两件事：
 *  1. 删除本次标为待删除的已保存项
 *  2. 按界面顺序提交 reorder
 */
async function saveMediaList(kind, listRef, removedIdsRef) {
  // 先删已存在的项：否则「删 1 个再传 1 个」会先撞到数量上限
  for (const id of [...removedIdsRef.value]) {
    await api.deleteMedia(props.id, id)
    removedIdsRef.value = removedIdsRef.value.filter((x) => x !== id)
  }

  const ids = listRef.value.map((x) => x.id).filter((x) => x != null)
  if (ids.length) {
    await api.reorderMedia(props.id, kind, ids)
  }
  listRef.value.forEach((x) => { x.fresh = false })
}

/** 上传成功：追加为已保存项（排序在保存时统一提交） */
function onUploaded(kind, media) {
  const listRef = kind === 'cover' ? coverList : cgList
  if (listRef.value.some((x) => x.id === media.id)) return
  listRef.value.push({
    key: 'up-' + media.id,
    id: media.id,
    filename: media.filename,
    url: media.path,
    persisted: true,
    fresh: true,
  })
}

/** 取消上传后的对账：以服务端列表为准，补齐漏在界面外的项 */
function onReconcile(kind, list) {
  const listRef = kind === 'cover' ? coverList : cgList
  const known = new Set(listRef.value.map((x) => x.id))
  for (const m of list) {
    if (known.has(m.id)) continue
    listRef.value.push({
      key: 'rc-' + m.id,
      id: m.id,
      filename: m.filename,
      url: m.path,
      persisted: true,
      fresh: true,
    })
  }
}

async function pickIcon(ev) {
  const file = ev.target.files?.[0]
  ev.target.value = ''
  if (!file) return
  if (!IMAGE_RE.test(file.name) || file.size > limits.value.imageMaxMb * MB) {
    notify.warning(t("图标只支持 png/jpg/webp/gif，且不超过 {0} MB", limits.value.imageMaxMb))
    return
  }
  if (iconItem.value?.url) URL.revokeObjectURL(iconItem.value.url)
  iconItem.value = {
    key: 'new-icon-' + (++tempSeq),
    filename: file.name,
    file,
    url: URL.createObjectURL(file),
  }
  // 替换图标覆盖旧文件，不需要再走删除接口
  iconRemoved.value = false
}

/** 已保存的内容才确认；本次刚加、还没保存的直接删掉 */
async function removeMedia(kind, index) {
  const listRef = kind === 'cover' ? coverList : cgList
  const item = listRef.value[index]
  if (!item) return
  if (item.persisted) {
    const ok = await confirmAction(
      t("删除已保存的{0}「{1}」？保存后生效。", kind === 'cover' ? t('封面') : t('宣传 CG'), item.filename),
      { title: t('删除媒体'), confirmText: t('删除') },
    )
    if (!ok) return
    if (kind === 'cover') removedCoverIds.value.push(item.id)
    else removedCgIds.value.push(item.id)
  } else {
    URL.revokeObjectURL(item.url)
  }
  listRef.value.splice(index, 1)
}

async function removeIcon() {
  if (iconItem.value) {
    URL.revokeObjectURL(iconItem.value.url)
    iconItem.value = null
    return
  }
  if (!hasPersistedIcon.value) return
  const ok = await confirmAction(t('删除已保存的图标？保存后生效。'), {
    title: t('删除图标'), confirmText: t('删除'),
  })
  if (!ok) return
  iconRemoved.value = true
}

function isVideoItem(item) {
  return item.file ? item.file.type.startsWith('video/') || VIDEO_RE.test(item.filename)
    : VIDEO_RE.test(item.filename)
}

/** 拖动排序只改本地顺序，保存时统一提交；MediaSection 只报 from/to */
function reorderMedia(kind, { from, to }) {
  const listRef = kind === 'cover' ? coverList : cgList
  if (from < 0 || to < 0 || from >= listRef.value.length || from === to) return
  const [item] = listRef.value.splice(from, 1)
  listRef.value.splice(to, 0, item)
}

/** 标签表单只存名称；选择器用对象模型，别名来自标签字典 */
const tagModel = computed({
  get: () => form.value.tags.map((n) => {
    const hit = allTags.value.find((x) => x.name === n)
    return { id: hit?.id ?? null, name: n, alias: hit?.alias || '', website: '' }
  }),
  set: (v) => { form.value.tags = (v || []).map((o) => o.name) },
})

/** 单游戏拖动排序：立即落库；新建项尚未有 id 时跳过，保存后由后端补 */
async function persistOrder(kind, list) {
  const ids = (list || []).map((x) => x.id).filter((id) => id != null)
  if (!ids.length) return
  try {
    await api.reorderGameRelations(props.id, kind, ids)
    notify.success(t('排序已保存'))
  } catch (e) {
    notify.error(t("保存排序失败：{0}", e.message))
  }
}

function onCompanyReorder(list) {
  form.value.companies = list
  persistOrder('company', list)
}

function onLocalizationReorder(list) {
  form.value.localizations = list
  persistOrder('localization', list)
}

function onTagReorder(list) {
  tagModel.value = list
  persistOrder('tag', list)
}

/**
 * 清掉单游戏排序，回到维护页的全局顺序。
 *
 * 只重排当前本地选择，不再整表重载——未保存的名称、简介、媒体与新增项都要保留。
 * 服务端已存在的关联顺序由接口清空；本地列表按维护页顺序对齐即可。
 */
async function resetOrder(kind) {
  try {
    await api.resetGameRelations(props.id, kind)
    if (kind === 'company') {
      form.value.companies = sortByMaintenanceOrder(form.value.companies, allCompanies.value)
    } else if (kind === 'localization') {
      form.value.localizations = sortByMaintenanceOrder(
        form.value.localizations, allLocalizations.value)
    } else if (kind === 'tag') {
      tagModel.value = sortByMaintenanceOrder(tagModel.value, allTags.value)
    }
    notify.success(t('已按维护顺序重置'))
  } catch (e) {
    notify.error(t("重置排序失败：{0}", e.message))
  }
}

/** 编辑页新增公司：只填名称与别名，其余留空；返回新条目供下拉自动选中 */
async function createCompany({ name, alias }) {
  try {
    const c = await api.createCompany(name, alias, '', '')
    allCompanies.value = [...allCompanies.value, c]
    if (!form.value.companies.some((x) => x.id === c.id)) {
      form.value.companies.push(companyChip(c))
    }
    return c
  } catch (e) {
    notify.error(t("新增公司失败：{0}", e.message))
    return null
  }
}

/** 编辑页新增汉化组：只填名称与别名；返回新条目供下拉自动选中 */
async function createLocalization({ name, alias }) {
  try {
    const g = await api.createLocalizationGroup(name, alias, '', '')
    allLocalizations.value = [...allLocalizations.value, g]
    if (!form.value.localizations.some((x) => x.id === g.id)) {
      form.value.localizations.push(companyChip(g))
    }
    return g
  } catch (e) {
    notify.error(t("新增汉化组失败：{0}", e.message))
    return null
  }
}

/** 编辑页新增标签：名称 + 可选别名；返回新条目供下拉自动选中 */
async function createTag({ name, alias }) {
  try {
    const created = await api.createTag(name, alias)
    if (!allTags.value.some((x) => x.id === created.id)) {
      allTags.value = [...allTags.value, created]
    }
    if (!form.value.tags.includes(created.name)) form.value.tags.push(created.name)
    return created
  } catch (e) {
    notify.error(t("新增标签失败：{0}", e.message))
    return null
  }
}

function modeText(mode) {
  if (mode === 'random') return t('随机')
  if (mode === 'autoNext') return t('播完下一条')
  if (mode === 'repeat') return t('重复当前')
  if (mode === 'stop') return t('播完停')
  return t('顺序')
}

function onResChange(ev) {
  const [w, h] = ev.target.value.split('x').map(Number)
  form.value.width = w
  form.value.height = h
}

async function deleteGame() {
  const name = form.value.name || props.id
  // 删除前查一次会话状态：运行中的游戏要先确认「关闭并删除」，不能沿用普通提示
  let running = false
  try {
    const st = await api.session(props.id)
    running = st?.state === 'RUNNING'
  } catch {
    // 状态查不到时按未运行处理，交互不被阻塞
  }
  const message = running
    ? t("「{0}」正在运行。\n\n继续将先停止并关闭游戏会话，再从游戏库移除其注册信息、封面/图标/宣传 CG 与游玩记录。\n\n游戏本体目录会保留，之后仍可重新注册。此操作不可撤销。", name)
    : t("将从游戏库移除「{0}」的注册信息、封面/图标/宣传 CG 与游玩记录。\n\n游戏本体目录会保留，之后仍可重新注册。此操作不可撤销。", name)
  const ok = await confirmAction(message, running
    ? { title: t('游戏运行中'), confirmText: t('关闭并删除') }
    : { title: t('删除游戏'), confirmText: t('删除游戏') })
  if (!ok) return
  deleting.value = true
  try {
    await api.deleteGame(props.id)
    notify.success(running ? t('游戏已关闭并删除') : t('游戏已删除'))
    router.replace('/')
  } catch (e) {
    notify.error(t("删除失败：{0}", e.message))
  } finally {
    deleting.value = false
  }
}

/** 上传队列仍在跑时离开页面要确认（上传即时落库，中途离开不算回滚） */
function onBeforeUnload(e) {
  if (!uploadBusy.value.cover && !uploadBusy.value.cg) return
  e.preventDefault()
  e.returnValue = ''
}

onMounted(() => {
  load()
  window.addEventListener('beforeunload', onBeforeUnload)
})
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
  releasePendingUrls()
})
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <router-link :to="`/game/${id}`" class="ghost-btn">{{ t("← 返回详情") }}</router-link>
      <span v-if="stagedCount" class="muted-sm">{{ t("{0} 项改动待保存", stagedCount) }}</span>
      <div class="spacer" />
      <button class="btn primary sm" :disabled="saving || deleting" @click="save">{{ saving ? t('保存中…') : t('保存') }}</button>
    </header>

    <div v-if="loading" class="loading">{{ t("加载中…") }}</div>
    <div v-else-if="error" class="empty">{{ error }}</div>

    <div v-else class="edit-grid">
      <div>
        <div class="field">
          <label>{{ t("游戏名称") }}</label>
          <input v-model="form.name" class="input" maxlength="200">
        </div>

        <div class="field">
          <label class="res-label">
            <span>
              {{ t("分辨率") }}<span class="info-tip" :title="t('当前游戏不支持的比例自动隐藏处理')">!</span>
            </span>
            <span class="res-note">
              {{ t("修改分辨率后需要重启会话才生效。游戏正在运行时会提供「立即重启」按钮。") }}</span>
          </label>
          <div class="res-row">
            <select class="input" :value="`${form.width}x${form.height}`" @change="onResChange">
              <option v-for="r in availableResolutions" :key="r.label" :value="`${r.w}x${r.h}`">
                {{ r.label }}
              </option>
            </select>
          </div>
          <p v-if="winRect" class="muted-sm" style="margin-top:6px">
            {{ t("实测游戏窗口 {0}×{1}。 画布比窗口大只会多出黑边，甚至让画面显得更小；选「推荐」可让窗口占比最大。 保存后需要重启会话生效，游玩页右上角「校准分辨率」可直接完成测量与重启。", winRect.width, winRect.height) }}</p>
          <p v-else class="muted-sm" style="margin-top:6px">
            {{ t("游戏未运行，无法测量实际窗口。启动后可在游玩页右上角点「校准分辨率」， 或在全局设置选择自动校准。") }}</p>
        </div>

        <div class="field">
          <label class="res-label">
            <span>{{ t("启动程序") }}</span>
            <span class="res-note">
              {{ t("当前生效的是「主用」；「备用」用于保存另一个版本（如中文启动器）。 改动需重启会话才生效，点上方「保存」才落盘。") }}</span>
          </label>
          <label class="muted-sm">{{ t("主用（当前生效）") }}</label>
          <select v-model="exeCurrent" class="input">
            <option v-for="e in exeInfo.exes" :key="e.path" :value="e.path">{{ e.path }}</option>
          </select>
          <label class="muted-sm" style="margin-top:10px">{{ t("备用（可留空）") }}</label>
          <select v-model="exeAlt" class="input">
            <option value="">{{ t("（无）") }}</option>
            <option
              v-for="e in exeInfo.exes.filter((x) => x.path !== exeCurrent)"
              :key="e.path"
              :value="e.path"
            >{{ e.path }}</option>
          </select>
          <button class="btn sm" type="button" style="margin-top:10px" @click="swapExe">
            {{ t("交换主用 / 备用") }}</button>
          <p v-if="!exeInfo.exes.length" class="muted-sm">{{ t("目录内没有找到 exe。") }}</p>
        </div>

        <div v-if="companyEnabled" class="field">
          <label>{{ t("公司（可多选，拖动已选项排序；维护在「公司维护」页）") }}</label>
          <OrgMultiSelect
            v-model="form.companies"
            :options="allCompanies"
            :creatable="true"
            :placeholder="t('搜索公司名称或别名')"
            :create-fn="createCompany"
            @reorder="onCompanyReorder"
          />
          <button class="link-btn" type="button" @click="resetOrder('company')">{{ t("重置为维护顺序") }}</button>
        </div>

        <div v-if="localizationEnabled" class="field">
          <label>{{ t("汉化组（可多选，拖动已选项排序；维护在「汉化组维护」页）") }}</label>
          <OrgMultiSelect
            v-model="form.localizations"
            :options="allLocalizations"
            :creatable="true"
            :placeholder="t('搜索汉化组名称或别名')"
            :create-fn="createLocalization"
            @reorder="onLocalizationReorder"
          />
          <button class="link-btn" type="button" @click="resetOrder('localization')">{{ t("重置为维护顺序") }}</button>
        </div>

        <div class="field">
          <label>{{ t("标签（可多选，拖动已选项排序；输入名称或别名搜索或新建）") }}</label>
          <OrgMultiSelect
            v-model="tagModel"
            :options="allTags"
            :creatable="true"
            :placeholder="t('搜索标签名称或别名')"
            :create-fn="createTag"
            @reorder="onTagReorder"
          />
          <button class="link-btn" type="button" @click="resetOrder('tag')">{{ t("重置为维护顺序") }}</button>
        </div>

        <div class="field">
          <label>{{ t("简介") }}</label>
          <textarea v-model="form.description" maxlength="2000"></textarea>
        </div>

        <MediaSection
          kind="cover"
          :title="t('封面（多图轮换）')"
          :game-id="id"
          :items="coverList"
          :max="limits.coverMaxCount"
          :image-max-mb="limits.imageMaxMb"
          :video-max-mb="limits.videoMaxMb"
          :unit="t('张')"
          index-mode="number"
          :upload-pct="uploadPct"
          :upload-label="uploadLabel"
          @remove="removeMedia('cover', $event)"
          @reorder="reorderMedia('cover', $event)"
          @uploaded="onUploaded('cover', $event)"
          @reconcile="onReconcile('cover', $event)"
          @busy="uploadBusy.cover = $event"
          @preview="openLightbox('cover', $event)"
        />

        <div class="section-title">{{ t("图标") }}</div>
        <div class="media-list">
          <div v-if="iconItem" class="media-item pending">
            <span class="media-thumb-btn" :title="t('点击预览')" @click="openIconLightbox">
              <img :src="iconItem.url" class="icon-preview" :alt="iconItem.filename">
            </span>
            <span class="name">{{ iconItem.filename }}</span>
            <span class="pending-badge">{{ t("未保存") }}</span>
            <HoverPreview :item="iconHoverItem">
              <button class="link-btn" type="button" @click="openIconLightbox">{{ t("预览") }}</button>
            </HoverPreview>
            <button class="link-btn danger" @click="removeIcon">{{ t("删除") }}</button>
          </div>
          <div v-else-if="hasPersistedIcon" class="media-item">
            <span class="media-thumb-btn" :title="t('点击预览')" @click="openIconLightbox">
              <img :src="data.iconPath" class="icon-preview" :alt="t('图标')">
            </span>
            <span class="name">{{ data.paths.icon.join(', ') || 'icon' }}</span>
            <HoverPreview :item="iconHoverItem">
              <button class="link-btn" type="button" @click="openIconLightbox">{{ t("预览") }}</button>
            </HoverPreview>
            <button class="link-btn danger" @click="removeIcon">{{ t("删除") }}</button>
          </div>
        </div>
        <div style="margin-top:10px">
          <label class="file-btn">{{ t("上传图标") }}<input type="file" accept="image/png,image/jpeg,image/webp,image/gif" hidden @change="pickIcon">
          </label>
        </div>

        <MediaSection
          kind="cg"
          :title="t('宣传 CG（多 CG 轮换）')"
          :game-id="id"
          :items="cgList"
          :max="limits.cgMaxCount"
          :image-max-mb="limits.imageMaxMb"
          :video-max-mb="limits.videoMaxMb"
          :unit="t('个')"
          index-mode="media"
          allow-video
          :upload-pct="uploadPct"
          :upload-label="uploadLabel"
          @remove="removeMedia('cg', $event)"
          @reorder="reorderMedia('cg', $event)"
          @uploaded="onUploaded('cg', $event)"
          @reconcile="onReconcile('cg', $event)"
          @busy="uploadBusy.cg = $event"
          @preview="openLightbox('cg', $event)"
        />

        <div class="section-title">{{ t("轮换规则") }}</div>
        <div v-if="!globalRotationEnabled" class="warn-box" style="margin-bottom:12px">
          {{ t("全局轮换功能已在「设置」页关闭，本页的规则暂不生效；开启后按此规则轮换。") }}</div>
        <div class="field">
          <label style="display:flex;align-items:center;gap:8px;cursor:pointer">
            <input type="checkbox" v-model="followGlobal">
            <span>
              {{ t("应用全局轮换规则（封面：{0} {1} 秒 · CG：{2}", modeText(globalSettings.coverMode), globalSettings.coverInterval, modeText(globalSettings.cgMode)) }}<template v-if="globalSettings.cgMode === 'autoNext'"> · {{ modeText(globalSettings.cgOrder) }}</template>）
            </span>
          </label>
          <span class="muted-sm">{{ t("取消勾选后可为此游戏分别定制封面与 CG 规则；全局默认在「设置」页配置。") }}</span>
        </div>

        <div v-if="!followGlobal" class="field">
          <label>{{ t("封面轮换") }}</label>
          <div class="res-row">
            <select v-model="coverRotMode" class="input" style="width:120px">
              <option value="seq">{{ t("顺序") }}</option>
              <option value="random">{{ t("随机") }}</option>
            </select>
            <input v-model.number="coverRotValue" class="input" type="number" min="1" max="999" style="width:90px">
            <select v-model.number="coverRotUnit" class="input" style="width:90px">
              <option :value="1">{{ t("秒") }}</option>
              <option :value="60">{{ t("分") }}</option>
              <option :value="3600">{{ t("时") }}</option>
            </select>
          </div>
          <span class="muted-sm">{{ t("范围 1 秒 – 24 小时") }}</span>
        </div>

        <div v-if="!followGlobal" class="field">
          <label>{{ t("宣传 CG 轮换") }}</label>
          <div class="res-row">
            <select v-model="cgRotMode" class="input" style="width:170px">
              <option value="autoNext">{{ t("播完自动下一条") }}</option>
              <option value="repeat">{{ t("重复播放当前 CG") }}</option>
              <option value="stop">{{ t("播放结束自动停") }}</option>
            </select>
            <select v-if="cgRotMode === 'autoNext'" v-model="cgRotOrder" class="input" style="width:110px">
              <option value="seq">{{ t("顺序") }}</option>
              <option value="random">{{ t("随机") }}</option>
            </select>
          </div>
          <span class="muted-sm">
            {{ t("播完自动下一条：视频按播放结束切下一项，图片 CG 使用固定展示时长； 重复播放当前 CG：视频循环；播放结束自动停：播完停在最后一帧。") }}</span>
        </div>

        <div class="actions" style="margin-top:26px;flex-direction:row">
          <button class="btn primary sm" :disabled="saving || deleting" @click="save">{{ t("保存") }}</button>
          <router-link :to="`/game/${id}`" class="btn">{{ t("取消") }}</router-link>
        </div>
      </div>

      <div>
        <div class="panel" style="margin-top:0">
          <div class="panel-head"><h2>{{ t("服务器路径") }}</h2></div>
          <div class="path-box">
            <b>{{ t("游戏实体") }}</b><br>{{ data.paths.exe }}<br><br>
            <b>{{ t("媒体目录") }}</b><br>{{ data.paths.mediaDir }}<br><br>
            <b>{{ t("封面") }}</b><br>
            <span v-for="f in data.paths.cover" :key="f">{{ data.paths.mediaDir }}/{{ f }}<br></span>
            <span v-if="!data.paths.cover.length">{{ t("（无）") }}<br></span>
            <span v-if="coverFreshCount" class="muted-sm">{{ t("另有 {0} 张刚上传，保存后在列表中", coverFreshCount) }}<br></span>
            <br>
            <b>{{ t("图标") }}</b><br>
            <span v-for="f in data.paths.icon" :key="f">{{ data.paths.mediaDir }}/{{ f }}<br></span>
            <span v-if="!data.paths.icon.length && !iconItem">{{ t("（无）") }}<br></span>
            <span v-if="iconItem" class="muted-sm">{{ t("新图标待保存") }}<br></span>
            <br>
            <b>{{ t("宣传 CG") }}</b><br>
            <span v-for="f in data.paths.cg" :key="f">{{ data.paths.mediaDir }}/{{ f }}<br></span>
            <span v-if="!data.paths.cg.length">{{ t("（无）") }}<br></span>
            <span v-if="cgFreshCount" class="muted-sm">{{ t("另有 {0} 个刚上传，保存后在列表中", cgFreshCount) }}<br></span>
          </div>
          <p class="note" style="margin-top:12px">{{ t("只读展示已保存的文件，便于在服务器上定位。") }}</p>
        </div>

        <div class="panel danger-zone" style="margin-top:18px">
          <div class="panel-head"><h2>{{ t("删除游戏") }}</h2></div>
          <p class="muted-sm" style="margin-bottom:14px">
            {{ t("移除注册信息、媒体与游玩记录，保留游戏本体目录。正在运行的会话会先被停止。") }}</p>
          <button class="btn danger-solid" :disabled="saving || deleting" @click="deleteGame">
            {{ deleting ? t('删除中…') : t('删除这个游戏') }}
          </button>
        </div>
      </div>
    </div>

    <MediaLightbox
      v-model:visible="lightboxVisible"
      v-model:index="lightboxIndex"
      :items="lightboxItems"
    />
  </div>
</template>
