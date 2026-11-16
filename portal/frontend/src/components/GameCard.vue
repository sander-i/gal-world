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

const STATE_TEXT = {
  RUNNING: '运行中',
  STOPPED: '已停止',
  FAILED: '异常',
  UNKNOWN: '未知',
  UNSUPPORTED: '不支持',
}
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
    <span class="badge" :class="game.state">{{ STATE_TEXT[game.state] || game.state }}</span>
    <span
      v-if="game.state === 'RUNNING' && latency != null"
      class="latency-badge"
      :class="latencyTone(latency)"
    >{{ latency }} ms</span>
  </div>

  <div class="card-body">
    <div class="card-title">
      <img v-if="game.iconPath" :src="game.iconPath" class="card-icon" :alt="game.name" />
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
      <span v-for="t in (game.tags || [])" :key="t" class="mini-tag">{{ t }}</span>
    </div>
    <div v-if="game.width && game.height" class="card-meta">
      {{ game.width }}×{{ game.height }}
      <span class="dot-sep">·</span>
      <span :title="'累计游玩 ' + game.totalSeconds + ' 秒'">
        {{ formatPlaytimeShort(game.totalSeconds) }}
      </span>
    </div>
    <div v-else class="card-meta">
      <span :title="'累计游玩 ' + game.totalSeconds + ' 秒'">
        {{ formatPlaytimeShort(game.totalSeconds) }}
      </span>
    </div>
    <div class="card-actions">
      <button
        class="mini-btn"
        :class="{ danger: game.state === 'RUNNING' && !launching }"
        :disabled="busy || launching"
        @click.stop="$emit('toggle', $event)"
      >{{ launching ? (action === 'stop' ? '关闭中…' : '启动中…') : game.state === 'RUNNING' ? '停止' : '启动' }}</button>
      <button
        v-if="game.state === 'RUNNING' && !launching"
        class="mini-btn primary"
        @click.stop="$emit('open', $event)"
      >进入</button>
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
