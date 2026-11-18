#!/usr/bin/env python3
"""在已部署的实例上验证市场语言包：上传 .po / XLIFF / CSV，再查翻译。

用法：python3 tools/i18n-e2e-market.py <base-url> <deploy-token> [--insecure] [--url-install <public-url>]

`--url-install` 额外验证「从地址安装」：传一个公网可访问的语言包直链
（如 GitHub raw 上的 .po），后端拉取后应得到与上传相同的导入结果。
不给该参数时只验证默认的内网地址拒绝逻辑。

只读 + 安装/删除语言包，不改其它配置。
"""
import io
import json
import ssl
import sys
import urllib.error
import urllib.request

FAILS = 0


def make_ctx(insecure):
    if not insecure:
        return None
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def check(label, ok, detail=''):
    global FAILS
    print(('PASS  ' if ok else 'FAIL  ') + label + ((' — ' + str(detail)[:200]) if detail else ''))
    if not ok:
        FAILS += 1


def main():
    base = sys.argv[1].rstrip('/')
    token = sys.argv[2]
    ctx = make_ctx('--insecure' in sys.argv)

    def req(path, method='GET', body=None, auth=False, raw=False):
        h = {'Content-Type': 'application/json'}
        if auth:
            h['X-GalWorld-Deploy-Token'] = token
        data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
        r = urllib.request.Request(base + path, data=data, headers=h, method=method)
        try:
            with urllib.request.urlopen(r, context=ctx, timeout=30) as res:
                payload = res.read().decode('utf-8')
                return res.status, (payload if raw else json.loads(payload or 'null'))
        except urllib.error.HTTPError as e:
            payload = e.read().decode('utf-8', 'replace')
            try:
                return e.code, json.loads(payload)
            except Exception:
                return e.code, payload

    def install(content, name, extra=None):
        body = {'file': content, 'fileName': name}
        if extra:
            body.update(extra)
        return req('/api/admin/i18n', 'POST', body, auth=True)

    def msgs(locale):
        st, data = req('/api/i18n/' + locale)
        return data.get('messages', {}) if st == 200 else {}

    st, listing = req('/api/i18n')
    check('语言清单可读', st == 200, st)
    ids = [x['id'] for x in listing.get('languages', [])]
    check('内置四语言在位', set(ids) >= {'en', 'zh-CN', 'zh-TW', 'ja'}, ids)

    # 1) 德语 PO
    de = io.open('tmp-i18n/market/de.po', encoding='utf-8').read()
    st, r = install(de, 'de.po')
    check('德语 .po 导入', st == 200 and r.get('format') == 'po' and r.get('imported', 0) > 30, r)
    de_msgs = msgs('de')
    check('德语翻译生效（设置）', de_msgs.get('设置') == 'Einstellungen', de_msgs.get('设置'))
    check('德语翻译生效（开始游戏）', de_msgs.get('开始游戏') == 'Spiel starten', de_msgs.get('开始游戏'))
    check('德语占位符保留', de_msgs.get('没有这个游戏: {0}') == 'Spiel nicht gefunden: {0}',
          de_msgs.get('没有这个游戏: {0}'))
    check('德语未翻译键回退中文', de_msgs.get('这条文案不存在') is None)

    # 2) 韩语 XLIFF
    ko = io.open('tmp-i18n/market/ko.xlf', encoding='utf-8').read()
    st, r = install(ko, 'ko.xlf')
    check('韩语 .xlf 导入', st == 200 and r.get('format') == 'xliff' and r.get('imported', 0) > 15, r)
    ko_msgs = msgs('ko')
    check('韩语翻译生效（设置）', ko_msgs.get('设置') == '설정', ko_msgs.get('设置'))
    check('韩语翻译生效（运行中）', ko_msgs.get('运行中') == '실행 중', ko_msgs.get('运行中'))

    # 3) 俄语 CSV
    ru = io.open('tmp-i18n/market/ru.csv', encoding='utf-8').read()
    st, r = install(ru, 'ru.csv')
    check('俄语 .csv 导入', st == 200 and r.get('format') == 'csv' and r.get('imported', 0) > 10, r)
    ru_msgs = msgs('ru')
    check('俄语翻译生效（设置）', ru_msgs.get('设置') == 'Настройки', ru_msgs.get('设置'))

    # 4) 清单里出现三种新语言
    st, listing = req('/api/i18n')
    ids = [x['id'] for x in listing.get('languages', [])]
    check('清单含 de/ko/ru', {'de', 'ko', 'ru'} <= set(ids), ids)

    # 5) 导出往返：德语导出 PO 再导入成 de2
    st, blob = req('/api/admin/i18n/de/export?format=po', auth=True, raw=True)
    check('德语导出 PO', st == 200 and 'msgid "设置"' in blob, st)
    st, r = install(blob, 'de2.po', {'id': 'de2'})
    check('导出的 PO 能再导入', st == 200 and r.get('imported', 0) > 30, r)

    # 6) 从地址安装：内网地址必须被拒
    st, r = req('/api/admin/i18n', 'POST',
                {'url': 'http://127.0.0.1:1/ko.po', 'id': 'ko2'}, auth=True)
    msg = r.get('message', '') if isinstance(r, dict) else str(r)
    check('内网地址被拒', st == 400 and ('内网' in msg or 'private' in msg.lower()), (st, msg))

    st, r = req('/api/admin/i18n', 'POST', {'url': 'file:///etc/passwd'}, auth=True)
    msg = r.get('message', '') if isinstance(r, dict) else str(r)
    check('非 http 协议被拒', st == 400, (st, msg))

    # 7) 从地址安装：公网直链（需要 --url-install 才验证）
    url_idx = sys.argv.index('--url-install') if '--url-install' in sys.argv else -1
    if url_idx > 0 and url_idx + 1 < len(sys.argv):
        pub = sys.argv[url_idx + 1]
        st, r = req('/api/admin/i18n', 'POST', {'url': pub}, auth=True)
        check('从公网地址安装', st == 200 and r.get('imported', 0) > 0, (st, r))
        if st == 200:
            check('返回来源地址', r.get('sourceUrl') == pub, r.get('sourceUrl'))
            check('返回文件名', bool(r.get('fileName')), r.get('fileName'))
            req('/api/admin/i18n/' + r.get('id', ''), 'DELETE', auth=True)

    # 8) 请求头语言影响后端异常文案
    def err_for(locale):
        h = {'X-GalWorld-Locale': locale}
        r = urllib.request.Request(base + '/api/admin/settings', headers=h)
        try:
            urllib.request.urlopen(r, context=ctx, timeout=15)
            return ''
        except urllib.error.HTTPError as e:
            try:
                return json.loads(e.read().decode('utf-8')).get('message', '')
            except Exception:
                return ''
    check('德语请求头得到德语错误', 'Token' in err_for('de') or 'token' in err_for('de').lower(),
          err_for('de'))
    check('韩语请求头得到韩语错误', '토큰' in err_for('ko'), err_for('ko'))
    check('俄语请求头得到俄语错误', 'токен' in err_for('ru').lower(), err_for('ru'))

    # 清理
    for lid in ['de', 'ko', 'ru', 'de2']:
        req('/api/admin/i18n/' + lid, 'DELETE', auth=True)

    print('\n%s' % ('全部通过' if FAILS == 0 else '%d 项失败' % FAILS))
    sys.exit(1 if FAILS else 0)


if __name__ == '__main__':
    main()
