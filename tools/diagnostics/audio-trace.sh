#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 跟踪游戏进程的 DirectSound / ALSA 调用，定位不出声原因
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)
G=sample-game
D="$HOME/galworld/games/$G"
export WINEPREFIX="$D/wineprefix"
export WINEDLLOVERRIDES="winecoreaudio.drv=d;winepulse.drv=d"
export ALSA_CONFIG_PATH="$HOME/galworld/data/alsa-$G.conf"
export PULSE_SERVER="unix:/run/user/$(id -u)/pulse/native"
export LIBGL_ALWAYS_SOFTWARE=1
export LANG=C.UTF-8
export DISPLAY=:9

for p in $(ps -eo pid,args | awk '/sample-game\.exe|winedbg/ && !/awk/ {print $1}'); do kill -9 "$p" 2>/dev/null; done
WINEPREFIX="$WINEPREFIX" wineserver -k 2>/dev/null
for p in $(ps -eo pid,args | awk '/Xvfb :9 / && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
sleep 2
rm -f /tmp/.X9-lock /tmp/.X11-unix/X9
setsid Xvfb :9 -screen 0 1280x720x24 -nolisten tcp >/dev/null 2>&1 </dev/null &
for _ in $(seq 1 40); do DISPLAY=:9 xdpyinfo >/dev/null 2>&1 && break; sleep 0.25; done

cd "$D"
WINEDEBUG=+dsound,+mmdevapi,+alsa timeout 25 wine sample-game.exe >/tmp/audio-trace.log 2>&1 &
sleep 18
echo "=== 播放端状态（游戏运行中）"
for d in 0 1; do for s in 0 1; do printf "p%d/%d: " "$d" "$s"; head -1 "/proc/asound/card0/pcm${d}p/sub${s}/status" 2>/dev/null; done; done
sleep 8
WINEPREFIX="$WINEPREFIX" wineserver -k 2>/dev/null

echo "=== DirectSound / MMDevAPI 流创建"
grep -iE "DirectSoundCreate|CreateSoundBuffer|dsound.*Create|render.*stream|mmdevapi.*create|AudioClient|Initialize" /tmp/audio-trace.log | head -15
echo "=== ALSA 打开结果"
grep -iE "alsa_try_open|snd_pcm_open|failed to open|Device or resource" /tmp/audio-trace.log | head -15
