/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 编辑页「画质」分组的请求补丁构造。
 *
 * 语义：留空 = 门户不管理这个键。不能把空字符串下发，否则后端的可空字段
 * 会被写成空、进而覆盖 game.env 里人工调好的值。
 *
 * @param {{videoCrf:string, videoPaintoverCrf:string, rateControlMode:string,
 *          videoBitrate:string, keyframeInterval:string}} q 表单里的原始值（全是字符串）
 * @returns {object} 只含填了值的键
 */
export function qualityPatch(q) {
  const intOrNull = (v) => {
    if (v === '' || v == null) return null
    const n = Number(v)
    return Number.isFinite(n) ? Math.round(n) : null
  }
  const out = {}
  const crf = intOrNull(q.videoCrf)
  if (crf !== null) out.videoCrf = crf
  const pcrf = intOrNull(q.videoPaintoverCrf)
  if (pcrf !== null) out.videoPaintoverCrf = pcrf
  if (q.rateControlMode) out.rateControlMode = q.rateControlMode
  const bitrate = (q.videoBitrate || '').trim()
  if (bitrate) out.videoBitrate = bitrate
  const kf = intOrNull(q.keyframeInterval)
  if (kf !== null) out.keyframeInterval = kf
  return out
}

/**
 * 后端返回的可空画质字段 -> 表单里的字符串（null 显示成空）。
 * 用 String() 而不是 `?? ''` 是为了让 0（关键帧 0 = 无限 GOP）保持可见。
 */
export function qualityForm(d) {
  const str = (v) => (v == null ? '' : String(v))
  return {
    videoCrf: str(d?.videoCrf),
    videoPaintoverCrf: str(d?.videoPaintoverCrf),
    rateControlMode: d?.rateControlMode || '',
    videoBitrate: d?.videoBitrate || '',
    keyframeInterval: str(d?.keyframeInterval),
  }
}
