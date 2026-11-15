#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 对比：脚本直跑 vs systemd 服务启动，抓 wine 进程环境的全量 diff
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)
G=sample-game
D="$HOME/galworld/games/$G"

echo "=== 1. systemd 服务启动 ==="
systemctl --user stop "galworld-game@$G.service" 2>/dev/null
systemctl --user reset-failed "galworld-game@$G.service" 2>/dev/null
for p in $(ps -eo pid,args | awk '/run-game\.sh|sample-game\.exe|winedbg|venv\/bin\/selkies/ && !/awk/ {print $1}'); do kill -9 "$p" 2>/dev/null; done
WINEPREFIX="$D/wineprefix" wineserver -k 2>/dev/null
for p in $(ps -eo pid,args | awk '/Xvfb :2 / && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
sleep 3; rm -f /tmp/.X2-lock /tmp/.X11-unix/X2

systemctl --user start "galworld-game@$G.service"
sleep 32
PID=$(pgrep -f "games/$G/.*\.exe" | head -1)
echo "service wine pid=${PID:-none}"
[[ -n "${PID:-}" ]] && tr '\0' '\n' < /proc/$PID/environ | sort > /tmp/env-service.txt
echo "playDev=$(head -1 /proc/asound/card0/pcm0p/sub0/status 2>/dev/null|sed 's/state: //')"

echo "=== 2. 直跑脚本 ==="
systemctl --user stop "galworld-game@$G.service" 2>/dev/null
for p in $(ps -eo pid,args | awk '/run-game\.sh|sample-game\.exe|winedbg|venv\/bin\/selkies/ && !/awk/ {print $1}'); do kill -9 "$p" 2>/dev/null; done
WINEPREFIX="$D/wineprefix" wineserver -k 2>/dev/null
for p in $(ps -eo pid,args | awk '/Xvfb :2 / && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
sleep 3; rm -f /tmp/.X2-lock /tmp/.X11-unix/X2

setsid nohup "$HOME/galworld/bin/run-game.sh" "$G" >/dev/null 2>&1 </dev/null &
sleep 32
PID2=$(pgrep -f "games/$G/.*\.exe" | head -1)
echo "direct wine pid=${PID2:-none}"
[[ -n "${PID2:-}" ]] && tr '\0' '\n' < /proc/$PID2/environ | sort > /tmp/env-direct.txt
echo "playDev=$(head -1 /proc/asound/card0/pcm0p/sub0/status 2>/dev/null|sed 's/state: //')"

echo "=== 3. 环境差异 ==="
diff -u /tmp/env-service.txt /tmp/env-direct.txt | grep -E "^[+-]" | grep -vE "^(\+\+\+|---)" | head -30

echo "=== 4. systemd 单元内容 ==="
systemctl --user cat "galworld-game@$G.service" | head -25
