<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { notify } from '../feedback'
import { gameLink, listQuery, readListQuery } from '../list-origin'
import { playLink } from '../play-origin'
import GameCard from '../components/GameCard.vue'
import { companyEnabled, localizationEnabled } from '../feature-flags'
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
const busyId = ref('')
const rotationEnabled = ref(true)

const DENSITIES = [
  { id: 'cozy', label: '标准' },
  { id: 'compact', label: '紧凑' },
  { id: 'list', label: '列表' },
]

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
    parts.push(companies.value.find((c) => c.id === activeCompany.value)?.name || '公司')
  }
  if (activeLocalization.value != null) {
    parts.push(localizations.value.find((g) => g.id === activeLocalization.value)?.name || '汉化组')
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
 */
async function fetchPage(reset) {
  if (loading.value) return
  loading.value = true
  if (reset) {
    games.value = []
    done.value = false
    error.value = ''
  }
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
  if (busyId.value) return
  busyId.value = g.id
  try {
    const updated = g.state === 'RUNNING'
      ? await api.stopGame(g.id)
      : await api.startGame(g.id)
    const i = games.value.findIndex((x) => x.id === g.id)
    if (i >= 0) games.value[i] = updated
  } catch (e) {
    notify.error('操作失败：' + e.message)
  } finally {
    busyId.value = ''
  }
}

let sentinel = null
let observer = null

onMounted(async () => {
  try {
    const [t, c, g, s] = await Promise.all([
      api.listTags(), api.listCompanies(), api.listLocalizationGroups(), api.settings(),
    ])
    tags.value = t
    companies.value = c
    localizations.value = g
    rotationEnabled.value = s.rotationEnabled !== 'false'
  } catch {
    // 标签 / 公司拉取失败不影响主列表，列表自身的错误由 fetchPage 报告
  }
  await fetchPage(true)

  sentinel = document.getElementById('listSentinel')
  if (sentinel && typeof IntersectionObserver !== 'undefined') {
    observer = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting) && !done.value) fetchPage(false)
    }, { rootMargin: '200px' })
    observer.observe(sentinel)
  }
})

onBeforeUnmount(() => {
  clearTimeout(queryTimer)
  observer?.disconnect()
})

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
        <router-link to="/" class="ghost-btn">← 首页</router-link>
        <div class="spacer" />
        <input v-model="query" class="search" placeholder="搜索游戏名 / 简介" />
        <router-link to="/settings" class="ghost-btn">设置</router-link>
      </header>

      <div class="filter-panel" :class="{ 'is-collapsed': filtersCollapsed }">
        <button
          type="button"
          class="filter-collapse"
          :aria-expanded="filtersCollapsed ? 'false' : 'true'"
          :title="filtersCollapsed ? '展开筛选条件' : '收起筛选条件'"
          @click="filtersCollapsed = !filtersCollapsed"
        >
          <span>{{ filtersCollapsed ? '展开' : '收起' }}</span>
          <span class="filter-caret" :class="{ up: !filtersCollapsed }">▾</span>
        </button>

        <button
          v-if="filtersCollapsed"
          type="button"
          class="filter-collapsed-bar"
          @click="filtersCollapsed = false"
        >
          <span class="filter-collapsed-title">筛选条件</span>
          <span v-if="activeFilterCount" class="filter-collapsed-hint">
            {{ activeFilterSummary }}
          </span>
          <span v-else class="filter-collapsed-hint is-empty">未设置</span>
        </button>

        <div v-show="!filtersCollapsed" class="filter-rows">
          <div v-if="companyEnabled" class="filter-row">
            <span class="filter-label">公司</span>
            <button
              class="tag-chip company"
              :class="{ active: activeCompany == null }"
              @click="activeCompany = null"
            >全部</button>
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
            >{{ companiesOpen ? '收起 ▴' : `更多 ${companyChips.length - CHIP_LIMIT} ▾` }}</button>
            <span v-if="!companies.length" class="filter-hint">
              还没有公司，可在「公司维护」里添加
            </span>
          </div>

          <div v-if="localizationEnabled" class="filter-row">
            <span class="filter-label">汉化组</span>
            <button
              class="tag-chip company"
              :class="{ active: activeLocalization == null }"
              @click="activeLocalization = null"
            >全部</button>
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
            >{{ localizationsOpen ? '收起 ▴' : `更多 ${localizationChips.length - CHIP_LIMIT} ▾` }}</button>
            <span v-if="!localizations.length" class="filter-hint">
              还没有汉化组，可在「汉化组维护」里添加
            </span>
          </div>

          <div class="filter-row">
            <span class="filter-label">标签</span>
            <button class="tag-chip" :class="{ active: !activeTag }" @click="activeTag = ''">全部</button>
            <button
              v-for="t in tagChips"
              :key="t.id"
              class="tag-chip"
              :class="{ active: activeTag === t.name }"
              @click="activeTag = activeTag === t.name ? '' : t.name"
            >{{ t.name }}</button>
            <span v-if="!tags.length" class="filter-hint">还没有标签</span>
          </div>
        </div>
      </div>

      <div class="section-head">
        <h2>全部游戏</h2>
        <span class="muted-sm">共 {{ total }} 款 · 已显示 {{ games.length }}</span>
        <div class="spacer" />
        <div class="seg density-seg">
          <button
            v-for="d in DENSITIES"
            :key="d.id"
            :class="{ on: density === d.id }"
            @click="setDensity(d.id)"
          >{{ d.label }}</button>
        </div>
      </div>
    </div>

    <div v-if="error" class="empty">加载失败：{{ error }}</div>
    <div v-else-if="!loading && !games.length" class="empty">
      {{ hasFilter ? '没有符合筛选条件的游戏。' : '游戏库为空。用 galworld add 注册游戏后刷新。' }}
    </div>
    <template v-else>
      <div class="grid list-grid" :data-density="density">
        <article
          v-for="g in games"
          :key="g.id"
          class="card"
          @click="openGame(g.id)"
        >
          <GameCard
            :game="g"
            :busy="busyId === g.id"
            :rotation-enabled="rotationEnabled"
            @toggle="quickToggle(g, $event)"
            @open="enterGame(g)"
          />
        </article>
      </div>

      <div id="listSentinel" class="load-more" :class="{ done }">
        {{ done ? '已经到底了' : loading ? '加载中…' : '继续滚动加载…' }}
      </div>
    </template>
  </div>
</template>
