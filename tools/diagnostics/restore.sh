#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 启动 sample-game 会话，并在游戏运行中检测音频是否真的流出
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)

echo "=== 重启服务"
for g in sample-game demo-game; do
  systemctl --user stop "galworld-game@$g.service" 2>/dev/null
  systemctl --user reset-failed "galworld-game@$g.service" 2>/dev/null
done
for p in $(ps -eo pid,args | awk '/sample-game\.exe|game\.exe|winedbg|venv\/bin\/selkies/ && !/awk/ {print $1}'); do kill -9 "$p" 2>/dev/null; done
for g in sample-game demo-game; do WINEPREFIX="$HOME/galworld/games/$g/wineprefix" wineserver -k 2>/dev/null; done
for p in $(ps -eo pid,args | awk '/Xvfb :/ && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
sleep 3
rm -f /tmp/.X*-lock /tmp/.X11-unix/X*
rm -f "$HOME"/galworld/data/loop-*.id

~/galworld/bin/galworld start sample-game
~/galworld/bin/galworld start demo-game
sleep 40

echo "=== 状态"
~/galworld/bin/galworld list
echo "--- 播放端:"
for d in 0 1; do for s in 0 1; do printf "p%d/%d: " "$d" "$s"; head -1 "/proc/asound/card0/pcm${d}p/sub${s}/status" 2>/dev/null; done; done

echo "--- 窗口:"
for d in 2 3; do
  printf ":%s " "$d"
  DISPLAY=":$d" xwininfo -root -tree 2>/dev/null | grep -E '"[^"]+":' | grep -v Openbox | head -1
done
