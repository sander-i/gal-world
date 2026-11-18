<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
/**
 * 存档目录选择器：浏览游戏主目录下的文件夹，勾选若干作为存档目录。
 * 只负责浏览与勾选，保存动作由父组件调 api.setSaveDirs。
 */
import { t } from '../i18n.js'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../feedback'

const props = defineProps({
  visible: { type: Boolean, default: false },
  gameId: { type: String, required: true },
  /** 当前生效的存档目录（相对游戏目录），用于打开时回显勾选状态 */
  currentDirs: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:visible', 'saved'])

const path = ref('')
const entries = ref([])
const loading = ref(false)
const error = ref('')
const saving = ref(false)
const selected = ref([])

const crumbs = computed(() => path.value ? path.value.split('/').filter(Boolean) : [])

watch(() => props.visible, async (v) => {
  // 弹窗打开时锁住页面滚动，避免遮罩下层的游戏页跟着滚
  document.body.style.overflow = v ? 'hidden' : ''
  if (!v) return
  path.value = ''
  selected.value = [...props.currentDirs]
  await load('')
})

onBeforeUnmount(() => {
  document.body.style.overflow = ''
})

async function load(next) {
  loading.value = true
  error.value = ''
  try {
    const res = await api.listSaveFiles(props.gameId, next)
    entries.value = res.entries || []
    path.value = res.path || ''
  } catch (e) {
    error.value = e.message
    entries.value = []
  } finally {
    loading.value = false
  }
}

function go(next) {
  load(next)
}

function childPath(name) {
  return path.value ? `${path.value}/${name}` : name
}

function toggle(p) {
  const i = selected.value.indexOf(p)
  if (i >= 0) selected.value.splice(i, 1)
  else selected.value.push(p)
}

async function save() {
  saving.value = true
  try {
    await api.setSaveDirs(props.gameId, selected.value)
    notify.success(t("已设置 {0} 个存档目录", selected.value.length))
    emit('saved', [...selected.value])
    close()
  } catch (e) {
    notify.error(t("保存失败：{0}", e.message))
  } finally {
    saving.value = false
  }
}

function close() {
  emit('update:visible', false)
}

function fmtSize(n) {
  if (!n) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let v = n
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(v >= 100 || i === 0 ? 0 : 1)} ${units[i]}`
}
</script>

<template>
  <div v-if="visible" class="sdp-mask" role="dialog" aria-modal="true" :aria-label="t('选择存档目录')">
    <div class="sdp-panel">
      <div class="sdp-head">
        <h2>{{ t("手动选择存档目录") }}</h2>
        <button class="link-btn" @click="close">{{ t("关闭") }}</button>
      </div>
      <p class="muted-sm">
        {{ t("勾选存放存档的") }}<strong>{{ t("文件夹或文件") }}</strong>{{ t("，可多选、可进入子目录。 存档有「文件夹」和「单个文件」两种形态，都支持。 不勾选任何项时保存会回到默认约定（savedata_cn / savedata_jp / savedata / save）。") }}</p>
      <div class="sdp-crumbs">
        <a @click="go('')">{{ t("根目录") }}</a>
        <template v-for="(c, i) in crumbs" :key="i">
          <span class="sdp-sep">/</span>
          <a @click="go(crumbs.slice(0, i + 1).join('/'))">{{ c }}</a>
        </template>
      </div>
      <!-- 当前所在目录本身也可选：存档目录常是深层目录（如 SteamEmu/Saves），
           没有这条就只能勾它的子项 -->
      <label v-if="path" class="sdp-current">
        <input
          type="checkbox"
          :checked="selected.includes(path)"
          @change="toggle(path)"
        />
        <span>{{ t("选中的当前目录：") }}<code>{{ path }}</code></span>
      </label>
      <div class="sdp-list">
        <div v-if="loading" class="muted-sm">{{ t("加载中…") }}</div>
        <div v-else-if="error" class="muted-sm" style="color:var(--warn)">{{ error }}</div>
        <div v-else-if="!entries.length" class="muted-sm">{{ t("这个目录下没有内容") }}</div>
        <template v-else>
          <div v-for="e in entries" :key="e.name" class="sdp-row">
            <!-- 目录与文件都可选：存档有「文件夹」和「单个文件」两种形态 -->
            <input
              type="checkbox"
              :checked="selected.includes(childPath(e.name))"
              @change="toggle(childPath(e.name))"
            />
            <span v-if="e.dir" class="sdp-enter" :title="t('进入该目录')" @click="go(childPath(e.name))">▸</span>
            <span v-else class="sdp-enter-placeholder" />
            <span class="sdp-name">{{ e.name }}</span>
            <span v-if="!e.dir" class="muted-sm sdp-size">{{ fmtSize(e.size) }}</span>
          </div>
        </template>
      </div>
      <div class="sdp-actions">
        <button class="btn" @click="close">{{ t("取消") }}</button>
        <button class="btn primary sm" :disabled="saving || !selected.length" @click="save">
          {{ saving ? t('保存中…') : t("保存（{0}）", selected.length) }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sdp-mask {
  position: fixed;
  inset: 0;
  background: var(--overlay);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
  padding: 24px;
}
.sdp-panel {
  background: var(--surface);
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  width: min(640px, 100%);
  max-height: 82vh;
  display: flex;
  flex-direction: column;
  padding: 18px 20px 16px;
}
.sdp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.sdp-head h2 {
  font-size: 16px;
  margin: 0;
}
.sdp-crumbs {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  font-size: 12.5px;
  margin: 10px 0;
  padding: 6px 10px;
  background: var(--surface-2);
  border-radius: 6px;
}
.sdp-crumbs a {
  cursor: pointer;
  color: var(--accent);
}
.sdp-sep {
  color: var(--muted);
  margin: 0 2px;
}
.sdp-current {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px;
  padding: 6px 10px;
  font-size: 12.5px;
  background: var(--accent-faint);
  border: 1px solid var(--line);
  border-radius: 6px;
  cursor: pointer;
}
.sdp-current code {
  font-size: 12px;
}
.sdp-list {
  border: 1px solid var(--line-strong);
  border-radius: 6px;
  max-height: 340px;
  overflow: auto;
}
.sdp-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--surface-2);
}
.sdp-row:last-child {
  border-bottom: none;
}
.sdp-enter-placeholder {
  width: 14px;
  display: inline-block;
}
.sdp-enter {
  cursor: pointer;
  color: var(--muted);
  width: 14px;
  display: inline-block;
}
.sdp-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sdp-size {
  font-size: 12px;
}
.sdp-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
}
</style>
