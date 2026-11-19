<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<template>
  <router-view v-slot="{ Component, route }">
    <!-- 游玩页是全屏 fixed 画布，过渡期间祖先的 transform 会改变它的包含块，
         整页会缩进一个内边距里。直接跳过过渡。 -->
    <component :is="Component" v-if="route.name === 'play'" />
    <!-- 不加 mode="out-in"：旧页在 chunk 到达时已经没有信息价值，
         等它淡出 90 ms 再进新页是纯等待。旧页立刻移除，新页自己淡入。 -->
    <transition v-else name="page">
      <!-- key 用 path 而不是 fullPath：列表筛选只改 query，
           用 fullPath 会让整个列表页重新挂载、丢失滚动位置 -->
      <component :is="Component" :key="route.path" />
    </transition>
  </router-view>
  <RouteProgress />
  <ClosingBanner />
  <MaintenanceOverlay />
</template>

<script setup>
import { onMounted } from 'vue'
import ClosingBanner from './components/ClosingBanner.vue'
import MaintenanceOverlay from './components/MaintenanceOverlay.vue'
import RouteProgress from './components/RouteProgress.vue'
import { loadFeatureFlags } from './feature-flags'
import { prefetch } from './prefetch'

/**
 * 全局悬停预取委托。
 *
 * 挂在 document 上一次，而不是给每个 <router-link> 加 @pointerenter：
 * 站内链接散落在十几个视图里，逐个挂容易漏，也会在 v-for 里产生大量监听器。
 * passive 是因为这里绝不 preventDefault —— 只是搭车读一下 href。
 */
function onPointerOver(e) {
  // e.target 可能是文本节点等非元素，closest 不一定存在
  const a = e.target.closest?.('a[href]')
  if (!a) return
  // 站外链接与 new tab 不归路由管，预取它们的 href 只会解析成错误路径
  if (a.origin !== location.origin || a.target === '_blank') return
  prefetch(a.getAttribute('href'))
}

onMounted(loadFeatureFlags)
onMounted(() => {
  document.addEventListener('pointerover', onPointerOver, { passive: true })
})
</script>
