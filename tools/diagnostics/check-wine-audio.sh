#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 检测 wine 能否打开 ALSA 播放设备并输出可听数据
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)
export PULSE_SERVER="unix:/run/user/$(id -u)/pulse/native"
G=sample-game
D="$HOME/galworld/games/$G"
export WINEPREFIX="$D/wineprefix"
export WINEDLLOVERRIDES="winecoreaudio.drv=d;winepulse.drv=d"
export ALSA_CONFIG_PATH="$HOME/galworld/data/alsa-$G.conf"

# 1) 先确认 Linux 侧 ALSA 能写
echo "--- Linux aplay → hw:0,0,0"
( timeout 4 aplay -D hw:0,0,0 -f S16_LE -r 44100 -c 2 /dev/urandom >/dev/null 2>&1 ) &
sleep 1
echo -n "playdev0: "; head -1 /proc/asound/card0/pcm0p/sub0/status
echo -n "采集(hw:0,1,0): "
timeout 2 arecord -D hw:0,1,0 -f S16_LE -r 44100 -c 2 -d 1 /tmp/lx.raw >/dev/null 2>&1
python3 -c "
import array
a=array.array('h'); a.frombytes(open('/tmp/lx.raw','rb').read())
print('peak', max((abs(v) for v in a), default=0))"
wait

# 2) wine 侧：用 winemine 之类需要有声音的程序不现实，直接看枚举结果
echo "--- winealsa 枚举"
cd "$D"
WINEDEBUG=+alsa DISPLAY=:2 timeout 12 wine winecfg >/tmp/wc.log 2>&1 &
sleep 6
echo -n "playdev0 (wine运行中): "; head -1 /proc/asound/card0/pcm0p/sub0/status
pkill -f winecfg 2>/dev/null
wineserver -k 2>/dev/null
echo "--- 枚举到的输出设备:"
grep -oE 'Enumerated device: L"Out: [^"]*"' /tmp/wc.log | sort -u | head
