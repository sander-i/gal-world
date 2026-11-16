<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
/**
 * 全局「正在关闭游戏」横幅。
 *
 * 关闭要等游戏自己存盘退出（10~15s），此时用户已经不在游玩页了，
 * 所以横幅挂在 App 上，任何页面都能看到进度。
 */
import { closing } from '../closing'
</script>

<template>
  <div v-if="closing.active || closing.done" class="closing-banner" role="status" aria-live="polite">
    <div class="closing-head">
      <span class="closing-text">
        {{ closing.done ? '游戏已关闭' : `正在关闭「${closing.name}」` }}
      </span>
      <span class="closing-percent">{{ closing.done ? '' : closing.percent + '%' }}</span>
    </div>
    <div class="closing-track">
      <span class="closing-fill" :class="{ done: closing.done }" :style="{ width: closing.percent + '%' }" />
    </div>
    <p v-if="!closing.done" class="closing-hint">等待游戏保存存档后退出，请不要关闭页面</p>
  </div>
</template>

<style scoped>
.closing-banner {
  position: fixed;
  right: 18px;
  bottom: 18px;
  z-index: 60;
  width: min(320px, calc(100vw - 36px));
  padding: 12px 14px;
  border: 1px solid var(--line-strong);
  border-radius: 8px;
  background: var(--surface);
  box-shadow: 0 8px 24px rgb(0 0 0 / 0.28);
}
.closing-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 13px;
}
.closing-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.closing-percent {
  color: var(--muted);
  font-size: 12.5px;
  flex: 0 0 auto;
}
.closing-track {
  height: 4px;
  border-radius: 999px;
  background: var(--surface-2);
  overflow: hidden;
}
.closing-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--accent);
  transition: width .3s ease-out;
}
.closing-fill.done {
  background: var(--muted);
}
.closing-hint {
  margin: 8px 0 0;
  font-size: 11.5px;
  color: var(--muted);
}
</style>
