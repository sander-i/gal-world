#!/usr/bin/env python3
"""端到端验证 i18n：语言接口、按请求头返回的异常语言、外部包安装/删除。"""
import io
import json
import sys
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:18088'


def req(path, method='GET', body=None, headers=None):
    data = None
    h = {'Content-Type': 'application/json'}
    if headers:
        h.update(headers)
    if body is not None:
        data = json.dumps(body).encode('utf-8')
    r = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r) as res:
            return res.status, json.loads(res.read().decode('utf-8') or 'null')
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', 'replace')
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def check(label, ok, detail=''):
    print(('PASS  ' if ok else 'FAIL  ') + label + ((' — ' + str(detail)) if detail else ''))
    return ok


def main():
    fails = 0

    st, data = req('/api/i18n')
    ids = [x['id'] for x in data.get('languages', [])]
    fails += not check('四套内置语言', ids == ['en', 'zh-CN', 'zh-TW', 'ja'], ids)
    fails += not check('默认语言 en', data.get('defaultLocale') == 'en', data.get('defaultLocale'))

    st, en = req('/api/i18n/en')
    # 条数随文案增减变化：只要求覆盖源码抽取出的全部键，不写死数字
    keys = [k for k in io.open('tmp-i18n/keys.txt', encoding='utf-8').read().split('\n') if k]
    missing = [k for k in keys if k not in en['messages']]
    fails += not check('英文包覆盖全部键', not missing, missing[:3])
    fails += not check('英文翻译正确', en['messages']['设置'] == 'Settings',
                       en['messages']['设置'])

    st, ja = req('/api/i18n/ja')
    fails += not check('日文翻译正确', ja['messages']['设置'] == '設定', ja['messages']['设置'])

    st, tw = req('/api/i18n/zh-TW')
    fails += not check('繁中翻译正确', tw['messages']['设置'] == '設定', tw['messages']['设置'])

    st, cn = req('/api/i18n/zh-CN')
    fails += not check('简中回退原文', cn['messages']['设置'] == '设置', cn['messages']['设置'])

    st, body = req('/api/i18n/xx')
    fails += not check('未知语言 404', st == 404, st)

    # 写接口：部署令牌由服务端随机生成，未配置时读不到，这里只验证拒绝路径
    st, body = req('/api/admin/i18n', method='POST',
                   body={'id': 'ko', 'name': '한국어', 'messages': {'设置': '설정'}})
    fails += not check('无令牌安装被拒绝', st == 403, st)

    # 请求头语言：异常消息应跟随请求语言
    st, body = req('/api/admin/settings', headers={'X-GalWorld-Locale': 'en'})
    fails += not check('英文请求头得到英文错误', 'deploy token' in str(body).lower(), body)

    st, body = req('/api/admin/settings', headers={'X-GalWorld-Locale': 'zh-CN'})
    fails += not check('中文请求头得到中文错误', '部署令牌' in str(body), body)

    st, body = req('/api/admin/settings', headers={'X-GalWorld-Locale': 'ja'})
    fails += not check('日文请求头得到日文错误', 'デプロイトークン' in str(body), body)

    print('\n%s' % ('全部通过' if fails == 0 else '%d 项失败' % fails))
    sys.exit(1 if fails else 0)


if __name__ == '__main__':
    main()
