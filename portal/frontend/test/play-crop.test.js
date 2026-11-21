/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { cropPlan, streamFitStyle, sameWindowRect, topGuardRect,
  TOP_GUARD_PX, TOP_GUARD_CANVAS_PX, WINDOW_SAMPLE_DELAYS, WINDOW_STABLE_AFTER_MS }
  from '../src/play-crop.js'

/** 从生成的 CSS 里取某条规则的声明块 */
function rule(css, selector) {
  const re = new RegExp(`${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')} \\{([^}]*)\\}`)
  const m = css.match(re)
  return m ? m[1] : ''
}

/** 从声明块里取一个像素值 */
function px(decl, prop) {
  const m = decl.match(new RegExp(`(?:^|;)\\s*${prop}:\\s*(-?\\d+)px`))
  return m ? Number(m[1]) : NaN
}

const box = { width: 1600, height: 800 }

test('没有窗口信息时按画布比例铺满（旧行为）', () => {
  const p = cropPlan(box, { width: 1280, height: 720 }, null)
  assert.equal(p.viewW, 1422)
  assert.equal(p.viewH, 800)
  assert.equal(p.frameW, 1422)
  assert.equal(p.frameH, 800)
  assert.equal(p.left, 0)
  assert.equal(p.top, 0)
})

test('窗口小于画布时放大并按窗口位置位移', () => {
  // 画布 1280x720，窗口 968x593 居中于 (156,63)
  const p = cropPlan(box, { width: 1280, height: 720 },
    { x: 156, y: 63, width: 968, height: 593 })
  // 按高度贴合：800 / 593 = 1.3491 倍 -> 视口宽 1305
  assert.equal(p.viewH, 800)
  assert.equal(p.viewW, 1305)
  assert.equal(p.frameW, 1726)
  assert.equal(p.frameH, 971)
  assert.equal(p.left, -210)
  assert.equal(p.top, -85)
})

test('窗口等于画布时是恒等变换', () => {
  const p = cropPlan(box, { width: 1280, height: 720 },
    { x: 0, y: 0, width: 1280, height: 720 })
  assert.equal(p.viewW, p.frameW)
  assert.equal(p.viewH, p.frameH)
  assert.equal(p.left, 0)
  assert.equal(p.top, 0)
})

test('窗口宽高为 0 时退回旧行为', () => {
  const p = cropPlan(box, { width: 1280, height: 720 }, { x: 0, y: 0, width: 0, height: 0 })
  assert.equal(p.viewW, 1422)
  assert.equal(p.left, 0)
})

test('窗口占画布 26% 时放大画布、负偏移，窗口矩形映射后正好等于容器', () => {
  const canvas = { width: 1280, height: 720 }
  const win = { x: 474, y: 266, width: 333, height: 187 } // 333/1280 ≈ 26%
  const p = cropPlan(box, canvas, win)

  assert.ok(p.frameW > p.viewW, `frameW ${p.frameW} 应大于 viewW ${p.viewW}`)
  assert.ok(p.left < 0, `left ${p.left} 应为负`)
  assert.ok(p.top < 0, `top ${p.top} 应为负`)

  // 窗口在画布上的矩形映射到容器坐标系后，应贴合容器的左/上边缘，
  // 宽度/高度应等于容器尺寸（允许 1px 取整误差）
  const sx = p.frameW / canvas.width
  const sy = p.frameH / canvas.height
  assert.ok(Math.abs(p.left + win.x * sx) <= 1, `left 映射偏差 ${p.left + win.x * sx}`)
  assert.ok(Math.abs(p.top + win.y * sy) <= 1, `top 映射偏差 ${p.top + win.y * sy}`)
  assert.ok(Math.abs(win.width * sx - p.viewW) <= 1, `宽度映射偏差 ${win.width * sx - p.viewW}`)
  assert.ok(Math.abs(win.height * sy - p.viewH) <= 1, `高度映射偏差 ${win.height * sy - p.viewH}`)
})

