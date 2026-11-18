#!/usr/bin/env python3
"""把合并后的键清单切成若干分片，便于分批翻译。

输出：tmp-i18n/keys.txt 与 tmp-i18n/keys_<n>.txt
"""
import glob
import io
import re

CJK = re.compile('[一-龥]')
BACKSLASH = chr(92)


def scan_calls(src):
    keys = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            while i < n and src[i] != '\n':
                i += 1
            continue
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c in ('"', "'", '`'):
            q = c
            j = i + 1
            while j < n:
                if src[j] == BACKSLASH:
                    j += 2
                    continue
                if src[j] == q:
                    break
                j += 1
            i = j + 1
            continue
        if c == 't' and i + 1 < n and src[i + 1] == '(':
            prev = src[i - 1] if i > 0 else ''
            if not (prev.isalnum() or prev in '_.$'):
                k = i + 2
                while k < n and src[k] in ' \t\n':
                    k += 1
                if k < n and src[k] in ('"', "'", '`'):
                    q = src[k]
                    j = k + 1
                    while j < n:
                        if src[j] == BACKSLASH:
                            j += 2
                            continue
                        if src[j] == q:
                            break
                        j += 1
                    keys.append(src[k + 1:j])
                    i = j + 1
                    continue
        i += 1
    return keys


def scan_java_t(src):
    """抽取 I18n.t( 后第一个双引号字面量；用字符扫描避免正则转义地狱。"""
    keys = []
    i, n = 0, len(src)
    while i < n:
        if src.startswith('I18n.t(', i):
            k = i + len('I18n.t(')
            while k < n and src[k] in ' \t\n':
                k += 1
            if k < n and src[k] == '"':
                j = k + 1
                while j < n:
                    if src[j] == BACKSLASH:
                        j += 2
                        continue
                    if src[j] == '"':
                        break
                    j += 1
                keys.append(src[k + 1:j])
                i = j + 1
                continue
        i += 1
    return keys


def main():
    fe = set()
    for p in (glob.glob('portal/frontend/src/**/*.vue', recursive=True)
              + glob.glob('portal/frontend/src/*.js', recursive=True)):
        if p.endswith('i18n.js'):
            continue
        fe.update(scan_calls(io.open(p, encoding='utf-8').read()))
    be = set()
    for p in glob.glob('portal/src/main/java/**/*.java', recursive=True):
        be.update(scan_java_t(io.open(p, encoding='utf-8').read()))
    keys = sorted(k for k in (fe | be) if CJK.search(k))
    io.open('tmp-i18n/keys.txt', 'w', encoding='utf-8').write('\n'.join(keys) + '\n')
    step = 300
    for i in range(0, len(keys), step):
        io.open('tmp-i18n/keys_%d.txt' % (i // step), 'w', encoding='utf-8').write(
            '\n'.join(keys[i:i + step]) + '\n')
    print(len(keys), 'keys,', (len(keys) + step - 1) // step, 'chunks')


if __name__ == '__main__':
    main()
