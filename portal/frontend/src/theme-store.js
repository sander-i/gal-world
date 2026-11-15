/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { computed, ref } from 'vue'
import {
  BUILTIN_THEMES, THEME_STORAGE_KEY, applyTheme, parseStoredTheme, resolveTheme,
} from './theme.js'

const CUSTOM_KEY = 'galworld.themes.custom'
const stored = parseStoredTheme(localStorage.getItem(THEME_STORAGE_KEY))
const customThemes = ref(loadCustom())
const currentId = ref(stored?.id || 'obsidian')
const current = computed(() => resolveTheme(currentId.value, customThemes.value))
const allThemes = computed(() => [...BUILTIN_THEMES, ...customThemes.value])

function loadCustom() {
  try {
    const arr = JSON.parse(localStorage.getItem(CUSTOM_KEY) || '[]')
    return Array.isArray(arr) ? arr.filter((t) => t && t.id && t.vars) : []
  } catch {
    return []
  }
}

function persist() {
  localStorage.setItem(CUSTOM_KEY, JSON.stringify(customThemes.value))
  localStorage.setItem(THEME_STORAGE_KEY, JSON.stringify({
    id: current.value.id, name: current.value.name,
    base: current.value.base, vars: current.value.vars || {},
  }))
}

function setTheme(id) {
  currentId.value = id
  applyTheme(current.value)
  persist()
}

function saveCustom(theme) {
  const i = customThemes.value.findIndex((t) => t.id === theme.id)
  if (i >= 0) customThemes.value.splice(i, 1, theme)
  else customThemes.value.push(theme)
  setTheme(theme.id)
}

function removeCustom(id) {
  customThemes.value = customThemes.value.filter((t) => t.id !== id)
  if (currentId.value === id) setTheme('obsidian')
  else persist()
}

export function useTheme() {
  return { current, currentId, customThemes, allThemes, setTheme, saveCustom, removeCustom }
}
