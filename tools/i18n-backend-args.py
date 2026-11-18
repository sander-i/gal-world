#!/usr/bin/env python3
"""检查后端 I18n.t("…{0}…") 的实参与占位符是否对得上。

两类问题：
- 无实参但文案里有 {n}：页面上会原样显示占位符
- 实参个数少于最大占位符编号 + 1：同上
"""
import glob
import io
import re
import sys

PH = re.compile(r'\{(\d+)\}')


def scan(text):
    """返回 [(offset, key, argcount)]；argcount 是 key 之后的顶层实参数。"""
    out = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith('I18n.t(', i):
            before = text[i - 1] if i else ' '
            if not (before.isalnum() or before in '_$.'):
                k = i + 7
                while k < n and text[k] in ' \t\n':
                    k += 1
                if k < n and text[k] == '"':
                    s = k + 1
                    e = s
                    while e < n:
                        if text[e] == '\\':
                            e += 2
                            continue
                        if text[e] == '"':
                            break
                        e += 1
                    key = text[s:e]
                    m = e + 1
                    depth = 0
                    argc = 0
                    seen = False
                    while m < n:
                        ch = text[m]
                        if ch == '"':
                            m += 1
                            while m < n:
                                if text[m] == '\\':
                                    m += 2
                                    continue
                                if text[m] == '"':
                                    break
                                m += 1
                        elif ch == "'":
                            m += 1
                            while m < n:
                                if text[m] == '\\':
                                    m += 2
                                    continue
                                if text[m] == "'":
                                    break
                                m += 1
                        elif ch in '([{':
                            depth += 1
                        elif ch in ')]}':
                            if depth == 0:
                                break
                            depth -= 1
                        elif ch == ',' and depth == 0:
                            seen = True
                            argc += 1
                        m += 1
                    out.append((i, key, argc))
                    i = m
                    continue
        i += 1
    return out


def main():
    roots = sys.argv[1:] or ['portal/src/main/java']
    total = 0
    for root in roots:
        for p in sorted(glob.glob(root + '/**/*.java', recursive=True)):
            text = io.open(p, encoding='utf-8').read()
            bad = []
            for off, key, argc in scan(text):
                ph = [int(x) for x in PH.findall(key)]
                if not ph:
                    continue
                need = max(ph) + 1
                if argc < need:
                    bad.append((text.count('\n', 0, off) + 1, key, need, argc))
            if bad:
                total += len(bad)
                print('\n=== %s (%d) ===' % (p, len(bad)))
                for ln, key, need, argc in bad:
                    print('  %d  needs %d, passes %d  %s' % (ln, need, argc, key[:70]))
    print('\n总计 %d 处' % total)


if __name__ == '__main__':
    main()
