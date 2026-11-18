#!/usr/bin/env python3
"""端到端验证第三方语言包导入：各格式上传、符号键 + keyMap、导出往返。"""
import io
import json
import sys
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:18088'
TOKEN = io.open(r'D:\tmp\gw-i18n-test\data\deploy-token', encoding='utf-8').read().strip()

FAILS = 0


def req(path, method='GET', body=None, token=None, raw=False):
    h = {'Content-Type': 'application/json'}
    if token:
        h['X-GalWorld-Deploy-Token'] = token
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r) as res:
            payload = res.read().decode('utf-8')
            return res.status, (payload if raw else json.loads(payload or 'null'))
    except urllib.error.HTTPError as e:
        payload = e.read().decode('utf-8', 'replace')
        try:
            return e.code, json.loads(payload)
        except Exception:
            return e.code, payload


def check(label, ok, detail=''):
    global FAILS
    print(('PASS  ' if ok else 'FAIL  ') + label + ((' — ' + str(detail)) if detail else ''))
    if not ok:
        FAILS += 1


def install(content, name, extra=None):
    body = {'file': content, 'fileName': name}
    if extra:
        body.update(extra)
    return req('/api/admin/i18n', 'POST', body, TOKEN)


def messages(locale):
    st, data = req('/api/i18n/' + locale)
    return data.get('messages', {}) if st == 200 else {}


def main():
    # 1) PO：源文即键，直接导入
    po = '''msgid ""
msgstr ""
"Language: ko\\n"

msgid "设置"
msgstr "설정"

msgid "没有这个游戏: {0}"
msgstr "게임이 없습니다: {0}"
'''
    st, r = install(po, 'ko.po')
    check('PO 导入', st == 200 and r.get('format') == 'po' and r.get('imported') == 2, r)
    check('PO 翻译生效', messages('ko').get('设置') == '설정')

    # 2) XLIFF
    xlf = '''<?xml version="1.0" encoding="UTF-8"?>
<xliff version="1.2"><file target-language="ko"><body>
  <trans-unit id="1"><source>功能开关</source><target>기능 스위치</target></trans-unit>
</body></file></xliff>
'''
    st, r = install(xlf, 'ko.xlf')
    check('XLIFF 导入', st == 200 and r.get('format') == 'xliff', r)

    # 3) CSV
    csv = 'source,target\n导出,내보내기\n'
    st, r = install(csv, 'ko.csv')
    check('CSV 导入', st == 200 and r.get('format') == 'csv' and r.get('imported') == 1, r)

    # 4) properties 无 keyMap：应报错并提示 keyMap
    props = 'nav.library=\\uc124\\uc815\n'
    st, r = install(props, 'ko.properties')
    check('properties 无 keyMap 被拒绝并提示', st == 400 and 'keyMap' in str(r), r)

    # 5) properties 带 keyMap
    st, r = install(props, 'ko.properties', {'keyMap': {'nav.library': '设置'}})
    check('properties 带 keyMap 成功', st == 200 and r.get('imported') == 1, r)

    # 6) Android strings.xml + keyMap（真实源文键，否则会被跳过）
    android = '''<?xml version="1.0" encoding="utf-8"?>
<resources><string name="nav_library">게임 라이브러리</string></resources>
'''
    st, r = install(android, 'ko-android.xml',
                    {'id': 'ko-android', 'keyMap': {'nav_library': '← 游戏库'}})
    check('Android XML 带 keyMap 成功', st == 200 and r.get('imported') == 1, r)

    # 7) 占位符不一致要报出来（用真实存在的带占位符源文）
    bad = 'msgid "Release 不存在: {0}"\nmsgstr "릴리스 없음: {1}"\n'
    st, r = install(bad, 'ko-ph.po', {'id': 'ko-ph'})
    check('占位符不一致被报告',
          st == 200 and r.get('placeholderMismatches') == ['Release 不存在: {0}'], r)

    # 8) 导出再导入往返
    st, blob = req('/api/admin/i18n/ko/export?format=po', token=TOKEN, raw=True)
    check('导出 PO', st == 200 and 'msgid' in blob, st)
    st, r = install(blob, 'ko2.po', {'id': 'ko2'})
    check('导出的 PO 能再导入', st == 200 and r.get('imported', 0) > 0, r)

    st, blob = req('/api/admin/i18n/ko/export?format=xliff', token=TOKEN, raw=True)
    check('导出 XLIFF', st == 200 and '<xliff' in blob, st)

    st, blob = req('/api/admin/i18n/ko/export?format=csv', token=TOKEN, raw=True)
    check('导出 CSV', st == 200 and blob.startswith('source,target'), st)

    st, blob = req('/api/admin/i18n/ko/export?format=properties', token=TOKEN, raw=True)
    check('导出 properties', st == 200 and '=' in blob, st)

    # 9) 源文清单与格式说明
    st, r = req('/api/admin/i18n/source-keys', token=TOKEN)
    check('源文清单可读', st == 200 and r.get('count', 0) > 1000, r.get('count'))
    st, r = req('/api/admin/i18n/formats')
    check('格式说明列出 8 种导入格式', st == 200 and len(r.get('import', [])) == 8, len(r.get('import', [])))

    # 10) 未识别格式
    st, r = install('binary\x01\x02', 'ko.bin')
    check('无法识别的格式报错', st == 400, r)

    # 清理
    for lid in ['ko', 'ko2', 'ko-android', 'ko-ph']:
        req('/api/admin/i18n/' + lid, 'DELETE', token=TOKEN)

    print('\n%s' % ('全部通过' if FAILS == 0 else '%d 项失败' % FAILS))
    sys.exit(1 if FAILS else 0)


if __name__ == '__main__':
    main()
