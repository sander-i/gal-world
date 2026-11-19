<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
/**
 * 游戏卡片：首页推荐位与全部游戏列表页共用。
 *
 * 首页传 recommend 接口的条目、列表页传 GameView，两者字段并集覆盖本组件所需；
 * 首页条目没有 width/height/iconPath 时对应位置留空，不报错。
 */
import { computed } from 'vue'
import { t } from '../i18n.js'
import MediaRotator from './MediaRotator.vue'
import { formatPlaytimeShort } from '../format'
import { latencyTone } from '../latency'
import { companyEnabled, localizationEnabled } from '../feature-flags'

defineProps({
  game: { type: Object, required: true },
  busy: { type: Boolean, default: false },
  launching: { type: Boolean, default: false },
  action: { type: String, default: '' },
  progress: { type: Number, default: null },
  progressLabel: { type: String, default: '' },
  latency: { type: Number, default: null },
  rotationEnabled: { type: Boolean, default: true },
  sizes: { type: String, default: '(max-width: 700px) 50vw, 320px' },
  priority: { type: String, default: 'auto' },
})
defineEmits(['toggle', 'open'])

/** 状态文案：computed 而非常量，否则语言切换后不更新 */
const stateText = computed(() => ({
  RUNNING: t('运行中'),
  STOPPED: t('已停止'),
  FAILED: t('异常'),
  UNKNOWN: t('未知'),
  UNSUPPORTED: t('不支持'),
}))
</script>

<template>
  <div class="cover">
    <MediaRotator
      v-if="game.covers && game.covers.length"
      :items="game.covers"
      :name="game.name"
      :interval="game.coverRotationInterval || 6"
      :mode="game.coverRotationMode || 'seq'"
      :show-dots="game.covers.length > 1"
      :enabled="rotationEnabled"
      :sizes="sizes"
      :priority="priority"
    />
    <span v-else class="initial">{{ game.name.slice(0, 1) }}</span>
    <span class="badge" :class="game.state">{{ stateText[game.state] || game.state }}</span>
    <span
      v-if="game.state === 'RUNNING' && latency != null"
      class="latency-badge"
      :class="latencyTone(latency)"
    >{{ latency }} ms</span>
  </div>

  <div class="card-body">
    <div class="card-title">
      <!-- 卡片图标不在首屏关键路径上：延迟加载，解码异步，避免阻塞首屏渲染。
           注意不要给 MediaRotator 加同样的属性，它用游离 Image 预加载，lazy 会让 onload 永不触发。 -->
      <img
        v-if="game.iconPath"
        :src="game.iconPath"
        class="card-icon"
        :alt="game.name"
        loading="lazy"
        decoding="async"
      />
      {{ game.name }}
    </div>
    <div v-if="companyEnabled && game.companies && game.companies.length" class="card-company">
      {{ game.companies.map((c) => c.name).join(' × ') }}
    </div>
    <div
      v-if="localizationEnabled && game.localizations && game.localizations.length"
      class="card-company card-localization"
    >
      {{ game.localizations.map((g) => g.name).join(' × ') }}
    </div>
    <div class="card-tags">
      <span v-for="tag in (game.tags || [])" :key="tag" class="mini-tag">{{ tag }}</span>
    </div>
    <div v-if="game.width && game.height" class="card-meta">
      {{ game.width }}×{{ game.height }}
      <span class="dot-sep">·</span>
      <span :title="t('累计游玩 {0} 秒', game.totalSeconds)">
        {{ formatPlaytimeShort(game.totalSeconds) }}
      </span>
    </div>
    <div v-else class="card-meta">
      <span :title="t('累计游玩 {0} 秒', game.totalSeconds)">
        {{ formatPlaytimeShort(game.totalSeconds) }}
      </span>
    </div>
    <div class="card-actions">
      <button
        class="mini-btn"
        :class="{ danger: game.state === 'RUNNING' && !launching }"
        :disabled="busy || launching"
        @click.stop="$emit('toggle', $event)"
      >{{ launching ? (action === 'stop' ? t('关闭中…') : t('启动中…')) : game.state === 'RUNNING' ? t('停止') : t('启动') }}</button>
      <button
        v-if="game.state === 'RUNNING' && !launching"
        class="mini-btn primary"
        @click.stop="$emit('open', $event)"
      >{{ t("进入") }}</button>
    </div>
    <div v-if="launching" class="card-launch">
      <div class="card-launch-track">
        <span class="card-launch-fill" :style="{ width: (progress || 0) + '%' }" />
      </div>
      <div class="card-launch-label">
        <span>{{ progressLabel }}</span>
        <span class="card-launch-pct">{{ progress || 0 }}%</span>
      </div>
    </div>
  </div>
</template>
