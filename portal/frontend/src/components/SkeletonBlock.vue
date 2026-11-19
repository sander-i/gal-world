<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
/**
 * 通用骨架块。
 *
 * 只负责结构，样式全部在 styles.css（.sk / .sk-lines / .sk-cover …），
 * 组件内不写 <style>：主题令牌与 shimmer 动效统一由全局样式控制。
 *
 * 用法：
 *   <SkeletonBlock :width="180" :height="20" />            单个块
 *   <SkeletonBlock circle :width="36" :height="36" />      圆形头像位
 *   <SkeletonBlock :lines="3" :height="12" />              多行文本块（末行 60%）
 */
import { computed } from 'vue'

/**
 * 尺寸默认值交给 CSS（.sk 里有 height/border-radius），不在这里生成内联样式。
 *
 * 内联样式优先级高于类，一旦无条件写入 height/border-radius，
 * .sk-btn / .sk-cover 这类尺寸来自类名的用法就会被压成默认的 14px 小条。
 * 所以只有调用方显式传了才写内联。
 */
const props = defineProps({
  width: { type: [String, Number], default: undefined },
  height: { type: [String, Number], default: undefined },
  radius: { type: [String, Number], default: undefined },
  circle: { type: Boolean, default: false },
  lines: { type: Number, default: 0 },
})

/** 数字按 px 处理，字符串（'100%'、'2em'、'var(--x)' 等）原样透传 */
function px(v) {
  return typeof v === 'number' ? `${v}px` : v
}

const blockStyle = computed(() => {
  const s = {}
  if (props.width != null) s.width = px(props.width)
  if (props.height != null) s.height = px(props.height)
  if (props.circle) s.borderRadius = '999px'
  else if (props.radius != null) s.borderRadius = px(props.radius)
  return s
})

/** 多行块只控制行高，宽度由 .sk-lines .sk 与末行 60% 决定 */
const lineStyle = computed(() => (props.height != null ? { height: px(props.height) } : {}))
</script>

<template>
  <div v-if="lines > 0" class="sk-lines" aria-hidden="true">
    <span
      v-for="i in lines"
      :key="i"
      class="sk"
      :style="[lineStyle, { width: i === lines ? '60%' : '100%' }]"
    />
  </div>
  <span v-else class="sk" :class="{ circle }" :style="blockStyle" aria-hidden="true" />
</template>
