#!/usr/bin/env python3
"""把前端 Vue / JS 里的中文文案换成 t()。

模板文本（`>中文<`）与属性里的静态中文都要改，所以按文件类型分两条路径：

  <script> 部分：字符串字面量 `'中文'` / `"中文"` / 模板串 → t('中文')
  模板部分：`>中文<` → `>{{ t('中文') }}<`；`placeholder="中文"` → `:placeholder="t('中文')"`

插值写法：把 `'前缀' + x + '后缀'` 合并成 `t('前缀{0}后缀', x)`，与后端同一套占位符。
三元表达式不合并，只逐个包裹，避免参数错位。

跳过：注释、已含 t( 的、含 {n} 占位符的模板串、`console.` 调用行。
"""
import io
import re
import sys

CJK = re.compile(r'[一-龥]')


def scan_js_strings(text):
    """扫描 JS 字符串（单引号 / 双引号 / 反引号），跳过注释。"""
    spans, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == '/' and i + 1 < n and text[i + 1] == '/':
            while i < n and text[i] != '\n':
                i += 1
            continue
        if c == '/' and i + 1 < n and text[i + 1] == '*':
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c in '\'"`':
            q = c
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == q:
                    break
                j += 1
            spans.append((i, j + 1, q))
            i = j + 1
            continue
        i += 1
    return spans


def split_plus(expr):
    parts, depth, buf, i, n = [], 0, [], 0, len(expr)
    while i < n:
        c = expr[i]
        if c in '\'"`':
            q = c
            j = i + 1
            while j < n:
                if expr[j] == '\\':
                    j += 2
                    continue
                if expr[j] == q:
                    break
                j += 1
            buf.append(expr[i:j + 1])
            i = j + 1
            continue
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        if c == '+' and depth == 0:
            parts.append(''.join(buf).strip())
            buf = []
            i += 1
            continue
        buf.append(c)
        i += 1
    parts.append(''.join(buf).strip())
    return parts


def chain_bounds(text, s, e):
    left = s
    while left > 0 and text[left - 1] not in '(;{},':
        left -= 1
    right, depth, n = e, 0, len(text)
    while right < n:
        c = text[right]
        if c in '([{':
            depth += 1
        elif c in ')]}':
            if depth == 0:
                break
            depth -= 1
        elif c in ';,' and depth == 0:
            break
        right += 1
    return left, right


def has_top_level(text, chars):
    depth = 0
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c in '\'"`':
            q = c
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == q:
                    break
                j += 1
            i = j + 1
            continue
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        elif depth == 0 and c in chars:
            return True
        i += 1
    return False


def wrap_literals(expr):
    for s, e, q in reversed(scan_js_strings(expr)):
        if not CJK.search(expr[s:e]):
            continue
        if expr[:s].rstrip().endswith('t('):
            continue
        lit = expr[s:e]
        single = convert_single(lit)
        expr = expr[:s] + (single or ('t(' + lit + ')')) + expr[e:]
    return expr


def convert_single(lit):
    """单个字面量 → t(...)；模板串带 ${} 时拆成占位符。"""
    tl = template_literal_parts(lit)
    if tl:
        segs, exprs = tl
        tmpl = segs[0]
        for i, e in enumerate(exprs):
            tmpl += '{%d}%s' % (i, segs[i + 1])
        return 't("%s"%s)' % (tmpl, ''.join(', ' + wrap_literals(e) for e in exprs))
    if lit[0] in '\'"`' and lit[-1] == lit[0] and lit.count(lit[0]) == 2:
        if re.search(r'\{\d+\}', lit):
            return None
        return 't(%s)' % lit
    return None


def template_literal_parts(lit):
    """`` `a${x}b` `` → (['a', 'b'], ['x'])；不含 ${} 时返回 None。"""
    if not (lit.startswith('`') and lit.endswith('`')):
        return None
    body = lit[1:-1]
    if '${' not in body:
        return None
    segs, exprs, buf, i, n = [], [], [], 0, len(body)
    while i < n:
        if body.startswith('${', i):
            depth = 1
            j = i + 2
            while j < n and depth > 0:
                if body[j] == '{':
                    depth += 1
                elif body[j] == '}':
                    depth -= 1
                j += 1
            segs.append(''.join(buf))
            buf = []
            exprs.append(body[i + 2:j - 1])
            i = j
            continue
        buf.append(body[i])
        i += 1
    segs.append(''.join(buf))
    return segs, exprs


