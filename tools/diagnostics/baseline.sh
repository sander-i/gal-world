#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 用本机原始 system.dat 启动游戏，测量音频电平和文件是否被改写
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)
G=sample-game
D="$HOME/galworld/games/$G"

echo "=== 完全停止"
systemctl --user stop "galworld-game@$G.service" 2>/dev/null
systemctl --user reset-failed "galworld-game@$G.service" 2>/dev/null
for p in $(ps -eo pid,args | awk '/sample-game\.exe|winedbg|venv\/bin\/selkies/ && !/awk/ {print $1}'); do kill -9 "$p" 2>/dev/null; done
WINEPREFIX="$D/wineprefix" wineserver -k 2>/dev/null
for p in $(ps -eo pid,args | awk '/Xvfb :2 / && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
sleep 3
rm -f /tmp/.X2-lock /tmp/.X11-unix/X2

echo "=== 写入原始 system.dat"
cp /tmp/orig-system.dat "$D/savedata_cn/system.dat"
md5sum "$D/savedata_cn/system.dat"

echo "=== 直接跑（不经 systemd，便于观察）"
export WINEPREFIX="$D/wineprefix"
export WINEDLLOVERRIDES="winecoreaudio.drv=d;winepulse.drv=d"
export ALSA_CONFIG_PATH="$HOME/galworld/data/alsa-$G.conf"
export PULSE_SERVER="unix:/run/user/$(id -u)/pulse/native"
export LIBGL_ALWAYS_SOFTWARE=1 LANG=C.UTF-8 DISPLAY=:2

setsid Xvfb :2 -screen 0 1280x720x24 -nolisten tcp >/dev/null 2>&1 </dev/null &
for _ in $(seq 1 40); do DISPLAY=:2 xdpyinfo >/dev/null 2>&1 && break; sleep 0.25; done

cd "$D"
wine sample-game.exe >/tmp/run2.log 2>&1 &
sleep 18

echo "--- 播放端:"
head -1 /proc/asound/card0/pcm0p/sub0/status

echo "--- 采集电平（标题画面，通常无BGM）:"
timeout 3 parecord -d gal_sample-game.monitor --file-format=wav --rate=44100 --channels=2 --format=s16le /tmp/a.wav 2>/dev/null
python3 -c "
import wave,struct,os
p='/tmp/a.wav'
if os.path.exists(p):
    w=wave.open(p);d=w.readframes(w.getnframes())
    s=struct.unpack('<%dh'%(len(d)//2),d);print('peak',max((abs(v) for v in s),default=0))
else: print('no data')"

echo "--- system.dat 是否被改写:"
md5sum "$D/savedata_cn/system.dat"

echo "--- 点击 START 进游戏"
xdotool mousemove 116 169; sleep 0.4; xdotool click 1
sleep 6
head -1 /proc/asound/card0/pcm0p/sub0/status
timeout 4 parecord -d gal_sample-game.monitor --file-format=wav --rate=44100 --channels=2 --format=s16le /tmp/b.wav 2>/dev/null
python3 -c "
import wave,struct,os
p='/tmp/b.wav'
if os.path.exists(p):
    w=wave.open(p);d=w.readframes(w.getnframes())
    s=struct.unpack('<%dh'%(len(d)//2),d);print('游戏内 peak',max((abs(v) for v in s),default=0))
else: print('no data')"
