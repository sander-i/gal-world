<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->
<script setup>
/**
 * 多语言字段编辑。
 *
 * 公司 / 标签 / 汉化组的名称、别名、简介可以按界面语言填不同文字，
 * 留空表示不覆盖，展示时回退到默认值。这里只负责编辑，
 * 值形如 {"en": {"name": "Yuzusoft", "alias": "yuzu-soft"}}。
 */
import { t } from '../i18n.js'
import { computed } from 'vue'

const props = defineProps({
  /** 当前覆盖表，形如 { en: { name: '...' } } */
  modelValue: { type: Object, default: () => ({}) },
  /** 可编辑的字段；每项 { key, label, placeholder } */
  fields: { type: Array, required: true },
  /** 已安装语言清单，来自 i18n 的 languages */
  languages: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:modelValue'])

/** 不列出站点默认语言：默认值就填在主字段里，重复一遍没有意义 */
const locales = computed(() => (props.languages || []).filter((l) => l && l.id))

function valueOf(locale, field) {
  const hit = props.modelValue?.[locale]
  return hit && typeof hit === 'object' ? (hit[field] || '') : ''
}

function setValue(locale, field, value) {
  const next = { ...(props.modelValue || {}) }
  const fields = { ...(next[locale] || {}) }
  if (value && value.trim()) {
    fields[field] = value
  } else {
    delete fields[field]
  }
  if (Object.keys(fields).length) {
    next[locale] = fields
  } else {
    delete next[locale]
  }
  emit('update:modelValue', next)
}

/** 某语言是否填了任意字段，用于折叠时给出提示 */
function hasAny(locale) {
  const hit = props.modelValue?.[locale]
  return !!hit && Object.keys(hit).length > 0
}
</script>

<template>
  <div class="i18n-fields">
    <details v-for="l in locales" :key="l.id" class="i18n-locale">
      <summary>
        <span class="i18n-locale-name">{{ l.nativeName || l.name || l.id }}</span>
        <code class="i18n-locale-id">{{ l.id }}</code>
        <span v-if="hasAny(l.id)" class="i18n-locale-dot" :title="t('已填写')">●</span>
      </summary>
      <div class="i18n-locale-body">
        <label v-for="f in fields" :key="f.key" class="i18n-field">
          <span class="i18n-field-label">{{ f.label }}</span>
          <input
            class="input"
            :value="valueOf(l.id, f.key)"
            :placeholder="f.placeholder || ''"
            @input="setValue(l.id, f.key, $event.target.value)"
          >
        </label>
      </div>
    </details>
    <p v-if="!locales.length" class="muted-sm">{{ t("没有可配置的语言") }}</p>
  </div>
</template>