def convert_chain(chain):
    parts = split_plus(chain)
    if not any(CJK.search(p) for p in parts):
        return None
    # 第一段必须是字面量：否则是 `{ summary: '…' + x }` 这类对象属性，
    # 合并会把 `summary:` 当成模板内容
    if not (parts[0][0] in '\'"`' and parts[0][-1] == parts[0][0]):
        return None
    if len(parts) == 1:
        p = parts[0]
        tl = template_literal_parts(p)
        if tl:
            segs, exprs = tl
            tmpl = ''.join(segs[0:1] + [''.join('{%d}%s' % (i, segs[i + 1])
                                                for i in range(len(exprs)))])
            return 't("%s"%s)' % (tmpl, ''.join(', ' + wrap_literals(e) for e in exprs))
        if p[0] in '\'"`' and p[-1] == p[0] and p.count(p[0]) == 2:
            return 't(%s)' % p
        return None
    if has_top_level(chain, '?'):
        return None
    tmpl, args = [], []
    for p in parts:
        tl = template_literal_parts(p)
        if tl:
            segs, exprs = tl
            tmpl.append(segs[0])
            for i, e in enumerate(exprs):
                tmpl.append('{%d}' % len(args))
                args.append(wrap_literals(e))
                tmpl.append(segs[i + 1])
            continue
        if p[0] in '\'"`' and p[-1] == p[0] and p.count(p[0]) == 2:
            if re.search(r'\{\d+\}', p):
                return None
            tmpl.append(p[1:-1])
        else:
            tmpl.append('{%d}' % len(args))
            args.append(wrap_literals(p))
    joined = ''.join(tmpl)
    return 't("%s"%s)' % (joined, ''.join(', ' + a for a in args))

def process_script(text):
    guard = 0
    while guard < 8000:
        guard += 1
        target = None
        for s, e, q in scan_js_strings(text):
            if not CJK.search(text[s:e]):
                continue
            if text[:s].rstrip().endswith(('t(', 'import ')):
                continue
            line_start = text.rfind('\n', 0, s) + 1
            line_end = text.find('\n', e)
            line = text[line_start:line_end if line_end >= 0 else len(text)]
            if re.search(r'\bconsole\.\w+\(', line):
                continue
            # 已翻译的模板串里不再处理
            if re.search(r'\bt\(\s*$', text[:s]):
                continue
            target = (s, e)
            break
        if target is None:
            break
        l, r = chain_bounds(text, *target)
        chain = text[l:r]
        indent = chain[:len(chain) - len(chain.lstrip())]
        converted = convert_chain(chain.strip())
        if converted is None:
            s, e = target
            lit = text[s:e]
            single = convert_single(lit)
            text = text[:s] + (single if single else 't(' + lit + ')') + text[e:]
            continue
        text = text[:l] + indent + converted + text[r:]
    return text


TEXT_RUN = re.compile(r'>([^<>]+)<')
ATTR = re.compile(r'(?<![:@\w-])(placeholder|title|alt|label|aria-label)="([^"]*[一-龥][^"]*)"')
MUSTACHE = re.compile(r'\{\{(.*?)\}\}', re.S)
MUSTACHE_G = re.compile(r'\{\{.*?\}\}', re.S)


def collapse(text):
    """多行文案压成一行：JS 双引号字符串不能跨行。"""
    return re.sub(r'\s*\n\s*', ' ', text).strip()


def merge_text_run(inner):
    """标签之间的内容 → 单个 t() 调用；返回 None 表示不用改。

    `共 {{ n }} 个文件` → `{{ t("共 {0} 个文件", n) }}`
    """
    parts, args, pos = [], [], 0
    for m in MUSTACHE_G.finditer(inner):
        seg = inner[pos:m.start()]
        parts.append(seg.replace('"', '\\"'))
        parts.append('{%d}' % len(args))
        args.append(m.group(0)[2:-2].strip())
        pos = m.end()
    parts.append(inner[pos:].replace('"', '\\"'))
    tmpl = ''.join(parts)
    if not CJK.search(tmpl):
        return None
    body = collapse(tmpl)
    if not body:
        return None
    call = 't("%s"%s)' % (body, ''.join(', ' + a for a in args))
    # 只保留行首缩进，避免把换行留在表达式里
    lead = re.match(r'\s*', inner).group(0)
    return '%s{{ %s }}' % (lead, call)


