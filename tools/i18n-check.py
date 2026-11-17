#!/usr/bin/env python3
"""校验语言包：JSON 合法性、缺失键、多余键、占位符一致性。

用法：python3 tools/i18n-check.py <pack.json> [<pack.json> ...]

- 缺失键：回退到中文原文，不算错误，只统计
- 多余键：拼错或代码里已删除的文案，必须清理
- 占位符不一致：{0} / {1} 数量或编号不同，运行时会显示原始占位符，必须修
"""
import io
import json
import re
import sys

PLACEHOLDER = re.compile(r'\{(\d+)\}')


def load(path):
    return json.load(io.open(path, encoding='utf-8'))


def main():
    keys = [k for k in io.open('tmp-i18n/keys.txt', encoding='utf-8').read().split('\n') if k]
    keyset = set(keys)
    failed = False
    for path in sys.argv[1:]:
        try:
            pack = load(path)
        except Exception as e:
            print('%s: JSON 解析失败: %s' % (path, e))
            failed = True
            continue
        missing = [k for k in keys if k not in pack]
        extra = [k for k in pack if k not in keyset]
        bad = []
        for k, v in pack.items():
            if not isinstance(v, str):
                bad.append((k, 'value is not a string'))
                continue
            src = set(PLACEHOLDER.findall(k))
            dst = set(PLACEHOLDER.findall(v))
            if src != dst:
                bad.append((k, 'placeholders %s -> %s' % (sorted(src), sorted(dst))))
        print('%s: %d keys, missing %d, extra %d, bad %d'
              % (path, len(pack), len(missing), len(extra), len(bad)))
        for k, why in bad[:20]:
            print('  BAD %r: %s' % (k, why))
        for k in extra[:10]:
            print('  EXTRA %r' % k)
        if extra or bad:
            failed = True
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
