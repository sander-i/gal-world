#!/usr/bin/env python3
"""找出前端 t("...", a, b) 多参数调用。

前端 t(key, args) 只接一个参数；后端 I18n.t 是可变参数。
写 t("...{1}...", a, b) 时第二、三个实参被丢弃，占位符原样留在页面上。
"""
import glob
import io
import sys

QUOTES = ('"', "'", '`')


def scan_calls(text):
    """返回 [(offset, key, argcount)]，argcount 是顶层逗号数 + 1。"""
    out = []
    i, n = 0, len(text)
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
        if c in QUOTES:
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
        if c == 't' and i + 1 < n and text[i + 1] == '(':
            before = text[i - 1] if i else ' '
            if not (before.isalnum() or before in '_$.'):
                k = i + 2
                while k < n and text[k] in ' \t\n':
                    k += 1
                if k < n and text[k] in QUOTES:
                    q = text[k]
                    s = k + 1
                    e = s
                    while e < n:
                        if text[e] == '\\':
                            e += 2
                            continue
                        if text[e] == q:
                            break
                        e += 1
                    key = text[s:e]
                    # 从引号后数到匹配的 )
                    m = e + 1
                    depth = 0
                    argc = 0
                    seen_comma = False
                    while m < n:
                        ch = text[m]
                        if ch in QUOTES:
                            qq = ch
                            m += 1
                            while m < n:
                                if text[m] == '\\':
                                    m += 2
                                    continue
                                if text[m] == qq:
                                    break
                                m += 1
                        elif ch in '([{':
                            depth += 1
                        elif ch in ')]}':
                            if depth == 0:
                                break
                            depth -= 1
                        elif ch == ',' and depth == 0:
                            seen_comma = True
                            argc += 1
                        m += 1
                    out.append((i, key, argc + 1 if seen_comma else 0))
                    i = m
                    continue
        i += 1
    return out


def main():
    roots = sys.argv[1:] or ['src']
    total = 0
    for root in roots:
        paths = (glob.glob(root + '/**/*.vue', recursive=True)
                 + glob.glob(root + '/**/*.js', recursive=True))
        for p in sorted(paths):
            if p.endswith('i18n.js'):
                continue
            text = io.open(p, encoding='utf-8').read()
            bad = []
            for off, key, argc in scan_calls(text):
                if argc >= 2:
                    ln = text.count('\n', 0, off) + 1
                    bad.append((ln, argc, key))
            if bad:
                total += len(bad)
                print('\n=== %s (%d) ===' % (p, len(bad)))
                for ln, argc, key in bad:
                    print('  %d  args=%d  %s' % (ln, argc, key[:70]))
    print('\n总计 %d 处多参数 t()' % total)


if __name__ == '__main__':
    main()
