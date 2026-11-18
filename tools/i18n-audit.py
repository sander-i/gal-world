#!/usr/bin/env python3
"""找出前端未走 t() 的中文文案。

思路反过来：先收集所有 t(...) 覆盖的字符串区间，剩下含中文的就是漏翻译。
这样不会因为属性引号、嵌套引号而误判。

用法：python3 tools/i18n-audit.py [src 目录]
"""
import glob
import io
import re
import sys

CJK_MIN, CJK_MAX = 0x4e00, 0x9fa5
SQ = chr(39)
DQ = chr(34)
BT = chr(96)
BS = chr(92)


def has_cjk(s):
    return any(CJK_MIN <= ord(c) <= CJK_MAX for c in s)


def scan_strings(text, base=0):
    """返回 [(start, end, quote)]，跳过注释。

    属性值 `:placeholder="t('x')"` 在外层是双引号字符串，但内层还有单引号
    字符串。内层也要扫，否则 `t('x')` 里的 x 会被当成外层字符串的一部分。
    """
    spans = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '<' and text.startswith('<!--', i):
            j = text.find('-->', i + 4)
            i = n if j < 0 else j + 3
            continue
        if c == '/' and text.startswith('//', i):
            j = text.find('\n', i)
            i = n if j < 0 else j
            continue
        if c == '/' and text.startswith('/*', i):
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c in (SQ, DQ, BT):
            q = c
            j = i + 1
            while j < n:
                if text[j] == BS:
                    j += 2
                    continue
                if text[j] == q:
                    break
                j += 1
            spans.append((base + i, base + j + 1, q))
            # 属性值形态（外层双引号里含其它引号）：递归扫内部
            if q == DQ and (SQ in text[i + 1:j] or 't(' in text[i + 1:j]):
                inner = text[i + 1:j]
                for s, e, iq in scan_strings(inner, base + i + 1):
                    if iq != DQ:
                        spans.append((s, e, iq))
            i = j + 1
            continue
        i += 1
    return spans


def t_covered(text, spans):
    """收集所有 t( 调用里第一个字符串字面量的区间。

    模板里 t('x') 会出现在属性引号内部，所以不能靠「前一个字符是 (」判断；
    直接找文本里的 `t(` 再定位其后第一个字符串字面量。
    """
    covered = set()
    for m in re.finditer(r'\bt\(', text):
        start = m.end()
        # 跳过空白
        k = start
        while k < len(text) and text[k] in ' \t\n':
            k += 1
        for s, e, q in spans:
            if s == k:
                covered.add((s, e))
                break
    return covered


def scan_file(path):
    text = io.open(path, encoding='utf-8').read()
    spans = scan_strings(text)
    covered = t_covered(text, spans)
    hits = []
    for s, e, q in spans:
        if (s, e) in covered:
            continue
        lit = text[s:e]
        if not has_cjk(lit):
            continue
        # 外层属性值里含 t(：真正要查的是内层，外层整段跳过
        if q == DQ and 't(' in lit:
            continue
        lineno = text.count('\n', 0, s) + 1
        hits.append((lineno, 'string', lit[:110]))

    # 模板静态文本：同一行内的 >中文<
    for m in re.finditer('>([^<>\\n]*[一-龥][^<>\\n]*)<', text):
        inner = m.group(1).strip()
        if not inner or inner.startswith('{{'):
            continue
        # 落在字符串字面量里的不算（那是属性值或脚本字符串，上面已查）
        if any(s <= m.start() < e for s, e, _ in spans):
            continue
        lineno = text.count('\n', 0, m.start()) + 1
        hits.append((lineno, 'text', inner[:110]))
    return sorted(hits)


def main():
    roots = sys.argv[1:] or ['src']
    total = 0
    for root in roots:
        for p in sorted(glob.glob(root + '/**/*.vue', recursive=True)
                        + glob.glob(root + '/*.js', recursive=True)):
            if p.endswith('i18n.js'):
                continue
            hits = scan_file(p)
            if hits:
                total += len(hits)
                print('\n=== %s (%d) ===' % (p, len(hits)))
                for lineno, kind, txt in hits:
                    print('  %d [%s] %s' % (lineno, kind, txt))
    print('\n总计 %d 处' % total)


if __name__ == '__main__':
    main()
