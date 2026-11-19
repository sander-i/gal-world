<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
import { onLocaleChange, t } from '../i18n.js'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { prefetch } from '../prefetch'
import { takeSavedScroll } from '../route-scroll'
import { activateSkeleton } from '../skeleton-fx.js'
import { api } from '../api'
import { notify } from '../feedback'
import { gameLink, listQuery, readListQuery } from '../list-origin'
import { playLink } from '../play-origin'
import GameCard from '../components/GameCard.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import { useGameLaunch } from '../use-game-launch'
import { companyEnabled, localizationEnabled, loadSettings } from '../feature-flags'
import { rankFilterItems } from '../filter-chips'

const router = useRouter()
const route = useRoute()
const DENSITY_KEY = 'galworld.density'
const PAGE_SIZE = 40

const games = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const done = ref(false)
/**
 * 首屏骨架：只有「列表还空着」的第一次 reset 才为 true。
 *
 * 筛选变化也会 reset，但那时用户已经看到内容，再铺整块骨架只会闪一下，
 * 所以那种情况直接替换列表，不显示网格骨架。
 */
const firstLoading = ref(false)
/** 标签 / 公司 / 汉化组 / 设置四组元数据返回前，筛选栏显示骨架 chip */
const metaLoading = ref(true)

/** 首次进入时从 URL 还原筛选（从详情页返回走的就是这条路径） */
const initial = readListQuery(route)

const tags = ref([])
const companies = ref([])
const localizations = ref([])
const activeTag = ref(initial.tag)
const activeCompany = ref(initial.company)
const activeLocalization = ref(initial.localization)
const query = ref(initial.q)
/** 公司 / 汉化组行内是否展开全部项 */
const companiesOpen = ref(false)
const localizationsOpen = ref(false)
const density = ref(initial.density || localStorage.getItem(DENSITY_KEY) || 'cozy')
const rotationEnabled = ref(true)

/**
 * 启停操作与进度跟踪：与首页共用同一套逻辑。
 *
 * 关闭接口返回后游戏进程可能还要 10~15 秒才真正退出（KiriKiri 类会自己存盘），
 * 进度条靠 /session 轮询确认会话真正停止后才收尾。
 */
const { busyId, launches, toggle: launchToggle } = useGameLaunch({
  idOf: (g) => g.id ?? g.gameId,
  onError: (msg) => notify.error(msg),
  onStarted: (id) => setGameState(id, 'RUNNING'),
  onStopped: (id) => setGameState(id, 'STOPPED'),
})

function setGameState(id, state) {
  const i = games.value.findIndex((x) => x.id === id)
  if (i >= 0) games.value[i] = { ...games.value[i], state }
}

/** 密度选项：computed 而非常量，否则语言切换后不更新 */
const densities = computed(() => [
  { id: 'cozy', label: t('标准') },
  { id: 'compact', label: t('紧凑') },
  { id: 'list', label: t('列表') },
])

/** 超出这个数量就折叠成「前 N 个 + 更多」，避免筛选区被撑成多行 */
const CHIP_LIMIT = 8

/** 筛选栏只展示有关联游戏的项，并按游戏数倒序；已选中的项始终保留 */
const companyChips = computed(() =>
  rankFilterItems(companies.value, (c) => c.id === activeCompany.value))
const localizationChips = computed(() =>
  rankFilterItems(localizations.value, (g) => g.id === activeLocalization.value))
const tagChips = computed(() =>
  rankFilterItems(tags.value, (t) => t.name === activeTag.value))

const visibleCompanies = computed(() =>
  companiesOpen.value ? companyChips.value : companyChips.value.slice(0, CHIP_LIMIT))
const hasMoreCompanies = computed(() => companyChips.value.length > CHIP_LIMIT)
const visibleLocalizations = computed(() =>
  localizationsOpen.value ? localizationChips.value : localizationChips.value.slice(0, CHIP_LIMIT))
const hasMoreLocalizations = computed(() => localizationChips.value.length > CHIP_LIMIT)

/** 折叠时，选中的项必须在可见范围内，否则用户看不到当前筛选状态 */
watch(activeCompany, (id) => {
  if (id == null || companiesOpen.value) return
  const i = companyChips.value.findIndex((c) => c.id === id)
  if (i >= CHIP_LIMIT) companiesOpen.value = true
})

