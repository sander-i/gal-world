<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
/**
 * 汉化组维护页。
 *
 * 汉化组身份 = 名称 + 别名 + 网址：允许同名不同别名/网址，列表里按组合区分。
 * 删除汉化组只解除与游戏的关联，不删除游戏。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { confirmAction, notify } from '../feedback'
import { idsOf, moveItem } from '../order'

const groups = ref([])
const loading = ref(true)
const busy = ref(false)
const newName = ref('')
const newAlias = ref('')
const newWebsite = ref('')
const newDescription = ref('')
const editingId = ref(null)
const editName = ref('')
const editAlias = ref('')
const editWebsite = ref('')
const editDescription = ref('')

const total = computed(() => groups.value.length)
const dragIndex = ref(-1)

function onDragStart(i) {
  dragIndex.value = i
}

async function onDrop(i) {
  if (dragIndex.value < 0 || dragIndex.value === i) {
    dragIndex.value = -1
    return
  }
  const next = moveItem(groups.value, dragIndex.value, i)
  dragIndex.value = -1
  groups.value = next
  busy.value = true
  try {
    await api.reorderLocalizationGroups(idsOf(next))
  } catch (e) {
    notify.error('保存排序失败：' + e.message)
    await load()
  } finally {
    busy.value = false
  }
}

async function load() {
  loading.value = true
  try {
    groups.value = await api.listLocalizationGroups()
  } catch (e) {
    notify.error('加载汉化组失败：' + e.message)
  } finally {
    loading.value = false
  }
}

async function create() {
  const name = newName.value.trim()
  if (!name) {
    notify.warning('汉化组名不能为空')
    return
  }
  busy.value = true
  try {
    await api.createLocalizationGroup(name, newAlias.value.trim(), newWebsite.value.trim(),
      newDescription.value.trim())
    newName.value = ''
    newAlias.value = ''
    newWebsite.value = ''
    newDescription.value = ''
    await load()
    notify.success('汉化组已添加')
  } catch (e) {
    notify.error('添加失败：' + e.message)
  } finally {
    busy.value = false
  }
}

function startEdit(g) {
  editingId.value = g.id
  editName.value = g.name
  editAlias.value = g.alias || ''
  editWebsite.value = g.website || ''
  editDescription.value = g.description || ''
}

function cancelEdit() {
  editingId.value = null
}

async function saveEdit(id) {
  const name = editName.value.trim()
  if (!name) {
    notify.warning('汉化组名不能为空')
    return
  }
  busy.value = true
  try {
    await api.updateLocalizationGroup(id, name, editAlias.value.trim(),
      editWebsite.value.trim(), editDescription.value.trim())
    editingId.value = null
    await load()
    notify.success('汉化组已更新')
  } catch (e) {
    notify.error('更新失败：' + e.message)
  } finally {
    busy.value = false
  }
}

async function remove(g) {
  const ok = await confirmAction(
    `删除汉化组「${g.name}${g.alias ? ' · ' + g.alias : ''}${g.website ? ' · ' + g.website : ''}」？\n\n只会解除它与游戏的关联，不会删除游戏。`,
    { title: '删除汉化组', confirmText: '删除' },
  )
  if (!ok) return
  busy.value = true
  try {
    await api.deleteLocalizationGroup(g.id)
    await load()
    notify.success('汉化组已删除')
  } catch (e) {
    notify.error('删除失败：' + e.message)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <router-link to="/" class="ghost-btn">← 游戏库</router-link>
      <div class="spacer" />
      <div class="brand" style="font-size:15px;letter-spacing:.1em">汉化组维护</div>
    </header>

    <section class="panel" style="margin-top:0">
      <div class="panel-head">
        <h2>新增汉化组</h2>
        <span class="muted-sm">名称必填；名称 + 别名 + 网址共同构成汉化组身份</span>
      </div>
      <div class="company-add">
        <input v-model="newName" class="input" placeholder="汉化组名称（必填）" maxlength="128" @keyup.enter="create">
        <input v-model="newAlias" class="input" placeholder="别名，可留空" maxlength="128" @keyup.enter="create">
        <input v-model="newWebsite" class="input" placeholder="网址，可留空" maxlength="500" @keyup.enter="create">
        <input v-model="newDescription" class="input" placeholder="简介，可留空" maxlength="1000" @keyup.enter="create">
        <button class="btn primary sm" :disabled="busy" @click="create">添加</button>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>汉化组列表</h2>
        <span class="muted-sm">共 {{ total }} 家 · 拖动行排序</span>
      </div>

      <div v-if="loading" class="loading">加载中…</div>
      <p v-else-if="!groups.length" class="muted-sm">还没有汉化组。</p>

      <table v-else class="company-table">
        <thead>
          <tr>
            <th style="width:32px"></th>
            <th>名称</th>
            <th>别名</th>
            <th>网址</th>
            <th>简介</th>
            <th>游戏数</th>
            <th style="text-align:right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(g, i) in groups"
            :key="g.id"
            draggable="true"
            @dragstart="onDragStart(i)"
            @dragover.prevent
            @drop="onDrop(i)"
          >
            <td class="drag-handle" title="拖动排序">⣿</td>
            <template v-if="editingId === g.id">
              <td><input v-model="editName" class="input" maxlength="128"></td>
              <td><input v-model="editAlias" class="input" maxlength="128" placeholder="可留空"></td>
              <td><input v-model="editWebsite" class="input" maxlength="500" placeholder="可留空"></td>
              <td><input v-model="editDescription" class="input" maxlength="1000" placeholder="可留空"></td>
              <td class="mono">{{ g.count }}</td>
              <td style="text-align:right;white-space:nowrap">
                <button class="link-btn" :disabled="busy" @click="saveEdit(g.id)">保存</button>
                <button class="link-btn" :disabled="busy" @click="cancelEdit">取消</button>
              </td>
            </template>
            <template v-else>
              <td>{{ g.name }}</td>
              <td><span v-if="g.alias">{{ g.alias }}</span><span v-else class="muted-sm">—</span></td>
              <td>
                <a v-if="g.website" :href="g.website" target="_blank" rel="noopener" class="meta-link">
                  {{ g.website }}
                </a>
                <span v-else class="muted-sm">—</span>
              </td>
              <td class="company-desc" :title="g.description || undefined">
                <span v-if="g.description">{{ g.description }}</span>
                <span v-else class="muted-sm">—</span>
              </td>
              <td class="mono">{{ g.count }}</td>
              <td style="text-align:right;white-space:nowrap">
                <button class="link-btn" :disabled="busy" @click="startEdit(g)">编辑</button>
                <button class="link-btn danger" :disabled="busy" @click="remove(g)">删除</button>
              </td>
            </template>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>
