<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
/**
 * 标签维护页。
 *
 * 标签身份 = 名称（唯一），别名为可选展示/搜索字段；拖动行调整全局展示顺序。
 */
import { languages, onLocaleChange, t } from '../i18n.js'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'
import { confirmAction, notify } from '../feedback'
import { idsOf, moveItem } from '../order'
import I18nFields from '../components/I18nFields.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'

const tags = ref([])
const loading = ref(true)
const busy = ref(false)
const dragIndex = ref(-1)
const newName = ref('')
const newAlias = ref('')
const editingId = ref(null)
const editName = ref('')
const editAlias = ref('')
/** 正在编辑的多语言覆盖：{ en: { name, alias } } */
const newI18n = ref({})
const editI18n = ref({})

/** 标签只有名称与别名两个可翻译字段 */
const tagFields = computed(() => [
  { key: 'name', label: t('名称') },
  { key: 'alias', label: t('别名') },
])

const total = computed(() => tags.value.length)

async function load() {
  loading.value = true
  try {
    tags.value = await api.listTags()
  } catch (e) {
    notify.error(t("加载标签失败：{0}", e.message))
  } finally {
    loading.value = false
  }
}

async function create() {
  const name = newName.value.trim()
  if (!name) {
    notify.warning(t('标签名不能为空'))
    return
  }
  busy.value = true
  try {
    await api.createTag(name, newAlias.value.trim(), newI18n.value)
    newName.value = ''
    newAlias.value = ''
    newI18n.value = {}
    await load()
    notify.success(t('标签已添加'))
  } catch (e) {
    notify.error(t("添加失败：{0}", e.message))
  } finally {
    busy.value = false
  }
}

function startEdit(tag) {
  editingId.value = tag.id
  // 主字段编辑的是默认值，不是当前语言解析出来的展示值
  editName.value = tag.baseName ?? tag.name
  editAlias.value = tag.baseAlias ?? (tag.alias || '')
  editI18n.value = tag.i18n ? JSON.parse(JSON.stringify(tag.i18n)) : {}
}

function cancelEdit() {
  editingId.value = null
}

async function saveEdit(id) {
  const name = editName.value.trim()
  if (!name) {
    notify.warning(t('标签名不能为空'))
    return
  }
  busy.value = true
  try {
    await api.updateTag(id, name, editAlias.value.trim(), editI18n.value)
    editingId.value = null
    await load()
    notify.success(t('标签已更新'))
  } catch (e) {
    notify.error(t("更新失败：{0}", e.message))
  } finally {
    busy.value = false
  }
}

async function remove(tag) {
  const ok = await confirmAction(
    t("删除标签「{0}」？\n\n会同时解除它与所有游戏的关联。", tag.name),
    { title: t('删除标签'), confirmText: t('删除') },
  )
  if (!ok) return
  busy.value = true
  try {
    await api.deleteTag(tag.id)
    await load()
    notify.success(t('标签已删除'))
  } catch (e) {
    notify.error(t("删除失败：{0}", e.message))
  } finally {
    busy.value = false
  }
}

function onDragStart(i) {
  dragIndex.value = i
}

async function onDrop(i) {
  if (dragIndex.value < 0 || dragIndex.value === i) {
    dragIndex.value = -1
    return
  }
  const next = moveItem(tags.value, dragIndex.value, i)
  dragIndex.value = -1
  tags.value = next
  busy.value = true
  try {
    await api.reorderTags(idsOf(next))
  } catch (e) {
    notify.error(t("保存排序失败：{0}", e.message))
    await load()
  } finally {
    busy.value = false
  }
}

onMounted(load)

