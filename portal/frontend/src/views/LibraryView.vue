<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
import { onLocaleChange, t } from '../i18n.js'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { prefetch } from '../prefetch'
import { activateSkeleton } from '../skeleton-fx.js'
import { api } from '../api'
import { notify } from '../feedback'
import { formatPlaytimeShort } from '../format'
import MediaRotator from '../components/MediaRotator.vue'
import GameCard from '../components/GameCard.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import { useGameLaunch } from '../use-game-launch'
import { useLatency } from '../use-latency'
import { useSessionRefresh } from '../use-session-refresh'
import { playLink } from '../play-origin'
import { companyEnabled, loadSettings, localizationEnabled } from '../feature-flags'

const router = useRouter()

const hero = ref(null)
const recommended = ref([])
/** 分区 loading：Hero 区与推荐网格各自独立，谁先回来谁先渲染 */
const heroLoading = ref(true)
const gridLoading = ref(true)
const error = ref('')
const switchMode = ref('page')
const rotationEnabled = ref(true)
const heroSize = ref('medium')

/** 首页固定推荐条数；后端返回不足时按实际渲染，不补位 */
const RECOMMEND_COUNT = 10

/**
 * 推荐结果要等 Hero 确定后才能剔除 Hero 那款：
 * 多拉 1 条是因为后端 recommend 的 exclude 没有补位机制，剔除后会少一款。
 * 两个请求是同时发出的，这里的「等」只影响赋值时机，不增加 RTT。
 */
let pendingRec = null
let heroSettled = false
/** 语言切换会重跑 load()，旧请求晚到时不能覆盖新结果 */
let loadToken = 0

/** Hero 已确定且推荐已到达 → 剔除 Hero、截断到 10 条并渲染 */
function flushRecommend() {
  if (pendingRec === null || !heroSettled) return
  const heroId = hero.value?.gameId || ''
  // Hero 为 null（204）或推荐里没有它时，直接取前 10 条
  const list = heroId ? pendingRec.filter((g) => g.gameId !== heroId) : pendingRec
  recommended.value = list.slice(0, RECOMMEND_COUNT)
  gridLoading.value = false
}

function load() {
  const token = ++loadToken
  const fresh = () => token === loadToken
  heroLoading.value = true
  gridLoading.value = true
  error.value = ''
  pendingRec = null
  heroSettled = false

  // 三个请求同时发出，不再等 hero 回来才拉推荐（原来 2 个 RTT）
  const settingsPromise = loadSettings()
  const heroPromise = api.hero()
  const recPromise = api.recommend(RECOMMEND_COUNT + 1)

  // 设置：loadSettings 内部失败已兜底为 {}，这里用默认值继续
  settingsPromise.then((s) => {
    if (!fresh()) return
    const st = s || {}
    switchMode.value = st.switchMode || 'page'
    rotationEnabled.value = st.rotationEnabled !== 'false'
    heroSize.value = st.homeHeroSize === 'off' ? 'off' : 'medium'
  }).catch(() => {})

  // Hero：失败只清空推荐位，不阻塞网格，也不弹整页错误
  heroPromise
    .then((h) => {
      if (fresh()) hero.value = h || null
    }, () => {
      if (fresh()) hero.value = null
    })
    .finally(() => {
      if (!fresh()) return
      heroSettled = true
      heroLoading.value = false
      flushRecommend()
    })

  // 推荐：先暂存；Hero 已确定就立即剔除渲染，否则等 Hero 回来再 flush
  recPromise.then((list) => {
    if (!fresh()) return
    pendingRec = Array.isArray(list) ? list : []
    flushRecommend()
  }, (e) => {
    if (!fresh()) return
    error.value = e.message
    gridLoading.value = false
  })
}

/** 任意推荐卡片在运行 → 需要显示时延 */
const anyRunning = computed(() => recommended.value.some((g) => g.state === 'RUNNING'))
const { latency } = useLatency(anyRunning)

/**
 * 有会话在跑才值得轮询：全都停止时状态不会自己变化。
 * Hero 也要算进来，否则只有 Hero 在跑时它的状态会一直停在旧值。
 * 代价：从门户外（命令行 systemctl）启动的游戏，页面不会自动发现。
 */
const anyLive = computed(() =>
  hero.value?.state === 'RUNNING' || recommended.value.some((g) => g.state === 'RUNNING'))

/** 首页推荐卡片与 Hero 共用同一个会话操作器，按 gameId 记录进度 */
const { busyId, launches, toggle: launchToggle } = useGameLaunch({
  idOf: (g) => g.gameId,
  onError: (msg) => notify.error(msg),
  onStarted: (id) => setGameState(id, 'RUNNING'),
  onStopped: (id) => {
    setGameState(id, 'STOPPED')
    notify.success(t('游戏已关闭'))
  },
})

