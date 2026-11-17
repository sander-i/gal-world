<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<template>
  <div v-if="visible" class="maintenance-mask" role="alert" aria-live="assertive">
    <div class="maintenance-card">
      <span class="maintenance-spinner" aria-hidden="true"></span>
      <h1>{{ t("正在更新维护中") }}</h1>
      <p>{{ t("门户正在部署新版本，恢复后页面会自动刷新，无需手动操作。") }}</p>
    </div>
  </div>
</template>

<script setup>
import { t } from '../i18n.js'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { onMaintenance, probeHealth } from '../api'

// 部署/更新期间后端会短暂不可用。requests 失败时 api.js 会广播维护事件，
// 这里接管成遮罩，并每 3 秒探测 /api/health，恢复后自动刷新页面。
const visible = ref(false)
let unsubscribe = () => {}
let timer = null

function startProbing() {
  clearInterval(timer)
  timer = setInterval(async () => {
    if (await probeHealth()) {
      clearInterval(timer)
      timer = null
      window.location.reload()
    }
  }, 3000)
}

onMounted(() => {
  unsubscribe = onMaintenance(() => {
    if (visible.value) return
    visible.value = true
    startProbing()
  })
})

onBeforeUnmount(() => {
  unsubscribe()
  clearInterval(timer)
})
</script>
