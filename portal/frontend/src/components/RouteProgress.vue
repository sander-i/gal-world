<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
/**
 * 顶部 2px 全局进度条。
 *
 * 只兜底路由导航的 chunk 下载那一段：预取命中时它根本不会出现（80 ms 内就结束），
 * 未命中时才用它填补「点击后旧页静止」的空白。
 *
 * 纯装饰，不承载信息，所以对辅助技术隐藏。样式在 styles.css 里，
 * 颜色全部走 var(--accent*)，不在这里写死。
 */
import { routeProgress } from '../route-progress'
</script>

<template>
  <div class="route-progress" :class="{ on: routeProgress.active }" aria-hidden="true">
    <i :style="{ width: routeProgress.value + '%' }" />
  </div>
</template>