function setGameState(id, state) {
  const i = recommended.value.findIndex((x) => x.gameId === id)
  if (i >= 0) recommended.value[i] = { ...recommended.value[i], state }
  if (hero.value?.gameId === id) hero.value = { ...hero.value, state }
}

/** 状态监听：定时拉 /session，刷新 Hero 与卡片的运行状态 */
useSessionRefresh(
  () => (anyLive.value
    ? [hero.value?.gameId, ...recommended.value.map((g) => g.gameId)]
    : []),
  (id, state, st) => {
    const cur = id === hero.value?.gameId
      ? hero.value?.state
      : recommended.value.find((g) => g.gameId === id)?.state
    // systemd 启停过程中的瞬时 UNKNOWN 不覆盖已知状态，避免闪「未知」
    if (state === 'UNKNOWN' && cur === 'RUNNING') return
    setGameState(id, st.crashed ? 'FAILED' : state)
  },
  { intervalMs: 1000 },
)

const heroAction = computed(() => (hero.value ? launches[hero.value.gameId] : null))
const heroBusy = computed(() =>
  !!heroAction.value || (busyId.value !== '' && busyId.value === hero.value?.gameId))

async function quickToggle(g, ev) {
  ev.stopPropagation()
  const updated = await launchToggle(g)
  // 启动期间后端已返回 RUNNING，但串流未就绪；此时不能提前显示「进入」
  if (updated?.state && !launches[g.gameId]) {
    const i = recommended.value.findIndex((x) => x.gameId === g.gameId)
    if (i >= 0) recommended.value[i] = { ...recommended.value[i], state: updated.state }
  }
}

/** 推荐卡片「进入」：直接进游玩窗口，不改变会话状态 */
function enterGame(g) {
  const href = playLink(g.gameId)
  if (switchMode.value === 'tab') {
    window.open(`${location.origin}${href}`, '_blank')
  } else {
    router.push(href)
  }
}

function enterHeroPlay() {
  const href = playLink(hero.value.gameId)
  if (switchMode.value === 'tab') {
    window.open(`${location.origin}${href}`, '_blank')
  } else {
    router.push(href)
  }
}

/** 推荐位「继续游玩 / 开始游戏」：未运行先启动，等串流就绪再进游玩窗口 */
async function openHero() {
  if (heroBusy.value || !hero.value) return
  if (hero.value.state !== 'RUNNING') {
    const started = await launchToggle({ gameId: hero.value.gameId, state: hero.value.state })
    if (!started) return
  }
  enterHeroPlay()
}

/** 推荐位「关闭游戏」：只关闭会话，不离开首页 */
async function stopHero() {
  if (heroBusy.value || !hero.value) return
  await launchToggle({ gameId: hero.value.gameId, state: 'RUNNING' })
}

function fmtDay(iso) {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const today = new Date()
  const sameDay = d.toDateString() === today.toDateString()
  const p = (x) => String(x).padStart(2, '0')
  return sameDay ? t("今天 {0}:{1}", p(d.getHours()), p(d.getMinutes()))
    : t("{0}月{1}日", d.getMonth() + 1, d.getDate())
}

onMounted(load)

/**
 * 骨架入场分组：Hero 与推荐网格各自独立，因为两个 loading 标志互不相关。
 * Hero 还要跟 heroSize 走：设置晚于骨架返回时，骨架会从横条换成 medium 大图
 * （v-else-if 换了元素），新容器没有 active 类，必须重新激活。
 *
 * 必须等 nextTick：watch 回调触发时 v-if 分支还没换成骨架 DOM，el.value 为 null；
 * 也必须等骨架真正可见，activateSkeleton 才能量出每块的 top 做视觉行分组（D9）。
 * immediate 覆盖「组件挂载时已经处于 loading」的首屏情形。
 */
const heroSkeletonEl = ref(null)
const gridSkeletonEl = ref(null)

watch([heroLoading, heroSize], async ([loading]) => {
  if (!loading) return
  await nextTick()
  activateSkeleton(heroSkeletonEl.value)
}, { immediate: true })

watch(gridLoading, async (v) => {
  if (!v) return
  await nextTick()
  activateSkeleton(gridSkeletonEl.value)
}, { immediate: true })

