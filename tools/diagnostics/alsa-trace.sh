#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 复现 run-game.sh 的确切环境，抓 wine 的 ALSA 初始化日志
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)
GAME=sample-game
DIR="$HOME/galworld/games/$GAME"
export WINEPREFIX="$DIR/wineprefix"
export WINEDEBUG=+alsa
export WINEDLLOVERRIDES="winecoreaudio.drv=d;winepulse.drv=d"
export LIBGL_ALWAYS_SOFTWARE=1
export LANG=C.UTF-8
export PULSE_SERVER="unix:/run/user/$(id -u)/pulse/native"
export ALSA_CONFIG_PATH="$HOME/galworld/data/alsa-$GAME.conf"
export DISPLAY=:8

echo "--- 使用的 ALSA 配置:"
cat "$ALSA_CONFIG_PATH"

for p in $(ps -eo pid,args | awk '/sample-game\.exe|winedbg/ && !/awk/ {print $1}'); do kill -9 "$p" 2>/dev/null; done
WINEPREFIX="$WINEPREFIX" wineserver -k 2>/dev/null
for p in $(ps -eo pid,args | awk '/Xvfb :8 / && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
sleep 2
rm -f /tmp/.X8-lock /tmp/.X11-unix/X8
setsid Xvfb :8 -screen 0 1280x720x24 -nolisten tcp >/dev/null 2>&1 </dev/null &
for _ in $(seq 1 40); do DISPLAY=:8 xdpyinfo >/dev/null 2>&1 && break; sleep 0.25; done

cd "$DIR"
timeout 20 wine sample-game.exe >/tmp/alsa-trace.log 2>&1
wineserver -k 2>/dev/null
sleep 1
echo "--- ALSA 关键行:"
grep -iE "alsa_try_open|snd_pcm_open|default|plughw|no devices|failed" /tmp/alsa-trace.log | head -20
echo "--- 播放端状态:"
for d in 0 1; do printf "dev%d: " "$d"; head -1 "/proc/asound/card0/pcm${d}p/sub0/status" 2>/dev/null; done