watch(activeLocalization, (id) => {
  if (id == null || localizationsOpen.value) return
  const i = localizationChips.value.findIndex((g) => g.id === id)
  if (i >= CHIP_LIMIT) localizationsOpen.value = true
})

const hasFilter = computed(() =>
  !!activeTag.value || activeCompany.value != null || activeLocalization.value != null
  || !!query.value.trim())

/** 筛选条件区是否收起：收起后把纵向空间让给游戏网格 */
const filtersCollapsed = ref(false)

/** 收起状态下展示当前生效的条件，避免用户忘记筛选还开着 */
const activeFilterSummary = computed(() => {
  const parts = []
  if (activeCompany.value != null) {
    parts.push(companies.value.find((c) => c.id === activeCompany.value)?.name || t('公司'))
  }
  if (activeLocalization.value != null) {
    parts.push(localizations.value.find((g) => g.id === activeLocalization.value)?.name || t('汉化组'))
  }
  if (activeTag.value) parts.push(activeTag.value)
  if (query.value.trim()) parts.push(`“${query.value.trim()}”`)
  return parts.join(' · ')
})
const activeFilterCount = computed(() => {
  let n = 0
  if (activeCompany.value != null) n += 1
  if (activeLocalization.value != null) n += 1
  if (activeTag.value) n += 1
  if (query.value.trim()) n += 1
  return n
})

function setDensity(id) {
  density.value = id
  localStorage.setItem(DENSITY_KEY, id)
}

/**
 * 拉一页。
 *
 * reset=true 用于筛选变化：先清空，再从 offset 0 开始。
 * 并发保护用 loading 标志——IntersectionObserver 在滚动时会连续触发。
 *
 * 首屏骨架只在「还没加载过任何一页、列表也是空的」时出现；
 * 之后筛选变化同样会清空列表，但用户已经在看内容了，再铺整块骨架只会闪一下，
 * 所以那种情况直接替换列表。
 */
let booted = false
/**
 * 组件是否已卸载。请求可能晚于卸载返回：此时不能再消费 route-scroll 的
 * 待恢复位置，否则会替下一个刚挂载的列表实例把位置取走，导致返回时不对齐。
 */
let disposed = false

/**
 * 从详情页返回时，浏览器/路由的 savedPosition 在骨架期就被应用：
 * 那时只有 12 张骨架卡，页面高度不够，目标位置被钳制到骨架底部；
 * 首屏数据到达、40 张卡铺开之后不会自动回来（浏览器只会保持相对位置）。
 *
 * 所以首屏数据渲染完再按 route-scroll.js 记录的位置对齐一次。
 * 滚动位置由 route-scroll.js 在 popstate 恢复时记录，不依赖 vue-router 内部字段。
 * 只在首屏（骨架）这条路径上调用：筛选变化不重铺骨架，那时用户已经在
 * 当前位置看得好好的，再滚一次反而是干扰。
 */
async function restoreSavedScroll() {
  if (disposed) return
  const top = takeSavedScroll(route.path)
  if (top == null || top === 0) return
  await nextTick()
  window.scrollTo(0, top)
}

async function fetchPage(reset) {
  if (loading.value) return
  loading.value = true
  const first = reset && !booted && games.value.length === 0 && !done.value
  if (reset) {
    games.value = []
    done.value = false
    error.value = ''
  }
  if (first) firstLoading.value = true
  try {
    const { items, total: t } = await api.listGames({
      tag: activeTag.value,
      company: activeCompany.value,
      localization: activeLocalization.value,
      q: query.value.trim(),
      offset: games.value.length,
      limit: PAGE_SIZE,
    })
    games.value = reset ? items : [...games.value, ...items]
    total.value = t
    done.value = games.value.length >= t
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
    firstLoading.value = false
    booted = true
    // 首屏数据已进 DOM：这时再对齐返回位置才滚得到（骨架期高度不够）
    if (first) restoreSavedScroll()
  }
}

/** 搜索防抖：输入时不立刻打后端，避免每个字符一次请求 */
let queryTimer = 0
watch(query, () => {
  clearTimeout(queryTimer)
  queryTimer = setTimeout(() => {
    syncUrl()
    fetchPage(true)
  }, 250)
})
watch([activeTag, activeCompany, activeLocalization], () => {
  syncUrl()
  fetchPage(true)
})
watch(density, () => syncUrl())