test('窗口越出画布时先裁到可见区，画面仍铺满容器', () => {
  // sugarstyle 实测：窗口 1286x752 + (-3,-29) 对画布 1280x720
  const canvas = { width: 1280, height: 720 }
  const win = { x: -3, y: -29, width: 1286, height: 752 }
  const p = cropPlan(box, canvas, win)

  // 可见区 = (0,0)-(1280,720)：整块画布
  assert.equal(p.left, 0)
  assert.equal(p.top, 0)
  assert.equal(p.frameW, p.viewW)
  assert.equal(p.frameH, p.viewH)
  assert.ok(p.frameW >= p.viewW, `frameW ${p.frameW} 不应小于 viewW ${p.viewW}`)
})

test('窗口只有一部分在画布内时按可见部分取比例', () => {
  const canvas = { width: 1280, height: 720 }
  // 窗口左上角在画布外，只有右下 640x360 可见
  const win = { x: -640, y: -360, width: 1280, height: 720 }
  const p = cropPlan(box, canvas, win)
  // 可见区 640x360 是 16:9，与画布同比例 -> 容器铺满，画布不变形
  assert.equal(p.viewW, 1422)
  assert.equal(p.viewH, 800)
  assert.equal(p.frameW, 2844)
  assert.equal(p.frameH, 1600)
  // 可见区起点 (0,0) -> 无位移
  assert.equal(p.left, 0)
  assert.equal(p.top, 0)
})

test('窗口完全在画布外时退回旧行为', () => {
  const p = cropPlan(box, { width: 1280, height: 720 },
    { x: 2000, y: 2000, width: 640, height: 360 })
  assert.equal(p.viewW, 1422)
  assert.equal(p.frameW, 1422)
  assert.equal(p.left, 0)
  assert.equal(p.top, 0)
})

test('streamFitStyle 把容器定位到居中位置，内部画布按 frame 放大并位移', () => {
  const css = streamFitStyle(box, { width: 1280, height: 720 },
    { x: 320, y: 180, width: 640, height: 360 }, 44)
  const boxDecl = rule(css, '.video-container')
  const mediaDecl = rule(css, '.video-container video, .video-container canvas, .video-container #overlayInput')

  assert.equal(px(boxDecl, 'width'), 1422)
  assert.equal(px(boxDecl, 'height'), 800)
  assert.equal(px(boxDecl, 'left'), 89) // (1600 - 1422) / 2
  assert.equal(px(boxDecl, 'top'), 44) // 顶栏 + (800 - 800) / 2
  assert.match(boxDecl, /overflow:\s*hidden/)

  assert.equal(px(mediaDecl, 'width'), 2844)
  assert.equal(px(mediaDecl, 'height'), 1600)
  assert.equal(px(mediaDecl, 'left'), -711)
  assert.equal(px(mediaDecl, 'top'), -400)
})

test('输入层 overlayInput 与画布共用同一组尺寸位移', () => {
  // 只改 video/canvas 会让输入层缩在中间，两侧可见画面上点不到
  const css = streamFitStyle(box, { width: 1280, height: 720 },
    { x: 320, y: 180, width: 640, height: 360 }, 44)
  const mediaDecl = rule(css, '.video-container video, .video-container canvas, .video-container #overlayInput')
  assert.match(css, /#overlayInput/)
  assert.equal(px(mediaDecl, 'left'), -711)
  assert.equal(px(mediaDecl, 'width'), 2844)
  // 不应存在只覆盖 video/canvas 的规则，否则会覆盖掉输入层
  assert.doesNotMatch(css, /\.video-container video, \.video-container canvas\s*\{[^}]*\}/)
})

test('win 为 null 时 streamFitStyle 退回旧行为：无偏移、画布与容器同尺寸', () => {
  const css = streamFitStyle(box, { width: 1280, height: 720 }, null, 44)
  const boxDecl = rule(css, '.video-container')
  const mediaDecl = rule(css, '.video-container video, .video-container canvas, .video-container #overlayInput')

  assert.equal(px(mediaDecl, 'left'), 0)
  assert.equal(px(mediaDecl, 'top'), 0)
  assert.equal(px(mediaDecl, 'width'), px(boxDecl, 'width'))
  assert.equal(px(mediaDecl, 'height'), px(boxDecl, 'height'))
})

