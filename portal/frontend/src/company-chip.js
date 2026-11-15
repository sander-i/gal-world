/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/** 公司 chip 的归一化：编辑页与去重共用，缺省字段统一为空串 */
export function companyChip(c) {
  return {
    id: c.id ?? null,
    name: c.name || '',
    alias: c.alias || '',
    website: c.website || '',
  }
}
