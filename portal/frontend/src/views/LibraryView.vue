<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
import { onLocaleChange, t } from '../i18n.js'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { notify } from '../feedback'
import { formatPlaytimeShort } from '../format'
import MediaRotator from '../components/MediaRotator.vue'
import GameCard from '../components/GameCard.vue'
import { useGameLaunch } from '../use-game-launch'
import { useLatency } from '../use-latency'
import { useSessionRefresh } from '../use-session-refresh'
import { playLink } from '../play-origin'
import { companyEnabled, localizationEnabled } from '../feature-flags'

const router = useRouter()

const hero = ref(null)
const recommended = ref([])
const loading = ref(true)
const error = ref('')
const switchMode = ref('page')
const rotationEnabled = ref(true)
const heroSize = ref('medium')

/** 首页固定推荐条数；后端返回不足时按实际渲染，不补位 */
const RECOMMEND_COUNT = 10

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [h, s] = await Promise.all([api.hero(), api.settings()])
    hero.value = h
    switchMode.value = s.switchMode || 'page'
    rotationEnabled.value = s.rotationEnabled !== 'false'
    heroSize.value = s.homeHeroSize === 'off' ? 'off' : 'medium'
    // Hero 占用的游戏从推荐里排除，避免同一款游戏在首页出现两次
    recommended.value = await api.recommend(RECOMMEND_COUNT, h?.gameId || '')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

/** 任意推荐卡片在运行 → 需要显示时延 */
const anyRunning = computed(() => recommended.value.some((g) => g.state === 'RUNNING'))
const { latency } = useLatency(anyRunning)

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
  () => [hero.value?.gameId, ...recommended.value.map((g) => g.gameId)],
  (id, state, st) => {
    const cur = id === hero.value?.gameId
      ? hero.value?.state
      : recommended.value.find((g) => g.gameId === id)?.state
    // systemd 启停过程中的瞬时 UNKNOWN 不覆盖已知状态，避免闪「未知」
    if (state === 'UNKNOWN' && cur === 'RUNNING') return
    setGameState(id, st.crashed ? 'FAILED' : state)
  },
  { intervalMs: 5000 },
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

    <div v-if="loading" class="loading">{{ t("加载中…") }}</div>
    <div v-else-if="error" class="empty">{{ t("加载失败：{0}", error) }}</div>

    <template v-else>
      <!-- 推荐位：medium 大图 / off 只留横条 -->
      <section
        v-if="hero && heroSize !== 'off'"
        class="hero"
        data-size="medium"
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

      <div class="section-head">
        <h2>{{ t("为你推荐") }}</h2>
        <span class="muted-sm">{{ t("按推荐算法排序的前 {0} 款", RECOMMEND_COUNT) }}</span>
        <router-link to="/games" class="ghost-btn">{{ t("全部游戏 →") }}</router-link>
      </div>

      <div v-if="!recommended.length" class="empty">
        {{ t("还没有游戏。用") }}<code>galworld add</code> {{ t("注册后刷新。") }}</div>
      <div v-else class="grid grid-fixed-5">
        <article
          v-for="g in recommended"
          :key="g.gameId"
          class="card"
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
    </template>
  </div>
</template>
