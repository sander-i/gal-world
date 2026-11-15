/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/* 静态原型的公共媒体能力：示例素材、悬停小窗、灯箱、轻提示 */
'use strict'

window.PV = (() => {
  const groups = Object.create(null)

  /* ---------------- 示例素材 ---------------- */
  function poster(title, hueA, hueB, w = 640, h = 360) {
    const c = document.createElement('canvas')
    c.width = w
    c.height = h
    const ctx = c.getContext('2d')
    const g = ctx.createLinearGradient(0, 0, w, h)
    g.addColorStop(0, `hsl(${hueA}, 46%, 30%)`)
    g.addColorStop(1, `hsl(${hueB}, 52%, 12%)`)
    ctx.fillStyle = g
    ctx.fillRect(0, 0, w, h)
    ctx.globalAlpha = 0.22
    ctx.strokeStyle = '#fff'
    ctx.lineWidth = 2
    for (let i = 0; i < 7; i++) {
      ctx.beginPath()
      ctx.arc(w * (0.2 + i * 0.12), h * (0.25 + (i % 3) * 0.24), 40 + i * 9, 0, Math.PI * 2)
      ctx.stroke()
    }
    ctx.globalAlpha = 1
    ctx.fillStyle = 'rgba(255,255,255,.92)'
    ctx.font = `600 ${Math.round(w * 0.053)}px "PingFang SC","Microsoft YaHei",sans-serif`
    ctx.fillText(title, w * 0.056, h - h * 0.145)
    ctx.font = `${Math.round(w * 0.022)}px "PingFang SC","Microsoft YaHei",sans-serif`
    ctx.fillStyle = 'rgba(255,255,255,.55)'
    ctx.fillText(`示例素材 · ${w}×${h}`, w * 0.056, h - h * 0.072)
    return c.toDataURL('image/png')
  }

  /** 现场录一段 2 秒 webm，作为示例视频（不支持时返回空串） */
  function makeVideo() {
    return new Promise((resolve) => {
      if (typeof MediaRecorder === 'undefined' || !HTMLCanvasElement.prototype.captureStream) {
        resolve('')
        return
      }
      try {
        const c = document.createElement('canvas')
        c.width = 640
        c.height = 360
        const ctx = c.getContext('2d')
        const t0 = performance.now()
        const timer = setInterval(draw, 33)
        function draw() {
          const t = (performance.now() - t0) / 1000
          const g = ctx.createLinearGradient(0, 0, 640, 360)
          g.addColorStop(0, `hsl(${(t * 40) % 360}, 52%, 26%)`)
          g.addColorStop(1, `hsl(${(t * 40 + 120) % 360}, 48%, 10%)`)
          ctx.fillStyle = g
          ctx.fillRect(0, 0, 640, 360)
          ctx.fillStyle = 'rgba(255,255,255,.9)'
          ctx.font = '600 36px "PingFang SC","Microsoft YaHei",sans-serif'
          ctx.fillText('CG 示例视频', 36, 190)
          ctx.font = '15px "PingFang SC","Microsoft YaHei",sans-serif'
          ctx.fillStyle = 'rgba(255,255,255,.55)'
          ctx.fillText('由浏览器现场生成，仅用于预览', 36, 222)
          ctx.beginPath()
          ctx.arc(320 + Math.cos(t * 2) * 140, 120 + Math.sin(t * 2) * 46, 16, 0, Math.PI * 2)
          ctx.fillStyle = 'rgba(255,255,255,.28)'
          ctx.fill()
        }
        const stream = c.captureStream(24)
        let rec
        try {
          rec = new MediaRecorder(stream, { mimeType: 'video/webm' })
        } catch {
          clearInterval(timer)
          resolve('')
          return
        }
        const chunks = []
        rec.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data) }
        rec.onstop = () => {
          clearInterval(timer)
          resolve(URL.createObjectURL(new Blob(chunks, { type: 'video/webm' })))
        }
        rec.start()
        setTimeout(() => { try { rec.stop() } catch { clearInterval(timer); resolve('') } }, 2000)
      } catch {
        resolve('')
      }
    })
  }

  /* ---------------- 媒体注册 ---------------- */
  function register(group, items) {
    groups[group] = items
    return items
  }
  function itemOf(el) {
    const group = el.getAttribute('data-pv-group')
    const index = Number(el.getAttribute('data-pv-index') || 0)
    if (group) return (groups[group] || [])[index] || null
    const src = el.getAttribute('data-pv-src') || ''
    if (!src) return null
    return {
      src,
      type: el.getAttribute('data-pv-type') || 'image',
      label: el.getAttribute('data-pv-label') || '',
    }
  }

  /* ---------------- 悬停小窗 ---------------- */
  let pop = null
  let popTimer = null
  let popAnchor = null

  function ensurePop() {
    if (pop) return pop
    pop = document.createElement('div')
    pop.className = 'pv-pop'
    pop.setAttribute('aria-hidden', 'true')
    document.body.appendChild(pop)
    return pop
  }

  function destroyPop() {
    if (popTimer) { clearTimeout(popTimer); popTimer = null }
    if (pop) {
      const v = pop.querySelector('video')
      if (v) {
        try { v.pause(); v.removeAttribute('src'); v.load() } catch {}
      }
      pop.remove()
      pop = null
    }
    popAnchor = null
  }

  function showPop(el) {
    const item = itemOf(el)
    if (!item) return
    const node = ensurePop()
    node.textContent = ''

    let media
    if (item.type === 'video' && item.src) {
      media = document.createElement('video')
      media.src = item.src
      media.muted = true
      media.loop = true
      media.autoplay = true
      media.playsInline = true
    } else {
      media = document.createElement('img')
      media.src = item.src || item.poster || ''
      media.alt = item.label || ''
    }
    media.className = 'pv-pop-media'
    node.appendChild(media)

    const cap = document.createElement('div')
    cap.className = 'pv-pop-cap'
    const name = document.createElement('span')
    name.className = 'pv-name'
    name.textContent = item.label || ''
    const type = document.createElement('span')
    type.className = 'pv-type'
    type.textContent = item.type === 'video' ? '视频' : '图片'
    cap.appendChild(name)
    cap.appendChild(type)
    node.appendChild(cap)

    const r = el.getBoundingClientRect()
    const w = 320
    const h = node.offsetHeight || 220
    let x = r.right + 12
    if (x + w > window.innerWidth - 12) x = r.left - w - 12
    if (x < 12) x = Math.min(window.innerWidth - w - 12, Math.max(12, r.left))
    let y = r.top
    if (y + h > window.innerHeight - 12) y = window.innerHeight - h - 12
    if (y < 12) y = 12
    node.style.left = x + 'px'
    node.style.top = y + 'px'
    node.classList.add('show')
    popAnchor = el
    if (media.tagName === 'VIDEO') media.play().catch(() => {})
  }

  function scheduleShow(el) {
    destroyPop()
    popTimer = setTimeout(() => showPop(el), 140)
  }

  document.addEventListener('mouseover', (e) => {
    const el = e.target.closest && e.target.closest('[data-pv-group],[data-pv-src]')
    if (!el || el === popAnchor) return
    scheduleShow(el)
  })
  document.addEventListener('mouseout', (e) => {
    const el = e.target.closest && e.target.closest('[data-pv-group],[data-pv-src]')
    if (!el) return
    const to = e.relatedTarget
    if (to && el.contains(to)) return
    destroyPop()
  })
  window.addEventListener('scroll', destroyPop, { passive: true })
  document.addEventListener('click', destroyPop, true)

  /* ---------------- 灯箱 ---------------- */
  const lb = { open: false, group: '', index: 0, node: null, video: null }

  function openLightbox(group, index) {
    const items = groups[group] || []
    if (!items.length) return
    destroyPop()
    lb.open = true
    lb.group = group
    lb.index = Math.max(0, Math.min(index, items.length - 1))
    renderLightbox()
  }

  function renderLightbox() {
    destroyLightbox()
    const items = groups[lb.group] || []
    if (!items.length) return
    const item = items[lb.index]

    const box = document.createElement('div')
    box.className = 'pv-lightbox'
    box.setAttribute('role', 'dialog')
    box.setAttribute('aria-modal', 'true')
    box.setAttribute('aria-label', '媒体预览')

    const head = document.createElement('div')
    head.className = 'pv-lb-head'
    const file = document.createElement('span')
    file.textContent = item.label || ''
    const idx = document.createElement('span')
    idx.className = 'pv-idx'
    idx.textContent = `${lb.index + 1} / ${items.length}`
    const close = document.createElement('button')
    close.className = 'pv-lb-close'
    close.textContent = '×'
    close.title = '关闭（Esc）'
    close.addEventListener('click', closeLightbox)
    head.appendChild(file)
    head.appendChild(idx)
    head.appendChild(close)
    box.appendChild(head)

    const body = document.createElement('div')
    body.className = 'pv-lb-body'
    if (item.type === 'video' && item.src) {
      const v = document.createElement('video')
      v.src = item.src
      v.controls = true
      v.autoplay = true
      v.muted = true
      v.playsInline = true
      lb.video = v
      body.appendChild(v)
    } else {
      const img = document.createElement('img')
      img.src = item.src || item.poster || ''
      img.alt = item.label || ''
      body.appendChild(img)
    }
    box.appendChild(body)

    if (items.length > 1) {
      const prev = document.createElement('button')
      prev.className = 'pv-lb-nav pv-lb-prev'
      prev.textContent = '‹'
      prev.title = '上一项（←）'
      prev.addEventListener('click', () => { lb.index = (lb.index - 1 + items.length) % items.length; renderLightbox() })
      const next = document.createElement('button')
      next.className = 'pv-lb-nav pv-lb-next'
      next.textContent = '›'
      next.title = '下一项（→）'
      next.addEventListener('click', () => { lb.index = (lb.index + 1) % items.length; renderLightbox() })
      box.appendChild(prev)
      box.appendChild(next)
    }

    const foot = document.createElement('div')
    foot.className = 'pv-lb-foot'
    foot.textContent = '← / → 切换 · Esc 关闭'
    box.appendChild(foot)

    box.addEventListener('click', (e) => {
      if (e.target === box || e.target === body) closeLightbox()
    })
    document.body.appendChild(box)
    document.body.style.overflow = 'hidden'
    lb.node = box
    document.addEventListener('keydown', onKey)
    close.focus()
  }

  function onKey(e) {
    if (!lb.open) return
    const n = (groups[lb.group] || []).length
    if (e.key === 'Escape') { e.preventDefault(); closeLightbox() }
    else if (e.key === 'ArrowLeft') { e.preventDefault(); lb.index = (lb.index - 1 + n) % n; renderLightbox() }
    else if (e.key === 'ArrowRight') { e.preventDefault(); lb.index = (lb.index + 1) % n; renderLightbox() }
  }

  function closeLightbox() {
    lb.open = false
    destroyLightbox()
    document.body.style.overflow = ''
  }

  function destroyLightbox() {
    document.removeEventListener('keydown', onKey)
    if (lb.video) {
      try { lb.video.pause(); lb.video.removeAttribute('src'); lb.video.load() } catch {}
      lb.video = null
    }
    if (lb.node) { lb.node.remove(); lb.node = null }
  }

  document.addEventListener('click', (e) => {
    const el = e.target.closest && e.target.closest('[data-pv-open]')
    if (!el) return
    const group = el.getAttribute('data-pv-group')
    const index = Number(el.getAttribute('data-pv-index') || 0)
    if (group) {
      e.preventDefault()
      openLightbox(group, index)
    }
  })

  /* ---------------- 轻提示 ---------------- */
  function toast(text, tone) {
    let box = document.querySelector('.pv-toasts')
    if (!box) {
      box = document.createElement('div')
      box.className = 'pv-toasts'
      document.body.appendChild(box)
    }
    const t = document.createElement('div')
    t.className = 'pv-toast ' + (tone || '')
    t.textContent = text
    box.appendChild(t)
    setTimeout(() => {
      t.style.transition = 'opacity .25s'
      t.style.opacity = '0'
      setTimeout(() => t.remove(), 260)
    }, 2800)
  }

  /* ---------------- 小工具 ---------------- */
  function fmtSize(bytes) {
    if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(bytes % (1024 * 1024) === 0 ? 0 : 1) + ' MB'
    if (bytes >= 1024) return Math.round(bytes / 1024) + ' KB'
    return bytes + ' B'
  }

  /** 原型导航高亮 */
  function markNav() {
    const page = location.pathname.split('/').pop() || 'index.html'
    document.querySelectorAll('.pv-nav a[data-page]').forEach((a) => {
      if (a.getAttribute('data-page') === page) a.classList.add('on')
    })
  }
  document.addEventListener('DOMContentLoaded', markNav)
  if (document.readyState !== 'loading') markNav()

  return { poster, makeVideo, register, openLightbox, toast, fmtSize }
})()
