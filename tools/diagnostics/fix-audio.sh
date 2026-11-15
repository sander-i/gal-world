#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 修复 winealsa 设备缓存：用完整系统 ALSA 配置跑一次 wine，让 Wine 枚举并缓存
# loopback 的 plughw 设备；之后游戏再用极简配置（避免 pulse 崩溃）就能正常出声。
set +euo pipefail

export XDG_RUNTIME_DIR=/run/user/$(id -u)
GAME=sample-game
DIR="$HOME/galworld/games/$GAME"
export WINEPREFIX="$DIR/wineprefix"
export WINEDLLOVERRIDES="winecoreaudio.drv=d;winepulse.drv=d"
export PULSE_SERVER="unix:/run/user/$(id -u)/pulse/native"
export WINEDEBUG=-all
unset ALSA_CONFIG_PATH
export DISPLAY=:6

echo "=== 清理"
for p in $(ps -eo pid,args | awk '/sample-game\.exe|winedbg|winecfg/ && !/awk/ {print $1}'); do kill -9 "$p" 2>/dev/null; done
WINEPREFIX="$WINEPREFIX" wineserver -k 2>/dev/null
for p in $(ps -eo pid,args | awk '/Xvfb :6 / && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
sleep 2
rm -f /tmp/.X6-lock /tmp/.X11-unix/X6

echo "=== 启动 Xvfb :6"
setsid Xvfb :6 -screen 0 1280x720x24 -nolisten tcp >/dev/null 2>&1 </dev/null &
for _ in $(seq 1 40); do DISPLAY=:6 xdpyinfo >/dev/null 2>&1 && break; sleep 0.25; done
DISPLAY=:6 xdpyinfo >/dev/null 2>&1 && echo "display ok" || { echo "display FAILED"; exit 1; }

echo "=== 删除旧缓存"
wine reg delete "HKCU\\Software\\Wine\\Drivers\\winealsa.drv" /f >/dev/null 2>&1

echo "=== 用完整 ALSA 配置触发枚举（跑 winecfg 20s）"
cd "$DIR"
timeout 20 wine winecfg >/tmp/winecfg.log 2>&1
wineserver -k 2>/dev/null
sleep 2
echo "--- 缓存的设备："
wine reg query "HKCU\\Software\\Wine\\Drivers\\winealsa.drv\\devices" 2>&1 | head -10

echo "=== 用极简配置跑游戏 25s"
cat > /tmp/gal-min.conf <<'EOF'
pcm.!default {
    type hw
    card 0
    device 0
    subdevice 0
}
ctl.!default {
    type hw
    card 0
}
EOF
ALSA_CONFIG_PATH=/tmp/gal-min.conf timeout 25 wine sample-game.exe >/tmp/game-min.log 2>&1 &
GPID=$!
sleep 12
echo "--- 播放端状态："
for d in 0 1; do for s in 0 1; do
  printf "dev%d/sub%d: " "$d" "$s"; head -1 "/proc/asound/card0/pcm${d}p/sub${s}/status" 2>/dev/null
done; done
wait $GPID
echo "--- 崩溃/内存：faults=$(grep -c 'page fault' /tmp/game-min.log) mmap=$(grep -c 'mmap() failed' /tmp/game-min.log)"