// Hero 的推荐理由与推荐条的理由由后端按请求语言翻译，切语言后要重取
const stopLocaleWatch = onLocaleChange(() => load())
onBeforeUnmount(stopLocaleWatch)
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <div class="brand">GAL <em>WORLD</em></div>
      <div class="spacer" />
      <router-link to="/games/import" class="ghost-btn">{{ t("＋ 接入游戏") }}</router-link>
      <router-link to="/games" class="ghost-btn">{{ t("全部游戏") }}</router-link>
      <router-link v-if="companyEnabled" to="/companies" class="ghost-btn">{{ t("公司维护") }}</router-link>
      <router-link v-if="localizationEnabled" to="/localization-groups" class="ghost-btn">{{ t("汉化组维护") }}</router-link>
      <router-link to="/tags" class="ghost-btn">{{ t("标签维护") }}</router-link>
      <router-link to="/settings" class="ghost-btn">{{ t("设置") }}</router-link>
      <router-link to="/admin" class="ghost-btn" :title="t('后台管理')">{{ t("管理") }}</router-link>
    </header>

    <!-- 推荐位骨架：medium 档与真实 Hero 同高（190px），避免数据到达时跳变 -->
    <section
      v-if="heroLoading && heroSize !== 'off'"
      ref="heroSkeletonEl"
      class="hero sk-hero page-skeleton"
      data-size="medium"
      aria-busy="true"
    >
      <div class="hero-bg">
        <SkeletonBlock class="sk-cover" height="100%" :radius="0" />
      </div>
      <div class="hero-body">
        <div class="hero-head">
          <SkeletonBlock :height="25" width="55%" />
        </div>
        <SkeletonBlock :height="13" width="40%" />
        <div class="hero-actions">
          <!-- 尺寸由 .sk-btn 提供（lg 高度 + 胶囊圆角），与真实 .hero-cta 对齐 -->
          <SkeletonBlock class="sk-btn" />
        </div>
      </div>
    </section>

    <!-- off 档只留横条：骨架也按横条高度铺，避免 190px → 横条的高度塌陷 -->
    <section
      v-else-if="heroLoading"
      ref="heroSkeletonEl"
      class="hero hero-strip-wrap page-skeleton"
      data-size="small"
      aria-busy="true"
    >
      <div class="hero-strip">
        <SkeletonBlock :height="13" width="110" />
        <SkeletonBlock :height="16" width="160" />
        <div class="sk-strip-meta">
          <SkeletonBlock :height="13" width="90" />
        </div>
        <SkeletonBlock :height="28" width="96" />
      </div>
    </section>

    <template v-else>
      <!-- 推荐位：medium 大图 / off 只留横条 -->
      <section
        v-if="hero && heroSize !== 'off'"
        class="hero"
        data-size="medium"
        @pointerenter="prefetch(`/game/${hero.gameId}`)"
        @click="router.push(`/game/${hero.gameId}`)"
      >
        <div class="hero-bg">
          <MediaRotator
            v-if="hero.covers && hero.covers.length"
            :items="hero.covers"
            :name="hero.name"
            :enabled="rotationEnabled"
            sizes="(max-width: 900px) 100vw, 34vw"
            priority="high"
          />
          <span v-else class="hero-initial">{{ hero.name.slice(0, 1) }}</span>
        </div>
        <div class="hero-body">
          <div class="hero-head">
            <h2 class="hero-title">{{ hero.name }}</h2>
            <span class="hero-reason">{{ hero.reason }}</span>
          </div>
          <div class="hero-meta">
            {{ t("累计 {0}", formatPlaytimeShort(hero.totalSeconds)) }}<span v-if="hero.lastPlayed" class="dot-sep">·</span>
            <span v-if="hero.lastPlayed">{{ t("上次 {0}", fmtDay(hero.lastPlayed)) }}</span>
          </div>
          <div class="hero-actions">
            <button
              v-if="hero.state === 'RUNNING'"
              class="btn primary hero-cta"
              :disabled="heroBusy"
              @click.stop="openHero"
            >{{ t("继续游玩") }}</button>
            <button
              v-else
              class="btn primary hero-cta"
              :disabled="heroBusy"
              @click.stop="openHero"
            >{{ heroAction?.action === 'start' ? t('启动中 ') + heroAction.percent + '%' : t('开始游戏') }}</button>
            <button
              v-if="hero.state === 'RUNNING'"
              class="btn hero-cta"
              :disabled="heroBusy"
              @click.stop="stopHero"
            >{{ heroAction?.action === 'stop' ? t('关闭中 ') + heroAction.percent + '%' : t('关闭游戏') }}</button>
          </div>
          <div v-if="heroAction" class="action-progress">
            <div class="action-progress-track">
              <span class="action-progress-fill" :style="{ width: heroAction.percent + '%' }" />
            </div>
            <div class="action-progress-label">
              <span>{{ heroAction.label }}</span>
              <span>{{ heroAction.percent }}%</span>
            </div>
          </div>
        </div>
      </section>

      <section
        v-else-if="hero"
        class="hero hero-strip-wrap"
        data-size="small"
        @pointerenter="prefetch(`/game/${hero.gameId}`)"
        @click="router.push(`/game/${hero.gameId}`)"
      >
        <div class="hero-strip">
          <span class="hero-strip-label">{{ hero.reason }}</span>
          <b>{{ hero.name }}</b>
          <span class="hero-strip-meta">{{ t("累计 {0}", formatPlaytimeShort(hero.totalSeconds)) }}</span>
          <template v-if="hero.state === 'RUNNING'">
            <button class="btn primary sm" :disabled="heroBusy" @click.stop="openHero">{{ t("继续游玩") }}</button>
            <button class="btn sm" :disabled="heroBusy" @click.stop="stopHero">
              {{ heroAction?.action === 'stop' ? t('关闭中 ') + heroAction.percent + '%' : t('关闭游戏') }}
            </button>
          </template>
          <button v-else class="btn primary sm" :disabled="heroBusy" @click.stop="openHero">
            {{ heroAction?.action === 'start' ? t('启动中 ') + heroAction.percent + '%' : t('开始游戏') }}
          </button>
        </div>
        <div v-if="heroAction" class="action-progress hero-progress">
          <div class="action-progress-track">
            <span class="action-progress-fill" :style="{ width: heroAction.percent + '%' }" />
          </div>
          <div class="action-progress-label">
            <span>{{ heroAction.label }}</span>
            <span>{{ heroAction.percent }}%</span>
          </div>
        </div>
      </section>
    </template>

    <div class="section-head">
      <h2>{{ t("为你推荐") }}</h2>
      <span class="muted-sm">{{ t("按推荐算法排序的前 {0} 款", RECOMMEND_COUNT) }}</span>
      <router-link to="/games" class="ghost-btn">{{ t("全部游戏 →") }}</router-link>
    </div>

    <!-- 网格骨架：10 张卡，cover 用 16:9 预留高度，数据到达不跳变 -->
    <div v-if="error" class="empty">{{ t("加载失败：{0}", error) }}</div>
    <div v-else-if="gridLoading" ref="gridSkeletonEl" class="grid grid-fixed-5 page-skeleton" aria-busy="true">
      <article v-for="i in 10" :key="i" class="card sk-card">
        <div class="cover">
          <SkeletonBlock class="sk-cover" height="100%" :radius="0" />
        </div>
        <div class="card-body">
          <SkeletonBlock :height="12" width="80%" />
          <SkeletonBlock :height="12" width="60%" />
          <SkeletonBlock :height="12" width="40%" />
        </div>
      </article>
    </div>
    <div v-else-if="!recommended.length" class="empty">
      {{ t("还没有游戏。用") }}<code>galworld add</code> {{ t("注册后刷新。") }}</div>
    <div v-else class="grid grid-fixed-5">
      <article
        v-for="g in recommended"
        :key="g.gameId"
        class="card"
        @pointerenter="prefetch(`/game/${g.gameId}`)"
        @click="router.push(`/game/${g.gameId}`)"
      >
        <GameCard
          :game="g"
          :busy="busyId === g.gameId"
          :launching="!!launches[g.gameId]"
          :action="launches[g.gameId]?.action || ''"
          :progress="launches[g.gameId]?.percent ?? null"
          :progress-label="launches[g.gameId]?.label || ''"
          :latency="latency"
          :rotation-enabled="rotationEnabled"
          sizes="(max-width: 1100px) 25vw, 300px"
          priority="low"
          @toggle="quickToggle(g, $event)"
          @open="enterGame(g)"
        />
      </article>
    </div>
  </div>
</template>

<style scoped>
/* 骨架卡片的正文高度对齐真实卡片（标题 22 + 元信息 20 + 操作行 38 + 内边距 30 + 间距 8） */
.sk-card .card-body { min-height: 118px; }

/* off 档横条骨架：元信息推到右侧，与 .hero-strip-meta 的 margin-left:auto 一致 */
.sk-strip-meta { margin-left: auto; }

/* Hero 骨架不可点、不响应 hover 位移（真实 Hero 有 cursor:pointer 与 -2px 上浮） */
.sk-hero { cursor: default; }
.sk-hero:hover { transform: none; border-color: var(--line); }
</style>
