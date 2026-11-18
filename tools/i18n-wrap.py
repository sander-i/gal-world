#!/usr/bin/env python3
"""把 Java 源码里的中文字符串字面量包进 I18n.t()。

整文件级处理，因此跨行的 `+` 连接链也能合并：
    "中文: " + x
        + " 个"        ->  I18n.t("中文: {0} 个", x)
    "中文"             ->  I18n.t("中文")

不处理：日志调用行（log.info/warn/error/debug）、注释、已有的 I18n.t(、
含 {n} 占位符的字面量（String.format 写法）。

命令脚本文件（DeployCommands 等）不要用本脚本：那里的中文是 shell / PowerShell
输出文本，包进 I18n.t 会破坏脚本本身。
"""
import io
import re
import sys

CJK = re.compile(r'[一-龥]')
LOG_CALL = re.compile(r'\b(log|logger)\.\w+\(')


def scan_literals(text):
    """整文件扫描字符串字面量，返回 [(start, end)]；跳过 // 与 /* */ 注释。"""
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
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            spans.append((i, j + 1))
            i = j + 1
            continue
        i += 1
    return spans


def split_plus(expr):
    parts, depth, buf, i, n = [], 0, [], 0, len(expr)
    while i < n:
        c = expr[i]
        if c == '"':
            j = i + 1
            while j < n:
                if expr[j] == '\\':
                    j += 2
                    continue
                if expr[j] == '"':
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


def wrap_literals(expr):
    """表达式里的中文字面量各自包裹；已有 I18n.t( 的跳过。"""
    for s, e in reversed(scan_literals(expr)):
        if not CJK.search(expr[s:e]):
            continue
        if expr[:s].rstrip().endswith('I18n.t('):
            continue
        expr = expr[:s] + 'I18n.t(' + expr[s:e] + ')' + expr[e:]
    return expr


def has_top_level(text, chars):
    """判断表达式顶层（括号外）是否含指定字符之一。"""
    depth = 0
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == '"':
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


def convert_chain(chain):
    parts = split_plus(chain)
    if not any(CJK.search(p) for p in parts):
        return None
    if len(parts) == 1:
        p = parts[0]
        if p.startswith('"') and p.endswith('"') and p.count('"') == 2:
            return 'I18n.t(%s)' % p
        return None
    # 含三元表达式的链不合并：合并后分支里的逗号会被当成参数分隔，
    # 语义直接错掉。退化为逐个字面量包裹，行为等价且安全。
    if has_top_level(chain, '?'):
        return None
    tmpl, args = [], []
    for p in parts:
        if p.startswith('"') and p.endswith('"') and p.count('"') == 2:
            if re.search(r'\{\d+\}', p):
                return None
            tmpl.append(p[1:-1])
        else:
            tmpl.append('{%d}' % len(args))
            args.append(wrap_literals(p))
    return 'I18n.t("%s"%s)' % (''.join(tmpl), ''.join(', ' + a for a in args))


def process(path):
    text = io.open(path, encoding='utf-8').read()
    skipped = []
    guard = 0
    while guard < 5000:
        guard += 1
        target = None
        for s, e in scan_literals(text):
            if not CJK.search(text[s:e]):
                continue
            if text[:s].rstrip().endswith('I18n.t('):
                continue
            # 日志调用行内的字面量不动
            line_start = text.rfind('\n', 0, s) + 1
            line_end = text.find('\n', e)
            line = text[line_start:line_end if line_end >= 0 else len(text)]
            if LOG_CALL.search(line):
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
            text = text[:s] + 'I18n.t(' + text[s:e] + ')' + text[e:]
            continue
        text = text[:l] + indent + converted + text[r:]
    io.open(path, 'w', encoding='utf-8').write(text)
    for s, e in scan_literals(text):
        if CJK.search(text[s:e]) and not text[:s].rstrip().endswith('I18n.t('):
            line_no = text.count('\n', 0, s) + 1
            skipped.append((line_no, text[s:e][:80]))
    return skipped


if __name__ == '__main__':
    for p in sys.argv[1:]:
        for idx, msg in process(p):
            print('%s:%d: %s' % (p, idx, msg))