test('topBar 被加到容器 top 上', () => {
  const win = { x: 320, y: 180, width: 640, height: 360 }
  const a = rule(streamFitStyle(box, { width: 1280, height: 720 }, win, 0), '.video-container')
  const b = rule(streamFitStyle(box, { width: 1280, height: 720 }, win, 44), '.video-container')
  assert.equal(px(b, 'top') - px(a, 'top'), 44)
  assert.equal(px(a, 'left'), px(b, 'left'))
})

test('顶部屏蔽带从容器上沿开始，至少 TOP_GUARD_PX 高', () => {
  const win = { x: 320, y: 180, width: 640, height: 360 }
  const r = topGuardRect(box, { width: 1280, height: 720 }, win, 8)
  assert.equal(r.top, px(rule(streamFitStyle(box, { width: 1280, height: 720 }, win, 8),
    '.video-container'), 'top'))
  // 窗口 640 宽放大到 1422：scale≈2.22，画布下限 14 × 2.22 ≈ 31 已超过 10
  assert.ok(r.bottom - r.top >= TOP_GUARD_PX)
  assert.ok(r.bottom - r.top >= TOP_GUARD_CANVAS_PX)
})

test('顶部屏蔽带按画布像素下限算：放大时比 CSS 下限更宽', () => {
  // 窗口 1600x800 被裁到画布 1280x720 后铺满容器，scale ≈ 1.11：
  // 要盖住 14 个画布像素需要 14 × 1.11 ≈ 16 CSS 像素
  const win = { x: 0, y: 0, width: 1600, height: 800 }
  const r = topGuardRect(box, { width: 1280, height: 720 }, win, 0)
  const scale = 1422 / 1280
  assert.equal(r.bottom - r.top, Math.round(TOP_GUARD_CANVAS_PX * scale))
  assert.ok(r.bottom - r.top >= TOP_GUARD_PX)
})

test('没有窗口信息时屏蔽带按画布下限（scale=1）', () => {
  const r = topGuardRect(box, { width: 1280, height: 720 }, null, 0)
  assert.equal(r.top, 0)
  assert.equal(r.bottom, TOP_GUARD_CANVAS_PX)
})

test('全屏视口变大时容器等比铺满（不封顶）', () => {
  const canvas = { width: 1920, height: 1080 }
  const win = { x: 12, y: 12, width: 1896, height: 1056 }
  const windowed = cropPlan({ width: 1280, height: 712 }, canvas, win)
  const full = cropPlan({ width: 1920, height: 1072 }, canvas, win)
  assert.ok(full.scale > windowed.scale, '全屏后画面应更大')
  assert.ok(Math.abs(full.scale - 1.015) < 0.02, `全屏倍数 ${full.scale}`)
})

test('窗口采样表递增且首项为正，覆盖 run-game.sh 的放大窗口期', () => {
  assert.ok(WINDOW_SAMPLE_DELAYS.length > 0)
  assert.ok(WINDOW_SAMPLE_DELAYS[0] > 0)
  for (let i = 1; i < WINDOW_SAMPLE_DELAYS.length; i++) {
    assert.ok(WINDOW_SAMPLE_DELAYS[i] > WINDOW_SAMPLE_DELAYS[i - 1])
  }
  // 最后一次采样应晚于 run-game.sh 放大完成的最晚时刻（约 10s）
  assert.ok(WINDOW_SAMPLE_DELAYS.at(-1) >= 10_000)
  // 稳定判定门槛落在采样表内，且不早于放大窗口期
  assert.ok(WINDOW_STABLE_AFTER_MS >= 4000)
  assert.ok(WINDOW_SAMPLE_DELAYS.includes(WINDOW_STABLE_AFTER_MS))
})

test('sameWindowRect 只有位置与宽高都相同才算稳定', () => {
  const a = { x: 0, y: 0, width: 968, height: 593 }
  assert.equal(sameWindowRect(a, { ...a }), true)
  assert.equal(sameWindowRect(a, { ...a, width: 1280 }), false)
  assert.equal(sameWindowRect(a, { ...a, x: 156 }), false)
  assert.equal(sameWindowRect(null, null), true)
  assert.equal(sameWindowRect(a, null), false)
})
