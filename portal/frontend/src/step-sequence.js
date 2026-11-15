/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 部署步骤的串行执行规则，安装与卸载共用。
 *
 * steps 必须是启动时的快照：不要用每步刷新后的状态驱动循环——一次状态读取失败
 * 就会把剩余步骤当成「查不到」跳过，最后仍报完成。
 * 查不到步骤按失败处理，不允许静默跳过。runStep(step) 返回 true 表示该步成功。
 */
export async function runSteps(seq, steps, runStep) {
  const byId = new Map((Array.isArray(steps) ? steps : []).map((s) => [s.id, s]))
  for (const id of Array.isArray(seq) ? seq : []) {
    const step = byId.get(id)
    if (!step) return { ok: false, failed: id, reason: 'missing' }
    if (step.state === 'READY') continue
    const done = await runStep(step)
    if (!done) return { ok: false, failed: id, reason: 'failed' }
  }
  return { ok: true }
}
