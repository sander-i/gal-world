/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { runSteps } from './step-sequence.js'

/** 一键卸载的执行顺序：只挑卸载步骤，已清理（READY）的跳过。 */
export function uninstallSequence(steps) {
  return (Array.isArray(steps) ? steps : [])
    .filter((s) => s && s.uninstall === true && s.state !== 'READY')
    .map((s) => s.id)
}

/**
 * 顺序执行卸载步骤。
 *
 * 执行规则见 step-sequence.js：快照驱动、跳过 READY、任一步失败即停。
 * 保留这个名字是因为调用点与测试都用它表达「卸载」语义。
 */
export const runUninstallSteps = runSteps

