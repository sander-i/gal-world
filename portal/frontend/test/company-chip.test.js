/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { companyChip } from '../src/company-chip.js'

test('公司 chip 保留别名，缺省字段归一为空串', () => {
  assert.deepEqual(
    companyChip({ id: 7, name: 'Key', alias: 'KEY', website: 'https://key.example.com' }),
    { id: 7, name: 'Key', alias: 'KEY', website: 'https://key.example.com' },
  )
  assert.deepEqual(
    companyChip({ id: null, name: 'Key' }),
    { id: null, name: 'Key', alias: '', website: '' },
  )
})
