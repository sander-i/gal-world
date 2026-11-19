<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
import { ref } from 'vue'
import { t } from '../i18n.js'
import { useTheme } from '../theme-store.js'
import { deriveVars, themeName } from '../theme.js'
import { confirmAction, notify } from '../feedback.js'
import ThemeEditor from './ThemeEditor.vue'

const { allThemes, current, currentId, setTheme, saveCustom, removeCustom } = useTheme()
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

/**
 * 删除自定义主题。
 * 正在使用的主题不允许删除：先切到别的主题，否则删除后界面会在用户没预期的情况下换色。
 */
async function remove(th) {
  if (th.id === currentId.value) {
    notify.warning(t('正在使用的主题不能删除，请先切换到其它主题'))
    return
  }
  const ok = await confirmAction(t('删除主题「{0}」？此操作不可撤销。', themeName(th)), {
    title: t('删除主题'), confirmText: t('删除'),
  })
  if (ok) removeCustom(th.id)
}
</script>

<template>
  <div class="theme-picker">
    <div class="theme-grid">
      <div
        v-for="th in allThemes"
        :key="th.id"
        class="theme-card"
        :class="{ active: currentId === th.id }"
        :data-theme-preview="th.id"
      >
        <button class="theme-card-main" @click="setTheme(th.id)">
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
        <button
          v-if="!th.builtin"
          class="theme-card-del"
          :class="{ 'is-current': currentId === th.id }"
          :title="currentId === th.id
            ? t('正在使用的主题不能删除，请先切换到其它主题')
            : t('删除主题')"
          :aria-label="t('删除主题')"
          @click="remove(th)"
        >×</button>
      </div>
    </div>

    <div class="backup-bar" style="margin-top:12px">
      <button class="btn" @click="duplicate(current)">{{ t("基于当前主题新建") }}</button>
    </div>

    <ThemeEditor v-if="editing" :draft="editing" @cancel="editing = null" @save="onSave" />
  </div>
</template>
