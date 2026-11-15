<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
import { reactive, computed } from 'vue'
import { deriveVars } from '../theme.js'

const props = defineProps({ draft: { type: Object, required: true } })
const emit = defineEmits(['save', 'cancel'])

const form = reactive({ name: props.draft.name, base: props.draft.base, seed: { ...props.draft.seed } })
const FIELDS = [
  ['bg', '背景'], ['surface', '表面'], ['line', '边框'], ['text', '正文'],
  ['muted', '次要文字'], ['accent', '强调色'], ['ok', '成功'], ['warn', '警告'], ['err', '错误'],
]
const preview = computed(() => deriveVars({ ...form.seed }))

function save() {
  emit('save', { id: props.draft.id, name: form.name.trim() || '未命名主题', base: form.base, seed: { ...form.seed } })
}
</script>

<template>
  <div class="theme-editor">
    <div class="section-title">编辑主题</div>
    <div class="setting-row">
      <span>名称</span>
      <input v-model="form.name" class="input" maxlength="20">
    </div>
    <div class="setting-row">
      <span>明暗</span>
      <div class="seg">
        <button :class="{ on: form.base === 'dark' }" @click="form.base = 'dark'">深色</button>
        <button :class="{ on: form.base === 'light' }" @click="form.base = 'light'">浅色</button>
      </div>
    </div>
    <div class="theme-fields">
      <label v-for="[k, label] in FIELDS" :key="k" class="theme-field">
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
        <b>示例游戏</b>
        <span>1280×720 · 已停止</span>
        <button>启动</button>
      </div>
    </div>
    <div class="backup-bar">
      <button class="btn primary sm" @click="save">保存并使用</button>
      <button class="btn" @click="$emit('cancel')">取消</button>
    </div>
  </div>
</template>