/**
 * 把当前筛选写回 URL。
 *
 * 这样从详情页点返回时，列表能靠 URL 还原到同一份结果；
 * 刷新页面也不会丢掉筛选。
 */
function syncUrl() {
  const next = listQuery({
    tag: activeTag.value,
    company: activeCompany.value,
    localization: activeLocalization.value,
    q: query.value.trim(),
    density: density.value,
  })
  const current = route.query
  const same = Object.keys(next).length === Object.keys(current).length
    && Object.entries(next).every(([k, v]) => current[k] === v)
  if (same) return
  router.replace({ path: '/games', query: next })
}

/** 进入详情页：带上当前筛选，返回时据此还原 */
function openGame(gameId) {
  router.push(gameLink(gameId, {
    tag: activeTag.value,
    company: activeCompany.value,
    localization: activeLocalization.value,
    q: query.value.trim(),
    density: density.value,
  }))
}

function enterGame(g) {
  router.push(playLink(g.id, 'list', {
    tag: activeTag.value,
    company: activeCompany.value,
    localization: activeLocalization.value,
    q: query.value.trim(),
    density: density.value,
  }))
}

async function quickToggle(g, ev) {
  ev.stopPropagation()
  await launchToggle(g)
}

let sentinel = null
let observer = null

/**
 * 量出最宽的筛选标签，写进 CSS 变量让三行 chip 左边缘对齐。
 *
 * 中文标签 2–3 字、英文 Company / Localization group 差一倍，
 * 不统一宽度的话切语言后每行 chip 起点都不一样。
 * 语言切换、公司/汉化组开关变化都会改标签文字，所以这几个时机都要重量。
 */
const filterRowsEl = ref(null)
let filterResizeObserver = null

function syncFilterLabelWidth() {
  const box = filterRowsEl.value
  if (!box) return
  let max = 0
  for (const el of box.querySelectorAll('.filter-label')) {
    // 量文字本身宽度：scrollWidth 会把已设置的 min-width 算进去
    const range = document.createRange()
    range.selectNodeContents(el)
    const w = range.getBoundingClientRect().width
    if (w > max) max = w
  }
  box.style.setProperty('--filter-label-w', `${Math.ceil(max)}px`)
}

onMounted(async () => {
  // 四组元数据与首页列表同时发出：谁先回来谁先渲染，不再串成两个 RTT。
  // 元数据失败不影响主列表（列表自身的错误由 fetchPage 报告）。
  const metaPromise = Promise.all([
    api.listTags(), api.listCompanies(), api.listLocalizationGroups(),
  ])
    .then(([tagList, companyList, localizationList]) => {
      tags.value = tagList
      companies.value = companyList
      localizations.value = localizationList
    })
    .catch(() => {})

  // 设置走共享缓存：App.vue / 库页可能已经拉过，这里通常零请求
  const settingsPromise = loadSettings()
    .then((s) => { rotationEnabled.value = s.rotationEnabled !== 'false' })
    .catch(() => {})

  // 四组元数据全部落地才撤掉筛选栏骨架；allSettled 保证失败也不会卡住骨架
  Promise.allSettled([metaPromise, settingsPromise])
    .then(() => { metaLoading.value = false })

  const pagePromise = fetchPage(true)

  await Promise.all([metaPromise, settingsPromise, pagePromise])

  sentinel = document.getElementById('listSentinel')
  if (sentinel && typeof IntersectionObserver !== 'undefined') {
    observer = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting) && !done.value) fetchPage(false)
    }, { rootMargin: '200px' })
    observer.observe(sentinel)
  }

  await nextTick()
  syncFilterLabelWidth()
  // 字体加载完成或窗口缩放都会改变文字宽度
  if (filterRowsEl.value && typeof ResizeObserver !== 'undefined') {
    filterResizeObserver = new ResizeObserver(syncFilterLabelWidth)
    filterResizeObserver.observe(filterRowsEl.value)
  }
})

// 切换语言后标签文字变了，宽度要重量
const stopLocaleWatch = onLocaleChange(async () => {
  await nextTick()
  syncFilterLabelWidth()
})

onBeforeUnmount(() => {
  disposed = true
  clearTimeout(queryTimer)
  observer?.disconnect()
  filterResizeObserver?.disconnect()
  stopLocaleWatch()
})

