#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# Gal World 全栈自检
#
# 自启策略：pulse 与 portal 开机自启，游戏会话默认不启动。
# 因此这里不再假设游戏在跑 —— 只在确实运行时检查其音频链路。
set +euo pipefail
export XDG_RUNTIME_DIR="/run/user/$(id -u)"

PASS=0; FAIL=0; SKIP=0
ok()   { echo "  [PASS] $1"; PASS=$((PASS+1)); }
bad()  { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
skip() { echo "  [SKIP] $1"; SKIP=$((SKIP+1)); }

echo "== 基础设施服务"
for s in galworld-pulse galworld-portal; do
  st=$(systemctl --user is-active "$s.service" 2>/dev/null)
  [ "$st" = "active" ] && ok "$s active" || bad "$s = $st"
done

echo "== 游戏单元自启策略（应为 disabled）"
for s in $(ls ~/galworld/games 2>/dev/null); do
  en=$(systemctl --user is-enabled "galworld-game@$s.service" 2>/dev/null)
  [ "$en" = "disabled" ] && ok "$s 未开机自启" || bad "$s = $en（应 disabled）"
done

echo "== 门户"
code=$(curl -sk --max-time 15 -o /dev/null -w '%{http_code}' https://127.0.0.1:8088/api/health)
[ "$code" = "200" ] && ok "health 200" || bad "health $code"
games=$(curl -sk --max-time 15 https://127.0.0.1:8088/api/games)
# 全新实例游戏库是空的：`[]` 是正常状态，不能算失败；只有响应不是 JSON 数组才算异常
case "$games" in
  *'"id":'*) ok "API 返回游戏" ;;
  *'[]'*)    skip "游戏库为空（新装实例正常）" ;;
  *)         bad "API 无游戏" ;;
esac
curl -sk --max-time 15 -o /dev/null -w '%{http_code}' https://127.0.0.1:8088/settings \
  | grep -q 200 && ok "设置页可达" || bad "设置页"

echo "== 串流端口（仅对正在运行的游戏检查）"
running=0
for g in $(ls ~/galworld/games 2>/dev/null); do
  st=$(systemctl --user is-active "galworld-game@$g.service" 2>/dev/null)
  if [ "$st" = "active" ]; then
    port=$(grep -h '^PORT=' "$HOME/galworld/games/$g/game.env" 2>/dev/null | cut -d= -f2)
    c=$(curl -sk --max-time 10 -o /dev/null -w '%{http_code}' "https://127.0.0.1:$port/")
    [ "$c" = "401" ] && ok "$g :$port 401(basic auth)" || bad "$g :$port $c"
    running=$((running+1))
  fi
done
[ "$running" -eq 0 ] && skip "没有运行中的游戏会话（符合默认不自启策略）"

echo "== 音频链路（仅对正在运行的会话）"
for g in $(ls ~/galworld/games 2>/dev/null); do
  st=$(systemctl --user is-active "galworld-game@$g.service" 2>/dev/null)
  [ "$st" = "active" ] || continue
  PID=$(pgrep -f "games/$g/.*\.exe" | head -1)
  if [ -n "${PID:-}" ]; then
    grep -oP '^Groups:\s+\K.*' "/proc/$PID/status" | tr ' ' '\n' | grep -qx 29 \
      && ok "$g wine 有 audio 组" || bad "$g wine 缺 audio 组"
  else
    bad "$g 无游戏进程"
  fi
done

# loopback 数量应等于「有过会话」的游戏数，不该有残留或缺失。
# 注意 failed 状态的单元同样会留下音频链（进程被杀但模块没卸），所以不能只数 active。
expected=0
for g in $(ls ~/galworld/games 2>/dev/null); do
  st=$(systemctl --user is-active "galworld-game@$g.service" 2>/dev/null)
  # 只要不是从未启动（inactive 且无标记），就认为会占用一条 loopback
  if [ "$st" = "active" ] || [ "$st" = "failed" ] \
     || [ -f "$HOME/galworld/data/session-intent/$g.started" ]; then
    expected=$((expected+1))
  fi
done
actual=$(pactl list short modules 2>/dev/null | grep -c module-loopback | head -1)
actual=${actual:-0}
[ "$actual" -eq "$expected" ] && ok "loopback $actual 条（= 有链会话数）" \
  || bad "loopback $actual 条，期望 $expected 条（可能有残留或缺失）"

# 采集源不该有已删除游戏的残留
stale=$(pactl list short sources 2>/dev/null | grep -oE 'gal_[a-z0-9]+_' | sort -u \
  | sed 's/gal_//;s/_//' | while read -r id; do
      [ -d "$HOME/galworld/games/$id" ] || echo "$id"
    done | tr '\n' ' ')
[ -z "${stale// /}" ] && ok "无残留采集源" || bad "残留采集源: $stale"

echo "== 快照"
latest=$(readlink -f ~/galworld/data/backups/latest 2>/dev/null)
# 新装实例还没有任何快照，属正常状态，不算失败
if [ -z "$latest" ] || [ ! -d "$latest" ]; then
  skip "尚无快照（新装实例正常）"
elif grep -q '^health=OK' "$latest/MANIFEST" 2>/dev/null; then
  ok "latest 健康 ($(basename "$latest"))"
else
  bad "latest 不健康"
fi

echo
echo "PASS=$PASS FAIL=$FAIL SKIP=$SKIP"
[ "$FAIL" -eq 0 ] || exit 1
