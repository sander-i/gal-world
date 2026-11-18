#!/usr/bin/env python3
"""找后端 Java 里没走 I18n.t() 的中文字符串字面量。

跳过的：注释、日志（log.*）、I18n.t / I18n.translate 调用内的实参、
异常包装里只作诊断的 message。

用法：python3 tools/i18n-backend-cjk.py [java 根目录]
"""
import glob
import io
import sys

CJK_MIN, CJK_MAX = 0x4e00, 0x9fa5
QUOTE = '"'


def has_cjk(s):
    return any(CJK_MIN <= ord(c) <= CJK_MAX for c in s)


def string_spans(text):
    """返回 [(start, end)]，跳过注释，end 是右引号之后。"""
    spans = []
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
        if c == QUOTE:
            j = i + 1
            while j < n:
                if text[j] == chr(92):
                    j += 2
                    continue
                if text[j] == QUOTE:
                    break
                j += 1
            spans.append((i, j + 1))
            i = j + 1
            continue
        i += 1
    return spans


def line_of(text, off):
    return text.count('\n', 0, off) + 1


def main():
    roots = sys.argv[1:] or ['portal/src/main/java']
    total = 0
    for root in roots:
        for p in sorted(glob.glob(root + '/**/*.java', recursive=True)):
            text = io.open(p, encoding='utf-8').read()
            hits = []
            for s, e in string_spans(text):
                lit = text[s:e]
                if not has_cjk(lit):
                    continue
                # 同一行里在 I18n.t( 之后：属于翻译调用，跳过
                line_start = text.rfind('\n', 0, s) + 1
                line_end = text.find('\n', e)
                if line_end < 0:
                    line_end = len(text)
                line = text[line_start:line_end]
                if 'I18n.t(' in line or 'I18n.translate(' in line:
                    continue
                # 日志与异常诊断用的 message 不影响界面
                if any(k in line for k in ('log.', 'Logger', 'System.err', 'System.out')):
                    continue
                hits.append((line_of(text, s), lit[:80]))
            if hits:
                total += len(hits)
                print('\n=== %s (%d) ===' % (p, len(hits)))
                for ln, lit in hits:
                    print('  %d  %s' % (ln, lit))
    print('\n总计 %d 处' % total)


if __name__ == '__main__':
    main()
