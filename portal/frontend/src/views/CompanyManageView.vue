<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
/**
 * 公司维护页。
 *
 * 公司身份 = 名称 + 网址：允许同名不同网址，列表里按组合区分。
 * 删除公司只解除与游戏的关联，不删除游戏。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { confirmAction, notify } from '../feedback'
import { idsOf, moveItem } from '../order'

const companies = ref([])
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

const total = computed(() => companies.value.length)
const dragIndex = ref(-1)

function onDragStart(i) {
  dragIndex.value = i
}

async function onDrop(i) {
  if (dragIndex.value < 0 || dragIndex.value === i) {
    dragIndex.value = -1
    return
  }
  const next = moveItem(companies.value, dragIndex.value, i)
  dragIndex.value = -1
  companies.value = next
  busy.value = true
  try {
    await api.reorderCompanies(idsOf(next))
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
    companies.value = await api.listCompanies()
  } catch (e) {
    notify.error('加载公司失败：' + e.message)
  } finally {
    loading.value = false
  }
}

async function create() {
  const name = newName.value.trim()
  if (!name) {
    notify.warning('公司名不能为空')
    return
  }
  busy.value = true
  try {
    await api.createCompany(name, newAlias.value.trim(), newWebsite.value.trim(),
      newDescription.value.trim())
    newName.value = ''
    newAlias.value = ''
    newWebsite.value = ''
    newDescription.value = ''
    await load()
    notify.success('公司已添加')
  } catch (e) {
    notify.error('添加失败：' + e.message)
  } finally {
    busy.value = false
  }
}

function startEdit(c) {
  editingId.value = c.id
  editName.value = c.name
  editAlias.value = c.alias || ''
  editWebsite.value = c.website || ''
  editDescription.value = c.description || ''
}

function cancelEdit() {
  editingId.value = null
}

async function saveEdit(id) {
  const name = editName.value.trim()
  if (!name) {
    notify.warning('公司名不能为空')
    return
  }
  busy.value = true
  try {
    await api.updateCompany(id, name, editAlias.value.trim(), editWebsite.value.trim(),
      editDescription.value.trim())
    editingId.value = null
    await load()
    notify.success('公司已更新')
  } catch (e) {
    notify.error('更新失败：' + e.message)
  } finally {
    busy.value = false
  }
}

async function remove(c) {
  const ok = await confirmAction(
    `删除公司「${c.name}${c.alias ? ' · ' + c.alias : ''}${c.website ? ' · ' + c.website : ''}」？\n\n只会解除它与游戏的关联，不会删除游戏。`,
    { title: '删除公司', confirmText: '删除' },
  )
  if (!ok) return
  busy.value = true
  try {
    await api.deleteCompany(c.id)
    await load()
    notify.success('公司已删除')
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
      <div class="brand" style="font-size:15px;letter-spacing:.1em">公司维护</div>
    </header>

    <section class="panel" style="margin-top:0">
      <div class="panel-head">
        <h2>新增公司</h2>
        <span class="muted-sm">名称必填；名称 + 别名 + 网址共同构成公司身份</span>
      </div>
      <div class="company-add">
        <input v-model="newName" class="input" placeholder="公司名称（必填）" maxlength="128" @keyup.enter="create">
        <input v-model="newAlias" class="input" placeholder="别名，可留空" maxlength="128" @keyup.enter="create">
        <input v-model="newWebsite" class="input" placeholder="网址，可留空" maxlength="500" @keyup.enter="create">
        <input v-model="newDescription" class="input" placeholder="简介，可留空" maxlength="1000" @keyup.enter="create">
        <button class="btn primary sm" :disabled="busy" @click="create">添加</button>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>公司列表</h2>
        <span class="muted-sm">共 {{ total }} 家 · 拖动行排序</span>
      </div>

      <div v-if="loading" class="loading">加载中…</div>
      <p v-else-if="!companies.length" class="muted-sm">还没有公司。</p>

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
            v-for="(c, i) in companies"
            :key="c.id"
            draggable="true"
            @dragstart="onDragStart(i)"
            @dragover.prevent
            @drop="onDrop(i)"
          >
            <td class="drag-handle" title="拖动排序">⣿</td>
            <template v-if="editingId === c.id">
              <td><input v-model="editName" class="input" maxlength="128"></td>
              <td><input v-model="editAlias" class="input" maxlength="128" placeholder="可留空"></td>
              <td><input v-model="editWebsite" class="input" maxlength="500" placeholder="可留空"></td>
              <td><input v-model="editDescription" class="input" maxlength="1000" placeholder="可留空"></td>
              <td class="mono">{{ c.count }}</td>
              <td style="text-align:right;white-space:nowrap">
                <button class="link-btn" :disabled="busy" @click="saveEdit(c.id)">保存</button>
                <button class="link-btn" :disabled="busy" @click="cancelEdit">取消</button>
              </td>
            </template>
            <template v-else>
              <td>{{ c.name }}</td>
              <td><span v-if="c.alias">{{ c.alias }}</span><span v-else class="muted-sm">—</span></td>
              <td>
                <a v-if="c.website" :href="c.website" target="_blank" rel="noopener" class="meta-link">
                  {{ c.website }}
                </a>
                <span v-else class="muted-sm">—</span>
              </td>
              <td class="company-desc" :title="c.description || undefined">
                <span v-if="c.description">{{ c.description }}</span>
                <span v-else class="muted-sm">—</span>
              </td>
              <td class="mono">{{ c.count }}</td>
              <td style="text-align:right;white-space:nowrap">
                <button class="link-btn" :disabled="busy" @click="startEdit(c)">编辑</button>
                <button class="link-btn danger" :disabled="busy" @click="remove(c)">删除</button>
              </td>
            </template>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>
