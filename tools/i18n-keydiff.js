// 一次性检查脚本（只读）：比对前端 t() 使用的键与语言包键集
const fs = require('fs')
const path = require('path')

const ROOT = process.argv[2] || 'portal/frontend/src'
const PACK = process.argv[3] || 'portal/src/main/resources/i18n/en.json'

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else if (/\.(vue|js)$/.test(e.name)) out.push(p)
  }
}

const files = []
walk(ROOT, files)

const QUOTES = ["'", '"', '`']
const used = new Map() // key -> [files]

for (const f of files) {
  const src = fs.readFileSync(f, 'utf8')
  let i = 0
  while ((i = src.indexOf('t(', i)) >= 0) {
    // 排除标识符内：前一个字符必须是分隔符
    const prev = i === 0 ? '' : src[i - 1]
    if (/[A-Za-z0-9_$.]/.test(prev)) { i += 2; continue }
    let j = i + 2
    while (j < src.length && /\s/.test(src[j])) j++
    const q = src[j]
    if (!QUOTES.includes(q)) { i += 2; continue }
    let k = j + 1
    let buf = ''
    let closed = false
    while (k < src.length) {
      const c = src[k]
      if (c === '\\') { buf += c + (src[k + 1] || ''); k += 2; continue }
      if (c === q) { closed = true; break }
      if (c === '\n' && q !== '`') break
      buf += c
      k++
    }
    if (closed) {
      const list = used.get(buf) || []
      if (!list.includes(f)) list.push(f)
      used.set(buf, list)
    }
    i += 2
  }
}

const pack = JSON.parse(fs.readFileSync(PACK, 'utf8'))
const messages = pack.messages || pack
const packKeys = Object.keys(messages)

const missing = [...used.keys()].filter(k => !(k in messages)).sort()
const unused = packKeys.filter(k => !used.has(k)).sort()

const CJK = /[一-龥]/

console.log(`前端文件数: ${files.length}`)
console.log(`t() 不同键数: ${used.size}`)
console.log(`语言包键数: ${packKeys.length}`)
console.log(`\n[A] 代码用了但语言包没有 (${missing.length})`)
for (const k of missing) {
  console.log(`  ${CJK.test(k) ? '中文' : '非中文'} ${JSON.stringify(k)}  <- ${used.get(k).map(f => f.replace(/\\/g, '/')).join(', ')}`)
}
console.log(`\n[B] 语言包有但代码没用到 (${unused.length})`)
for (const k of unused.slice(0, 200)) console.log(`  ${JSON.stringify(k)}`)
if (unused.length > 200) console.log(`  ... 还有 ${unused.length - 200} 条`)