/**
 * 骨架入场分组：卡片网格与筛选 chip 各自激活。
 *
 * chip 骨架是三段 <template v-if>，拿不到元素引用，所以复用常驻的 .filter-rows
 * 容器（已有 filterRowsEl 引用，一个元素不能挂两个 ref）；它初始展开即可见。
 */
const listSkeletonEl = ref(null)

// nextTick 之后 v-if 分支的骨架才进入 DOM，el 才有值；immediate 覆盖首屏直接进骨架的情形
watch(firstLoading, async (v) => {
  if (!v) return
  await nextTick()
  activateSkeleton(listSkeletonEl.value)
}, { immediate: true })

watch(metaLoading, async (v) => {
  if (!v) return
  await nextTick()
  // 筛选栏被收起时是 display:none，此时量位置全为 0，会把整屏压成一行（D9）。
  // 隐藏期间本来也看不到错峰，直接跳过；下次进入该页会重新挂载并重跑。
  if (filtersCollapsed.value) return
  activateSkeleton(filterRowsEl.value)
}, { immediate: true })

function hostOf(url) {
  if (!url) return ''
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return url
  }
}
</script>

<template>
  <div class="shell">
    <div class="list-fixed">
      <header class="topbar">
        <router-link to="/" class="ghost-btn">{{ t("← 首页") }}</router-link>
        <div class="spacer" />
        <input v-model="query" class="search" :placeholder="t('搜索游戏名 / 简介')" />
        <router-link to="/settings" class="ghost-btn">{{ t("设置") }}</router-link>
      </header>

      <div class="filter-panel" :class="{ 'is-collapsed': filtersCollapsed }">
        <button
          type="button"
          class="filter-collapse"
          :aria-expanded="filtersCollapsed ? 'false' : 'true'"
          :title="filtersCollapsed ? t('展开筛选条件') : t('收起筛选条件')"
          @click="filtersCollapsed = !filtersCollapsed"
        >
          <span>{{ filtersCollapsed ? t('展开') : t('收起') }}</span>
          <span class="filter-caret" :class="{ up: !filtersCollapsed }">▾</span>
        </button>

        <button
          v-if="filtersCollapsed"
          type="button"
          class="filter-collapsed-bar"
          @click="filtersCollapsed = false"
        >
          <span class="filter-collapsed-title">{{ t("筛选条件") }}</span>
          <span v-if="activeFilterCount" class="filter-collapsed-hint">
            {{ activeFilterSummary }}
          </span>
          <span v-else class="filter-collapsed-hint is-empty">{{ t("未设置") }}</span>
        </button>

        <div v-show="!filtersCollapsed" ref="filterRowsEl" class="filter-rows page-skeleton">
          <div v-if="companyEnabled" class="filter-row">
            <span class="filter-label">{{ t("公司") }}</span>
            <template v-if="metaLoading">
              <SkeletonBlock
                v-for="i in 6"
                :key="i"
                class="sk-chip"
                :height="26"
                :width="72"
                :radius="999"
              />
            </template>
            <template v-else>
              <button
                class="tag-chip company"
                :class="{ active: activeCompany == null }"
                @click="activeCompany = null"
              >{{ t("全部") }}</button>
              <button
                v-for="c in visibleCompanies"
                :key="c.id"
                class="tag-chip company"
                :class="{ active: activeCompany === c.id }"
                :title="c.website || c.name"
                @click="activeCompany = activeCompany === c.id ? null : c.id"
              >
                {{ c.name }}<span v-if="c.website" class="chip-site">{{ hostOf(c.website) }}</span>
                <span class="chip-count">{{ c.count }}</span>
              </button>
              <button
                v-if="hasMoreCompanies"
                class="tag-chip more"
                @click="companiesOpen = !companiesOpen"
              >{{ companiesOpen ? t('收起 ▴') : t("更多 {0} ▾", companyChips.length - CHIP_LIMIT) }}</button>
              <span v-if="!companies.length" class="filter-hint">
                {{ t("还没有公司，可在「公司维护」里添加") }}</span>
            </template>
          </div>

          <div v-if="localizationEnabled" class="filter-row">
            <span class="filter-label">{{ t("汉化组") }}</span>
            <template v-if="metaLoading">
              <SkeletonBlock
                v-for="i in 6"
                :key="i"
                class="sk-chip"
                :height="26"
                :width="72"
                :radius="999"
              />
            </template>
            <template v-else>
              <button
                class="tag-chip company"
                :class="{ active: activeLocalization == null }"
                @click="activeLocalization = null"
              >{{ t("全部") }}</button>
              <button
                v-for="g in visibleLocalizations"
                :key="g.id"
                class="tag-chip company"
                :class="{ active: activeLocalization === g.id }"
                :title="g.website || g.description || g.name"
                @click="activeLocalization = activeLocalization === g.id ? null : g.id"
              >
                {{ g.name }}<span v-if="g.alias" class="chip-site">{{ g.alias }}</span>
                <span class="chip-count">{{ g.count }}</span>
              </button>
              <button
                v-if="hasMoreLocalizations"
                class="tag-chip more"
                @click="localizationsOpen = !localizationsOpen"
              >{{ localizationsOpen ? t('收起 ▴') : t("更多 {0} ▾", localizationChips.length - CHIP_LIMIT) }}</button>
              <span v-if="!localizations.length" class="filter-hint">
                {{ t("还没有汉化组，可在「汉化组维护」里添加") }}</span>
            </template>
          </div>

          <div class="filter-row">
            <span class="filter-label">{{ t("标签") }}</span>
            <template v-if="metaLoading">
              <SkeletonBlock
                v-for="i in 6"
                :key="i"
                class="sk-chip"
                :height="26"
                :width="72"
                :radius="999"
              />
            </template>
            <template v-else>
              <button class="tag-chip" :class="{ active: !activeTag }" @click="activeTag = ''">{{ t("全部") }}</button>
              <button
                v-for="tag in tagChips"
                :key="tag.id"
                class="tag-chip"
                :class="{ active: activeTag === tag.name }"
                @click="activeTag = activeTag === tag.name ? '' : tag.name"
              >{{ tag.name }}</button>
              <span v-if="!tags.length" class="filter-hint">{{ t("还没有标签") }}</span>
            </template>
          </div>
        </div>
      </div>

      <div class="section-head">
        <h2>{{ t("全部游戏") }}</h2>
        <span class="muted-sm">{{ t("共 {0} 款 · 已显示 {1}", total, games.length) }}</span>
        <div class="spacer" />
        <div class="seg density-seg">
          <button
            v-for="d in densities"
            :key="d.id"
            :class="{ on: density === d.id }"
            @click="setDensity(d.id)"
          >{{ d.label }}</button>
        </div>
      </div>
    </div>

    <div v-if="error" class="empty">{{ t("加载失败：{0}", error) }}</div>
    <div v-else-if="!loading && !games.length" class="empty">
      {{ hasFilter ? t('没有符合筛选条件的游戏。') : t('游戏库为空。用 galworld add 注册游戏后刷新。') }}
    </div>
    <template v-else>
      <!-- 首屏骨架：只铺 12 张覆盖首屏，不追求与整页 40 张等高；
           逐卡尺寸与真实卡片对齐，所以单卡不跳 -->
      <div v-if="firstLoading" ref="listSkeletonEl" class="grid list-grid page-skeleton" :data-density="density">
        <article v-for="i in 12" :key="i" class="card sk-card">
          <div class="cover"><SkeletonBlock class="sk-cover" height="100%" :radius="0" /></div>
          <div class="card-body">
            <SkeletonBlock :height="12" width="70%" />
            <SkeletonBlock :height="11" width="45%" />
            <SkeletonBlock :height="11" width="30%" />
          </div>
        </article>
      </div>
      <div v-else class="grid list-grid" :data-density="density">
        <article
          v-for="g in games"
          :key="g.id"
          class="card"
          @pointerenter="prefetch(`/game/${g.id}`)"
          @click="openGame(g.id)"
        >
          <GameCard
            :game="g"
            :busy="busyId === g.id"
            :launching="!!launches[g.id]"
            :action="launches[g.id]?.action || ''"
            :progress="launches[g.id]?.percent ?? null"
            :progress-label="launches[g.id]?.label || ''"
            :rotation-enabled="rotationEnabled"
            @toggle="quickToggle(g, $event)"
            @open="enterGame(g)"
          />
        </article>
      </div>

      <div id="listSentinel" class="load-more" :class="{ done }">
        {{ done ? t('已经到底了') : loading ? t('加载中…') : t('继续滚动加载…') }}
      </div>
    </template>
  </div>
</template>

<style scoped>
/* 筛选栏骨架 chip：与 .tag-chip 同尺寸；flex:none 保证不被行内压缩 */
.sk-chip {
  flex: none;
}
</style>