def to_single_quotes(text):
    """把表达式里的双引号字符串改成单引号：属性值用双引号包裹，内部不能再有双引号。"""
    out, last = [], 0
    for s, e, q in scan_js_strings(text):
        if q != '"':
            continue
        lit = text[s:e]
        if "'" in lit:
            continue
        out.append(text[last:s])
        out.append("'" + lit[1:-1] + "'")
        last = e
    out.append(text[last:])
    return ''.join(out)


def process_template(text):
    # 1) 插值里的文案（三元、拼接）与脚本同规则
    def repl_mustache(m):
        inner = m.group(1)
        if not CJK.search(inner):
            return m.group(0)
        return '{{%s}}' % process_script(inner)

    text = MUSTACHE.sub(repl_mustache, text)

    # 2) 标签之间的文本（可能夹着插值）
    def repl_text(m):
        merged = merge_text_run(m.group(1))
        if merged is None:
            return m.group(0)
        return '>' + merged + '<'

    text = TEXT_RUN.sub(repl_text, text)

    # 3) 属性绑定里的三元文案。
    #    属性值本身用双引号，所以先把内部的双引号换成单引号再处理，
    #    否则 process_script 产出的 t("…") 会提前截断属性。
    def repl_binding(m):
        raw = m.group(2)
        if not CJK.search(raw):
            return m.group(0)
        # 用占位符保护内部双引号
        guard = raw.replace('"', '\x00')
        converted = process_script(guard).replace('\x00', '"')
        converted = to_single_quotes(converted)
        return m.group(1) + converted + m.group(3)

    text = re.sub(r'(:[\w-]+=")([^"]*)(")', repl_binding, text)

    # 4) 静态属性
    def repl_attr(m):
        name, val = m.group(1), m.group(2)
        return ":%s=\"t('%s')\"" % (name, collapse(val).replace("'", "\\'"))

    return ATTR.sub(repl_attr, text)


IMPORT_LINE = "import { t } from '%s'"


def add_import(text, rel_prefix):
    """在第一个 import 之前补 `import { t } from '...'`；已存在则跳过。"""
    if re.search(r"import\s*\{[^}]*\bt\b[^}]*\}\s*from\s*'[^']*i18n", text):
        return text
    line = IMPORT_LINE % (rel_prefix + 'i18n.js')
    m = re.search(r'^import .*$', text, re.M)
    if m:
        return text[:m.start()] + line + '\n' + text[m.start():]
    # 没有 import：插在文件头注释块之后、第一行代码之前
    m = re.search(r'\A(?:[ \t]*(?:\*|/\*|//)[^\n]*\n|[ \t]*\n)*', text)
    pos = m.end() if m else 0
    return text[:pos] + line + '\n\n' + text[pos:]


def vue_regions(text):
    """返回 [(tag, start, end)]：顶层 <template> 与 <script> 的内容区间。

    不能用 find('</template>')：模板里常有嵌套 <template v-if>，
    那样会把区间截断在第一个内层闭合标签上。
    """
    regions = []
    m = re.search(r'^<template[^>]*>', text, re.M)
    if m:
        depth, pos = 1, m.end()
        while pos < len(text) and depth > 0:
            nxt_open = text.find('<template', pos)
            nxt_close = text.find('</template>', pos)
            if nxt_close < 0:
                break
            if 0 <= nxt_open < nxt_close:
                depth += 1
                pos = nxt_open + len('<template')
                continue
            depth -= 1
            pos = nxt_close + len('</template>')
            if depth == 0:
                regions.append(('template', m.end(), nxt_close))
    m = re.search(r'^<script[^>]*>', text, re.M)
    if m:
        end = text.find('</script>', m.end())
        if end >= 0:
            regions.append(('script', m.end(), end))
    return regions


def process(path):
    text = io.open(path, encoding='utf-8').read()
    if path.endswith('.vue'):
        regions = vue_regions(text)
        # 从后往前替换，偏移不失效
        for tag, start, end in reversed(regions):
            body = text[start:end]
            new = process_template(body) if tag == 'template' else process_script(body)
            text = text[:start] + new + text[end:]
        # 组件都在 src/ 下一层，统一用 ../i18n.js
        if 't(' in text:
            text = add_import(text, '../')
    else:
        text = process_script(text)
        if re.search(r'(?<![\w.])t\(', text):
            text = add_import(text, './')
    io.open(path, 'w', encoding='utf-8').write(text)


if __name__ == '__main__':
    for p in sys.argv[1:]:
        process(p)
