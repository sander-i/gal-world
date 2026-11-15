/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/* 原型共用示例数据：海报素材 + 媒体分组注册 */
'use strict'

window.DEMO = (() => {
  const poster = window.PV.poster

  /** 一组封面海报 */
  function coverSet(id, name, hues) {
    return hues.map(([title, a, b], i) => ({
      src: poster(title, a, b),
      type: 'image',
      label: `${id}-cover-${i + 1}.png`,
      title: `${name} · ${title}`,
    }))
  }

  const g1covers = coverSet('sample-game', '示例游戏', [
    ['天台 · 夏日', 210, 268],
    ['学园 · 午后', 120, 40],
    ['海边 · 黄昏', 20, 330],
  ])
  const g1cg = [
    { src: poster('CG 01 · 初见', 275, 320), type: 'image', label: 'cg-1.png' },
    { src: '', poster: poster('CG 02 · 烟花', 340, 30), type: 'video', label: 'cg-2.mp4' },
  ]

  const g2covers = coverSet('hoshifuru', '星空列车', [
    ['夜行列车', 250, 220],
    ['银河', 230, 280],
  ])
  const g3covers = coverSet('amian', '示例游戏', [['雨夜', 200, 260]])
  const g4covers = coverSet('summer', '示例游戏', [['夏日', 40, 160]])

  window.PV.register('g1-cover', g1covers)
  window.PV.register('g1-cg', g1cg)
  window.PV.register('g2-cover', g2covers)
  window.PV.register('g3-cover', g3covers)
  window.PV.register('g4-cover', g4covers)

  const games = [
    {
      id: 'sample-game', name: '示例游戏 A', state: 'RUNNING',
      companies: ['示例会社 A'], tags: ['恋爱', '校园', '喜剧'],
      meta: '1280×720 · 累计 42 小时', icon: '灵',
      group: 'g1-cover', covers: g1covers,
    },
    {
      id: 'hoshifuru', name: '示例游戏 B', state: 'STOPPED',
      companies: ['示例会社 B'], tags: ['治愈', '悬疑'],
      meta: '1920×1080 · 累计 12 小时', icon: '星',
      group: 'g2-cover', covers: g2covers,
    },
    {
      id: 'amian', name: '示例游戏 C', state: 'RUNNING',
      companies: ['示例会社 C'], tags: ['剧情', '短篇'],
      meta: '1280×720 · 累计 3 小时', icon: '雨',
      group: 'g3-cover', covers: g3covers,
    },
    {
      id: 'summer', name: '示例游戏 D', state: 'STOPPED',
      companies: ['示例会社 D'], tags: ['夏日', '催泪', '多女主'],
      meta: '1280×720 · 累计 0 分钟', icon: '夏',
      group: 'g4-cover', covers: g4covers,
    },
  ]

  // 示例视频生成后回填到 CG 分组（悬停 / 灯箱读取的是同一个对象）
  window.PV.makeVideo().then((url) => {
    if (!url) return
    g1cg[1].src = url
    if (typeof window.onDemoVideoReady === 'function') window.onDemoVideoReady(url)
  })

  return { games, g1covers, g1cg }
})()
