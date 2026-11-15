<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<template>
  <router-view />
  <MaintenanceOverlay />
</template>

<script setup>
import { onMounted } from 'vue'
import MaintenanceOverlay from './components/MaintenanceOverlay.vue'
import { loadFeatureFlags } from './feature-flags'

onMounted(loadFeatureFlags)
</script>
