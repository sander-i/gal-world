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
import { languages, onLocaleChange, t } from '../i18n.js'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'
import { confirmAction, notify } from '../feedback'
import { idsOf, moveItem } from '../order'
import I18nFields from '../components/I18nFields.vue'

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
/** 正在编辑的多语言覆盖：{ en: { name, alias, description } } */
const newI18n = ref({})
const editI18n = ref({})

/** 多语言字段定义：computed 而非常量，否则切换语言后字段名不更新 */
const orgFields = computed(() => [
  { key: 'name', label: t('名称') },
  { key: 'alias', label: t('别名') },
  { key: 'description', label: t('简介') },
])

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
    notify.error(t("保存排序失败：{0}", e.message))
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
    notify.error(t("加载公司失败：{0}", e.message))
  } finally {
    loading.value = false
  }
}

async function create() {
  const name = newName.value.trim()
  if (!name) {
    notify.warning(t('公司名不能为空'))
    return
  }
  busy.value = true
  try {
    await api.createCompany(name, newAlias.value.trim(), newWebsite.value.trim(),
      newDescription.value.trim(), newI18n.value)
    newName.value = ''
    newAlias.value = ''
    newWebsite.value = ''
    newDescription.value = ''
    newI18n.value = {}
    await load()
    notify.success(t('公司已添加'))
  } catch (e) {
    notify.error(t("添加失败：{0}", e.message))
  } finally {
    busy.value = false
  }
}

function startEdit(c) {
  editingId.value = c.id
  // 主字段编辑的是默认值，不是当前语言解析出来的展示值
  editName.value = c.baseName ?? c.name
  editAlias.value = c.baseAlias ?? (c.alias || '')
  editWebsite.value = c.website || ''
  editDescription.value = c.baseDescription ?? (c.description || '')
  editI18n.value = c.i18n ? JSON.parse(JSON.stringify(c.i18n)) : {}
}

function cancelEdit() {
  editingId.value = null
}

async function saveEdit(id) {
  const name = editName.value.trim()
  if (!name) {
    notify.warning(t('公司名不能为空'))
    return
  }
  busy.value = true
  try {
    await api.updateCompany(id, name, editAlias.value.trim(), editWebsite.value.trim(),
      editDescription.value.trim(), editI18n.value)
    editingId.value = null
    await load()
    notify.success(t('公司已更新'))
  } catch (e) {
    notify.error(t("更新失败：{0}", e.message))
  } finally {
    busy.value = false
  }
}

async function remove(c) {
  const ok = await confirmAction(
    t("删除公司「{0}{1}{2}」？\n\n只会解除它与游戏的关联，不会删除游戏。", c.name, c.alias ? ' · ' + c.alias : '', c.website ? ' · ' + c.website : ''),
    { title: t('删除公司'), confirmText: t('删除') },
  )
  if (!ok) return
  busy.value = true
  try {
    await api.deleteCompany(c.id)
    await load()
    notify.success(t('公司已删除'))
  } catch (e) {
    notify.error(t("删除失败：{0}", e.message))
  } finally {
    busy.value = false
  }
}

onMounted(load)

// 名称等字段按请求语言返回，切语言后要重取
const stopLocaleWatch = onLocaleChange(() => load())
onBeforeUnmount(stopLocaleWatch)
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <router-link to="/" class="ghost-btn">{{ t("← 游戏库") }}</router-link>
      <div class="spacer" />
      <div class="brand" style="font-size:15px;letter-spacing:.1em">{{ t("公司维护") }}</div>
    </header>

    <section class="panel" style="margin-top:0">
      <div class="panel-head">
        <h2>{{ t("新增公司") }}</h2>
        <span class="muted-sm">{{ t("名称必填；名称 + 别名 + 网址共同构成公司身份") }}</span>
      </div>
      <div class="company-add">
        <input v-model="newName" class="input" :placeholder="t('公司名称（必填）')" maxlength="128" @keyup.enter="create">
        <input v-model="newAlias" class="input" :placeholder="t('别名，可留空')" maxlength="128" @keyup.enter="create">
        <input v-model="newWebsite" class="input" :placeholder="t('网址，可留空')" maxlength="500" @keyup.enter="create">
        <input v-model="newDescription" class="input" :placeholder="t('简介，可留空')" maxlength="1000" @keyup.enter="create">
        <button class="btn primary sm" :disabled="busy" @click="create">{{ t("添加") }}</button>
      </div>
      <details class="i18n-locale" style="margin-top:12px">
        <summary>{{ t("多语言名称（可选）") }}</summary>
        <I18nFields v-model="newI18n" :fields="orgFields" :languages="languages" />
      </details>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>{{ t("公司列表") }}</h2>
        <span class="muted-sm">{{ t("共 {0} 家 · 拖动行排序", total) }}</span>
      </div>

      <div v-if="loading" class="loading">{{ t("加载中…") }}</div>
      <p v-else-if="!companies.length" class="muted-sm">{{ t("还没有公司。") }}</p>

      <table v-else class="company-table">
        <thead>
          <tr>
            <th style="width:32px"></th>
            <th>{{ t("名称") }}</th>
            <th>{{ t("别名") }}</th>
            <th>{{ t("网址") }}</th>
            <th>{{ t("简介") }}</th>
            <th>{{ t("游戏数") }}</th>
            <th style="text-align:right">{{ t("操作") }}</th>
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
            <td class="drag-handle" :title="t('拖动排序')">⣿</td>
            <template v-if="editingId === c.id">
              <td><input v-model="editName" class="input" maxlength="128"></td>
              <td><input v-model="editAlias" class="input" maxlength="128" :placeholder="t('可留空')"></td>
              <td><input v-model="editWebsite" class="input" maxlength="500" :placeholder="t('可留空')"></td>
              <td><input v-model="editDescription" class="input" maxlength="1000" :placeholder="t('可留空')"></td>
              <td class="mono">{{ c.count }}</td>
              <td style="text-align:right;white-space:nowrap">
                <button class="link-btn" :disabled="busy" @click="saveEdit(c.id)">{{ t("保存") }}</button>
                <button class="link-btn" :disabled="busy" @click="cancelEdit">{{ t("取消") }}</button>
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
                <button class="link-btn" :disabled="busy" @click="startEdit(c)">{{ t("编辑") }}</button>
                <button class="link-btn danger" :disabled="busy" @click="remove(c)">{{ t("删除") }}</button>
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
        <I18nFields v-model="editI18n" :fields="orgFields" :languages="languages" />
      </div>
    </section>
  </div>
</template>
