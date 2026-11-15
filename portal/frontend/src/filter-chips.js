/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 筛选栏 chip 列表：隐藏没有关联游戏的项，按关联游戏数倒序。
 *
 * 当前选中的项即使关联游戏数为 0 也保留，否则用户看不到自己的筛选状态。
 * 数量相同时保持原有顺序（Array.prototype.sort 稳定，依赖后端排序）。
 */
export function rankFilterItems(items, isActive = () => false) {
  return items
    .filter((item) => (item.count || 0) > 0 || isActive(item))
    .sort((a, b) => (b.count || 0) - (a.count || 0))
}
