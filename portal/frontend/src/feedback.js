/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { ElMessage, ElMessageBox } from 'element-plus'

/**
 * 统一的通知 / 确认框入口。
 *
 * 全站不用浏览器原生 alert/confirm：原生弹窗样式与深色主题割裂，
 * 且 confirm 在部分浏览器里还会被拦截。这里统一走 Element Plus，
 * 成功通知固定 3 秒自动关闭。
 */
export const notify = {
  success(message, duration = 3000) {
    ElMessage({ message, type: 'success', duration, showClose: true })
  },
  error(message, duration = 4500) {
    ElMessage({ message, type: 'error', duration, showClose: true })
  },
  warning(message, duration = 3500) {
    ElMessage({ message, type: 'warning', duration, showClose: true })
  },
  info(message, duration = 3000) {
    ElMessage({ message, type: 'info', duration, showClose: true })
  },
}

/**
 * 危险操作确认框。返回 true 表示用户确认。
 * 取消（点取消 / 关闭 / Esc）不算异常，统一返回 false。
 */
export async function confirmAction(message, {
  title = '确认操作',
  confirmText = '确定',
  cancelText = '取消',
} = {}) {
  try {
    await ElMessageBox.confirm(message, title, {
      type: 'warning',
      confirmButtonText: confirmText,
      cancelButtonText: cancelText,
      closeOnClickModal: false,
      distinguishCancelAndClose: true,
    })
    return true
  } catch {
    return false
  }
}
