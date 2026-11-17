<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
/**
 * 公司 / 汉化组 / 标签通用多选下拉。
 *
 * 输入框按名称或别名过滤，点击选项切换选中；下拉只展示名称与别名。
 * 允许新建时，完全没有匹配项的下拉底部给出一个主操作：
 * 一次点击即创建并选中，不再有「先填名称再点新增」的两段式表单。
 *
 * 中文输入法：组合态（isComposing）期间的回车一律忽略，
 * 避免用回车确认拼音候选时被当成「选中第一项」或「新建」。
 */
import { t } from '../i18n.js'
import { computed, nextTick, ref } from 'vue'
import { filterOrgs, isSelected, resolveEnterAction, toggleOrg } from '../org-select'
import { moveItem } from '../order'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  options: { type: Array, default: () => [] },
  placeholder: { type: String, default: t('搜索名称或别名') },
  /** 允许在编辑页直接新建 */
  creatable: { type: Boolean, default: false },
  /** 新建实现：({ name, alias }) => Promise<条目|null>；实现方必须自行把条目并入 modelValue */
  createFn: { type: Function, default: null },
})
const emit = defineEmits(['update:modelValue', 'reorder'])
const dragIndex = ref(-1)

/** 已选项拖动排序：只在本组件内改顺序，是否落库由父组件决定 */
function onChipDrop(index) {
  if (dragIndex.value < 0 || dragIndex.value === index) {
    dragIndex.value = -1
    return
  }
  const next = moveItem(props.modelValue, dragIndex.value, index)
  dragIndex.value = -1
  emit('update:modelValue', next)
  emit('reorder', next)
}

const query = ref('')
const open = ref(false)
const composing = ref(false)
const creating = ref(false)
const searchEl = ref(null)
const filtered = computed(() => filterOrgs(props.options, query.value))
const enterAction = computed(() =>
  resolveEnterAction(filtered.value, query.value, props.creatable))
const showCreateRow = computed(() =>
  enterAction.value?.kind === 'create' && !!props.createFn)

function toggle(org) {
  emit('update:modelValue', toggleOrg(props.modelValue, org))
}

/** 组合态由 IME 自己消费回车；非组合态才处理选中或新建 */
function onSearchEnter(ev) {
  if (composing.value || ev.isComposing || ev.keyCode === 229) return
  ev.preventDefault()
  // Esc 关掉下拉后，回车只负责重新打开，不创建看不见的条目
  if (!open.value) {
    open.value = true
    return
  }
  const action = enterAction.value
  if (!action) return
  if (action.kind === 'select') {
    toggle(action.org)
    query.value = ''
    return
  }
  createFromQuery()
}

/** 只有主键点击才创建：右键、中键不应产生写操作 */
function onCreateMousedown(ev) {
  if (ev.button === 0) createFromQuery()
}

/** 一次点击：直接创建搜索词并选中；成功才清空搜索框 */
async function createFromQuery() {
  const action = enterAction.value
  if (creating.value || !action || action.kind !== 'create' || !props.createFn) return
  const submitted = action.name
  creating.value = true
  try {
    const created = await props.createFn({ name: submitted, alias: '' })
    // 失败时父组件已提示；保留搜索词，用户可直接重试
    if (created) {
      // 创建期间用户可能又输入了新内容，只清掉本次提交的那个词
      if (query.value.trim() === submitted) query.value = ''
      nextTick(() => searchEl.value?.focus())
    }
  } catch {
    // 父组件负责提示；这里吞掉异常，避免未处理的 Promise 拒绝
  } finally {
    creating.value = false
  }
}

function onFocusOut(ev) {
  if (!ev.currentTarget.contains(ev.relatedTarget)) open.value = false
}
</script>

<template>
  <div class="org-select" @focusout="onFocusOut">
    <div class="org-select-box">
      <span
        v-for="(o, i) in modelValue"
        :key="o.id ?? (o.name + '|' + (o.alias || ''))"
        class="org-chip"
        draggable="true"
        @dragstart.stop="dragIndex = i"
        @dragover.prevent
        @drop.stop="onChipDrop(i)"
      >
        <span class="org-chip-name">{{ o.name }}</span>
        <span v-if="o.alias" class="org-chip-alias">{{ o.alias }}</span>
        <button type="button" class="chip-x" :title="t('移除')" @click="toggle(o)">×</button>
      </span>
      <input
        ref="searchEl"
        v-model="query"
        class="org-select-input"
        :placeholder="modelValue.length ? '' : placeholder"
        @focus="open = true"
        @input="open = true"
        @compositionstart="composing = true"
        @compositionend="composing = false"
        @blur="composing = false"
        @keydown.enter="onSearchEnter"
        @keydown.esc="open = false"
      >
    </div>

    <div v-if="open" class="org-dropdown">
      <button
        v-for="o in filtered"
        :key="o.id ?? (o.name + '|' + (o.alias || ''))"
        type="button"
        class="org-option"
        :class="{ selected: isSelected(modelValue, o) }"
        @mousedown.prevent="toggle(o)"
      >
        <span class="org-option-name">{{ o.name }}</span>
        <span v-if="o.alias" class="org-option-alias">{{ o.alias }}</span>
        <span v-if="isSelected(modelValue, o)" class="org-option-check">✓</span>
      </button>
      <p v-if="!filtered.length && !showCreateRow" class="org-empty">{{ t("没有匹配项") }}</p>
      <button
        v-if="showCreateRow"
        type="button"
        class="org-option org-create-main"
        :disabled="creating"
        @mousedown.prevent="onCreateMousedown"
      >{{ creating ? t('新建中…') : t("＋ 新建「{0}」并选中", query.trim()) }}</button>
    </div>
  </div>
</template>