// 名称按请求语言返回，切语言后要重取
const stopLocaleWatch = onLocaleChange(() => load())
onBeforeUnmount(stopLocaleWatch)
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <router-link to="/" class="ghost-btn">{{ t("← 游戏库") }}</router-link>
      <div class="spacer" />
      <div class="brand" style="font-size:15px;letter-spacing:.1em">{{ t("标签维护") }}</div>
    </header>

    <section class="panel" style="margin-top:0">
      <div class="panel-head">
        <h2>{{ t("新增标签") }}</h2>
        <span class="muted-sm">{{ t("名称必填，别名可选；拖动行可调整全站展示顺序") }}</span>
      </div>
      <div class="company-add">
        <input v-model="newName" class="input" :placeholder="t('标签名称（必填）')" maxlength="64" @keyup.enter="create">
        <input v-model="newAlias" class="input" :placeholder="t('别名，可留空')" maxlength="64" @keyup.enter="create">
        <button class="btn primary sm" :disabled="busy" @click="create">{{ t("添加") }}</button>
      </div>
      <details class="i18n-locale" style="margin-top:12px">
        <summary>{{ t("多语言名称（可选）") }}</summary>
        <I18nFields v-model="newI18n" :fields="tagFields" :languages="languages" />
      </details>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>{{ t("标签列表") }}</h2>
        <span class="muted-sm">{{ t("共 {0} 个 · 拖动行排序", total) }}</span>
      </div>

      <!-- 加载态：沿用真实表格的行高与列结构，数据到达时不产生高度跳变 -->
      <table v-if="loading" class="company-table" aria-busy="true">
        <thead>
          <tr>
            <th style="width:32px"></th>
            <th>{{ t("名称") }}</th>
            <th>{{ t("别名") }}</th>
            <th style="text-align:right">{{ t("操作") }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="i in 6" :key="i">
            <td><SkeletonBlock :width="12" :height="14" /></td>
            <td><SkeletonBlock :width="120" :height="14" /></td>
            <td><SkeletonBlock :width="'40%'" :height="14" /></td>
            <td><div class="sk-actions"><SkeletonBlock :width="60" :height="14" /></div></td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="!tags.length" class="muted-sm">{{ t("还没有标签。") }}</p>

      <table v-else class="company-table">
        <thead>
          <tr>
            <th style="width:32px"></th>
            <th>{{ t("名称") }}</th>
            <th>{{ t("别名") }}</th>
            <th style="text-align:right">{{ t("操作") }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(tag, i) in tags"
            :key="tag.id"
            draggable="true"
            @dragstart="onDragStart(i)"
            @dragover.prevent
            @drop="onDrop(i)"
          >
            <td class="drag-handle" :title="t('拖动排序')">⣿</td>
            <template v-if="editingId === tag.id">
              <td><input v-model="editName" class="input" maxlength="64"></td>
              <td><input v-model="editAlias" class="input" maxlength="64" :placeholder="t('可留空')"></td>
              <td style="text-align:right;white-space:nowrap">
                <button class="link-btn" :disabled="busy" @click="saveEdit(tag.id)">{{ t("保存") }}</button>
                <button class="link-btn" :disabled="busy" @click="cancelEdit">{{ t("取消") }}</button>
              </td>
            </template>
            <template v-else>
              <td>{{ tag.name }}</td>
              <td><span v-if="tag.alias">{{ tag.alias }}</span><span v-else class="muted-sm">—</span></td>
              <td style="text-align:right;white-space:nowrap">
                <button class="link-btn" :disabled="busy" @click="startEdit(tag)">{{ t("编辑") }}</button>
                <button class="link-btn danger" :disabled="busy" @click="remove(tag)">{{ t("删除") }}</button>
              </td>
            </template>
          </tr>
        </tbody>
      </table>

      <!-- 多语言编辑：表格行里放不下多组输入，编辑时在下方单开一块 -->
      <div v-if="editingId != null" class="i18n-edit-panel">
        <div class="panel-head" style="margin-bottom:8px">
          <h3 style="font-size:13px;margin:0">{{ t("多语言名称") }}</h3>
          <span class="muted-sm">{{ t("留空表示沿用默认值") }}</span>
        </div>
        <I18nFields v-model="editI18n" :fields="tagFields" :languages="languages" />
      </div>
    </section>
  </div>
</template>

<style scoped>
/* 加载态表格：操作列右对齐，与真实行一致 */
.sk-actions { display: flex; justify-content: flex-end; }
</style>
