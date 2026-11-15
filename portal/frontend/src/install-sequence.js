/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { runSteps } from './step-sequence.js'

/**
 * 一键安装的执行顺序：按 requires 做拓扑排序，同层保持步骤表原有顺序。
 *
 * - 只排安装步骤（uninstall=false），卸载步骤不参与；
 * - requires 指向本平台不存在的 id 时忽略，避免一个笔误让整轮顺序变成空；
 * - 依赖成环时剩下的步骤不会被排进来（返回列表会短于步骤数），调用方据此报错。
 */
export function installSequence(steps) {
  const list = (Array.isArray(steps) ? steps : []).filter((s) => s && !s.uninstall)
  const known = new Set(list.map((s) => s.id))
  const deps = new Map(list.map((s) => [
    s.id,
    (Array.isArray(s.requires) ? s.requires : []).filter((r) => known.has(r)),
  ]))

  const out = []
  const taken = new Set()
  for (let round = 0; round < list.length; round += 1) {
    let progressed = false
    for (const s of list) {
      if (taken.has(s.id)) continue
      if (!deps.get(s.id).every((d) => taken.has(d))) continue
      out.push(s.id)
      taken.add(s.id)
      progressed = true
    }
    if (!progressed) break
  }
  return out
}

/**
 * 顺序执行安装步骤。执行规则与卸载一致：快照驱动、跳过 READY、任一步失败即停。
 */
export const runInstallSteps = runSteps
