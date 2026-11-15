import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')

/**
 * :root 是变量定义区，也是全文件唯一允许出现字面色值的地方；
 * 其余位置一律用 var(--...) 取色，否则切换主题时这些颜色不会跟着变。
 * 个别必须固定颜色的地方（灯箱文字等）在对应行尾加注释 `theme-ok: 原因` 放行。
 */
const ALLOW = /\/\* theme-ok:/

let inRoot = false
let depth = 0
const hits = []

css.split('\n').forEach((text, i) => {
  if (/^:root\s*\{/.test(text.trim())) {
    inRoot = true
    depth = 0
  }
  if (inRoot) {
    // 跟踪 :root 的括号深度，跳过整个变量定义块
    depth += (text.match(/\{/g) || []).length
    depth -= (text.match(/\}/g) || []).length
    if (depth <= 0) inRoot = false
    return
  }
  if (ALLOW.test(text)) return
  if (/#[0-9a-fA-F]{3,8}\b/.test(text) || /\brgba?\(/.test(text)) {
    hits.push({ line: i + 1, text })
  }
})

if (hits.length) {
  for (const h of hits) console.error(`styles.css:${h.line}: ${h.text.trim()}`)
  console.error(`\n还有 ${hits.length} 处字面色值，必须改成 var(--...)`)
  process.exit(1)
}
console.log('OK: styles.css 无字面色值')
