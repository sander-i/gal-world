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
import { t } from '../i18n.js'
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { confirmAction, notify } from '../feedback'
import { idsOf, moveItem } from '../order'

const tags = ref([])
const loading = ref(true)
const busy = ref(false)
const dragIndex = ref(-1)
const newName = ref('')
const newAlias = ref('')
const editingId = ref(null)
const editName = ref('')
const editAlias = ref('')

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
    await api.createTag(name, newAlias.value.trim())
    newName.value = ''
    newAlias.value = ''
    await load()
    notify.success(t('标签已添加'))
  } catch (e) {
    notify.error(t("添加失败：{0}", e.message))
  } finally {
    busy.value = false
  }
}

function startEdit(t) {
  editingId.value = t.id
  editName.value = t.name
  editAlias.value = t.alias || ''
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
    await api.updateTag(id, name, editAlias.value.trim())
    editingId.value = null
    await load()
    notify.success(t('标签已更新'))
  } catch (e) {
    notify.error(t("更新失败：{0}", e.message))
  } finally {
    busy.value = false
  }
}

async function remove(t) {
  const ok = await confirmAction(
    t("删除标签「{0}」？\n\n会同时解除它与所有游戏的关联。", t.name),
    { title: t('删除标签'), confirmText: t('删除') },
  )
  if (!ok) return
  busy.value = true
  try {
    await api.deleteTag(t.id)
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
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>{{ t("标签列表") }}</h2>
        <span class="muted-sm">{{ t("共 {0} 个 · 拖动行排序", total) }}</span>
      </div>

      <div v-if="loading" class="loading">{{ t("加载中…") }}</div>
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
            v-for="(t, i) in tags"
            :key="t.id"
            draggable="true"
            @dragstart="onDragStart(i)"
            @dragover.prevent
            @drop="onDrop(i)"
          >
            <td class="drag-handle" :title="t('拖动排序')">⣿</td>
            <template v-if="editingId === t.id">
              <td><input v-model="editName" class="input" maxlength="64"></td>
              <td><input v-model="editAlias" class="input" maxlength="64" :placeholder="t('可留空')"></td>
              <td style="text-align:right;white-space:nowrap">
                <button class="link-btn" :disabled="busy" @click="saveEdit(t.id)">{{ t("保存") }}</button>
                <button class="link-btn" :disabled="busy" @click="cancelEdit">{{ t("取消") }}</button>
              </td>
            </template>
            <template v-else>
              <td>{{ t.name }}</td>
              <td><span v-if="t.alias">{{ t.alias }}</span><span v-else class="muted-sm">—</span></td>
              <td style="text-align:right;white-space:nowrap">
                <button class="link-btn" :disabled="busy" @click="startEdit(t)">{{ t("编辑") }}</button>
                <button class="link-btn danger" :disabled="busy" @click="remove(t)">{{ t("删除") }}</button>
              </td>
            </template>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>
