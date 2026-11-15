#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# M4 验收自检
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)
PASS=0; FAIL=0
ok(){ echo "  [PASS] $1"; PASS=$((PASS+1)); }
bad(){ echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
C(){ curl -sk --max-time 15 "$@"; }

echo "== 服务"
for s in galworld-pulse galworld-portal; do
  [ "$(systemctl --user is-active $s.service 2>/dev/null)" = active ] && ok "$s active" || bad "$s"
done

echo "== 门户基础"
[ "$(C -o /dev/null -w '%{http_code}' https://127.0.0.1:8088/api/health)" = 200 ] && ok "health 200" || bad "health"
C https://127.0.0.1:8088/ | grep -q 'Gal World' && ok "首页可渲染" || bad "首页"
[ "$(C -o /dev/null -w '%{http_code}' https://127.0.0.1:8088/settings)" = 200 ] && ok "设置页路由" || bad "设置页路由"

echo "== M4-1 切换模式"
s=$(C https://127.0.0.1:8088/api/settings)
echo "$s" | grep -q '"switchMode"' && ok "switchMode 可读" || bad "switchMode"
echo "$s" | grep -q '"idleStopMinutes"' && ok "idleStopMinutes 可读" || bad "idleStopMinutes"
C -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  -d '{"switchMode":"bogus"}' https://127.0.0.1:8088/api/settings | grep -q 400 \
  && ok "非法 switchMode 被拒" || bad "非法 switchMode 未拒"

echo "== M4-2 空闲停止"
n=$(C -X POST -H 'Content-Type: application/json' -d '{"idleStopMinutes":"1"}' https://127.0.0.1:8088/api/settings | grep -o '"idleStopMinutes":"1"' || true)
[ -n "$n" ] && ok "idleStopMinutes 可设" || bad "idleStopMinutes 设置失败"
C -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  -d '{"idleStopMinutes":"9999"}' https://127.0.0.1:8088/api/settings | grep -q 400 \
  && ok "越界值被拒" || bad "越界值未拒"
C -X POST -H 'Content-Type: application/json' -d '{"idleStopMinutes":"0"}' https://127.0.0.1:8088/api/settings >/dev/null

echo "== M4-3 崩溃检测"
g=$(C https://127.0.0.1:8088/api/games/sample-game)
echo "$g" | grep -q '"crashed"' && ok "GameView 含 crashed 字段" || bad "缺 crashed 字段"

echo "== M4-5 整站导出"
e=$(C https://127.0.0.1:8088/api/export)
echo "$e" | grep -q '"games"' && ok "导出含 games" || bad "导出缺 games"
echo "$e" | grep -q '"settings"' && ok "导出含 settings" || bad "导出缺 settings"
echo "$e" | grep -q '"tags"' && ok "导出含 tags" || bad "导出缺 tags"

echo "== 游戏库"
C https://127.0.0.1:8088/api/games | grep -q '"id":"sample-game"' && ok "库含 sample-game" || bad "库缺 sample-game"
# 中文参数必须先 URL 编码，否则 curl 在非 UTF-8 locale 下会传错字节
C --get --data-urlencode "tag=汉化" https://127.0.0.1:8088/api/games | grep -q '"id":"sample-game"' \
  && ok "标签筛选" || bad "标签筛选"

echo "== Hero"
[ "$(C -o /dev/null -w '%{http_code}' https://127.0.0.1:8088/api/hero)" = 200 ] && ok "hero 200" || bad "hero"

echo "== 备份脚本"
bash -n ~/galworld/bin/backup.sh && ok "backup.sh 语法" || bad "backup.sh 语法"
grep -q 'portal-export' ~/galworld/bin/backup.sh && ok "backup 含门户导出" || bad "backup 缺门户导出"

echo
echo "PASS=$PASS FAIL=$FAIL"
