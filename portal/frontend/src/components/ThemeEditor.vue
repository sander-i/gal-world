<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
import { t } from '../i18n.js'
import { reactive, computed } from 'vue'
import { deriveVars } from '../theme.js'

const props = defineProps({ draft: { type: Object, required: true } })
const emit = defineEmits(['save', 'cancel'])

const form = reactive({ name: props.draft.name, base: props.draft.base, seed: { ...props.draft.seed } })
/** 颜色字段：computed 而非常量，否则语言切换后不更新 */
const fields = computed(() => [
  ['bg', t('背景')], ['surface', t('表面')], ['line', t('边框')], ['text', t('正文')],
  ['muted', t('次要文字')], ['accent', t('强调色')], ['ok', t('成功')], ['warn', t('警告')], ['err', t('错误')],
])
const preview = computed(() => deriveVars({ ...form.seed }))

function save() {
  emit('save', { id: props.draft.id, name: form.name.trim() || t('未命名主题'), base: form.base, seed: { ...form.seed } })
}
</script>

<template>
  <div class="theme-editor">
    <div class="section-title">{{ t("编辑主题") }}</div>
    <div class="setting-row">
      <span>{{ t("名称") }}</span>
      <input v-model="form.name" class="input" maxlength="20">
    </div>
    <div class="setting-row">
      <span>{{ t("明暗") }}</span>
      <div class="seg">
        <button :class="{ on: form.base === 'dark' }" @click="form.base = 'dark'">{{ t("深色") }}</button>
        <button :class="{ on: form.base === 'light' }" @click="form.base = 'light'">{{ t("浅色") }}</button>
      </div>
    </div>
    <div class="theme-fields">
      <label v-for="[k, label] in fields" :key="k" class="theme-field">
        <span>{{ label }}</span>
        <input v-model="form.seed[k]" type="color">
        <code>{{ form.seed[k] }}</code>
      </label>
    </div>
    <div class="theme-preview" :style="{
      '--p-bg': preview['--bg'], '--p-surface': preview['--surface'],
      '--p-line': preview['--line'], '--p-text': preview['--text'],
      '--p-muted': preview['--muted'], '--p-accent': preview['--accent'],
    }">
      <div class="theme-preview-card">
        <b>{{ t("示例游戏") }}</b>
        <span>{{ t("1280×720 · 已停止") }}</span>
        <button>{{ t("启动") }}</button>
      </div>
    </div>
    <div class="backup-bar">
      <button class="btn primary sm" @click="save">{{ t("保存并使用") }}</button>
      <button class="btn" @click="$emit('cancel')">{{ t("取消") }}</button>
    </div>
  </div>
</template>
