#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 干净环境：停掉所有会话，单独跑一次带 +alsa 跟踪的游戏
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)
G=sample-game
D="$HOME/galworld/games/$G"

echo "=== 停止所有会话"
for g in sample-game demo-game; do
  systemctl --user stop "galworld-game@$g.service" 2>/dev/null
  systemctl --user reset-failed "galworld-game@$g.service" 2>/dev/null
done
for p in $(ps -eo pid,args | awk '/sample-game\.exe|game\.exe|winedbg|venv\/bin\/selkies/ && !/awk/ {print $1}'); do kill -9 "$p" 2>/dev/null; done
for g in sample-game demo-game; do WINEPREFIX="$HOME/galworld/games/$g/wineprefix" wineserver -k 2>/dev/null; done
for p in $(ps -eo pid,args | awk '/Xvfb :/ && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
sleep 3
rm -f /tmp/.X*-lock /tmp/.X11-unix/X*

echo "=== 确认设备空闲"
for d in 0 1; do for s in 0 1; do printf "p%d/%d: " "$d" "$s"; head -1 "/proc/asound/card0/pcm${d}p/sub${s}/status" 2>/dev/null; done; done

echo "=== 起显示并跑游戏（+alsa）"
export WINEPREFIX="$D/wineprefix"
export WINEDLLOVERRIDES="winecoreaudio.drv=d;winepulse.drv=d"
export ALSA_CONFIG_PATH="$HOME/galworld/data/alsa-$G.conf"
export PULSE_SERVER="unix:/run/user/$(id -u)/pulse/native"
export LIBGL_ALWAYS_SOFTWARE=1 LANG=C.UTF-8 DISPLAY=:2

setsid Xvfb :2 -screen 0 1280x720x24 -nolisten tcp >/dev/null 2>&1 </dev/null &
for _ in $(seq 1 40); do DISPLAY=:2 xdpyinfo >/dev/null 2>&1 && break; sleep 0.25; done

cd "$D"
WINEDEBUG=+alsa timeout 30 wine sample-game.exe >/tmp/clean-alsa.log 2>&1 &
sleep 20
echo "--- 运行中播放端:"
for d in 0 1; do for s in 0 1; do printf "p%d/%d: " "$d" "$s"; head -1 "/proc/asound/card0/pcm${d}p/sub${s}/status" 2>/dev/null; done; done
sleep 12
wineserver -k 2>/dev/null

echo "--- ALSA 结果:"
grep -E "alsa_try_open|Unable to open|snd_pcm_open|Enumerated device" /tmp/clean-alsa.log | head -12
echo "--- 错误统计:"
echo "busy=$(grep -c 'Device or resource busy' /tmp/clean-alsa.log)"
