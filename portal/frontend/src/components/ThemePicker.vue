<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
import { ref } from 'vue'
import { useTheme } from '../theme-store.js'
import { deriveVars } from '../theme.js'
import ThemeEditor from './ThemeEditor.vue'

const { allThemes, currentId, setTheme, saveCustom, removeCustom } = useTheme()
const editing = ref(null)

function duplicate(theme) {
  editing.value = {
    id: 'custom-' + Date.now().toString(36),
    name: theme.name + '（副本）',
    base: theme.base,
    seed: seedOf(theme),
  }
}

/** 反推 9 个种子色：内置主题直接取对应变量，派生值忽略 */
function seedOf(theme) {
  const v = theme.vars || {}
  const fallback = {
    bg: '#0e1013', surface: '#16191f', line: '#262b33', text: '#e9e6e0',
    muted: '#8a9099', accent: '#d8a05c', ok: '#6fbf8b', warn: '#d98a5c', err: '#d96c6c',
  }
  return {
    bg: v['--bg'] || fallback.bg,
    surface: v['--surface'] || fallback.surface,
    line: v['--line'] || fallback.line,
    text: v['--text'] || fallback.text,
    muted: v['--muted'] || fallback.muted,
    accent: v['--accent'] || fallback.accent,
    ok: v['--ok'] || fallback.ok,
    warn: v['--warn'] || fallback.warn,
    err: v['--err'] || fallback.err,
  }
}

function onSave(theme) {
  saveCustom({ ...theme, vars: deriveVars({ ...theme.seed, base: theme.base }) })
  editing.value = null
}
</script>

<template>
  <div class="theme-picker">
    <div class="theme-grid">
      <button
        v-for="t in allThemes"
        :key="t.id"
        class="theme-card"
        :class="{ active: currentId === t.id }"
        :data-theme-preview="t.id"
        @click="setTheme(t.id)"
      >
        <span class="theme-swatch" :style="{
          background: t.vars?.['--bg'] || '#0e1013',
          borderColor: t.vars?.['--line'] || '#262b33',
        }">
          <span class="theme-swatch-surface" :style="{
            background: t.vars?.['--surface'] || '#16191f',
            color: t.vars?.['--text'] || '#e9e6e0',
          }">Aa</span>
          <span class="theme-swatch-accent" :style="{
            background: t.vars?.['--accent'] || '#d8a05c',
          }" />
        </span>
        <span class="theme-name">{{ t.name }}</span>
        <span class="theme-tag">{{ t.builtin ? '内置' : '自定义' }}</span>
      </button>
    </div>

    <div class="backup-bar" style="margin-top:12px">
      <button class="btn" @click="duplicate(currentId === 'obsidian'
        ? allThemes[0] : allThemes.find((t) => t.id === currentId))">基于当前主题新建</button>
      <button
        v-if="!allThemes.find((t) => t.id === currentId)?.builtin"
        class="btn danger"
        @click="removeCustom(currentId)"
      >删除当前自定义主题</button>
    </div>

    <ThemeEditor v-if="editing" :draft="editing" @cancel="editing = null" @save="onSave" />
  </div>
</template>
