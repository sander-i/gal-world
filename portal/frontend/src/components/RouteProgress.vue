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
 *
 * route-progress.js 导出的是普通对象（为了能在 node --test 里直接导入），
 * 响应性在这里用 reactive() 补上。必须把代理注入回去：模块内的 begin/done
 * 直接写对象，若不经过 proxy 的 set trap，模板永远不会更新（进度条不出现）。
 * 反过来让模块自己 import vue 会破坏 node 可导入性，所以走绑定函数。
 */
import { reactive } from 'vue'
import { bindProgress, routeProgress } from '../route-progress'

const progress = reactive(routeProgress)
bindProgress(progress)
</script>

<template>
  <div class="route-progress" :class="{ on: progress.active }" aria-hidden="true">
    <i :style="{ width: progress.value + '%' }" />
  </div>
</template>
