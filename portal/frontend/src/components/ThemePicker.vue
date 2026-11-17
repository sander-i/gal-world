<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
import { ref } from 'vue'
import { t } from '../i18n.js'
import { useTheme } from '../theme-store.js'
import { deriveVars, themeName } from '../theme.js'
import ThemeEditor from './ThemeEditor.vue'

const { allThemes, currentId, setTheme, saveCustom, removeCustom } = useTheme()
const editing = ref(null)

function duplicate(theme) {
  editing.value = {
    id: 'custom-' + Date.now().toString(36),
    name: themeName(theme) + t('（副本）'),
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
        v-for="th in allThemes"
        :key="th.id"
        class="theme-card"
        :class="{ active: currentId === th.id }"
        :data-theme-preview="th.id"
        @click="setTheme(th.id)"
      >
        <span class="theme-swatch" :style="{
          background: th.vars?.['--bg'] || '#0e1013',
          borderColor: th.vars?.['--line'] || '#262b33',
        }">
          <span class="theme-swatch-surface" :style="{
            background: th.vars?.['--surface'] || '#16191f',
            color: th.vars?.['--text'] || '#e9e6e0',
          }">Aa</span>
          <span class="theme-swatch-accent" :style="{
            background: th.vars?.['--accent'] || '#d8a05c',
          }" />
        </span>
        <span class="theme-name">{{ themeName(th) }}</span>
        <span class="theme-tag">{{ th.builtin ? t('内置') : t('自定义') }}</span>
      </button>
    </div>

    <div class="backup-bar" style="margin-top:12px">
      <button class="btn" @click="duplicate(currentId === 'obsidian'
        ? allThemes[0] : allThemes.find((x) => x.id === currentId))">基于当前主题新建</button>
      <button
        v-if="!allThemes.find((x) => x.id === currentId)?.builtin"
        class="btn danger"
        @click="removeCustom(currentId)"
      >删除当前自定义主题</button>
    </div>

    <ThemeEditor v-if="editing" :draft="editing" @cancel="editing = null" @save="onSave" />
  </div>
</template>
