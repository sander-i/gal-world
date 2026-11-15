#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 游戏运行期间，直接从 snd-aloop 采集端抓数据，验证是否真有音频流出
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)
G=sample-game
D="$HOME/galworld/games/$G"
export WINEPREFIX="$D/wineprefix"
export WINEDLLOVERRIDES="winecoreaudio.drv=d;winepulse.drv=d"
export ALSA_CONFIG_PATH="$HOME/galworld/data/alsa-$G.conf"
export PULSE_SERVER="unix:/run/user/$(id -u)/pulse/native"
export LIBGL_ALWAYS_SOFTWARE=1 LANG=C.UTF-8 DISPLAY=:2

for p in $(ps -eo pid,args | awk '/sample-game\.exe|winedbg/ && !/awk/ {print $1}'); do kill -9 "$p" 2>/dev/null; done
WINEPREFIX="$WINEPREFIX" wineserver -k 2>/dev/null
sleep 2
rm -f /tmp/.X2-lock /tmp/.X11-unix/X2
setsid Xvfb :2 -screen 0 1280x720x24 -nolisten tcp >/dev/null 2>&1 </dev/null &
for _ in $(seq 1 40); do DISPLAY=:2 xdpyinfo >/dev/null 2>&1 && break; sleep 0.25; done

cd "$D"
wine sample-game.exe >/tmp/run.log 2>&1 &
sleep 15

echo "--- 播放端:"
head -1 /proc/asound/card0/pcm0p/sub0/status

echo "--- 直接用 arecord 抓 hw:0,1,0 (snd-aloop 采集端)"
timeout 4 arecord -D hw:0,1,0 -f S16_LE -r 44100 -c 2 -d 3 /tmp/al.raw >/dev/null 2>&1
python3 - <<'PYEOF'
import array, os
p="/tmp/al.raw"
if not os.path.exists(p):
    print("no data (device busy or no open)")
else:
    a=array.array('h'); a.frombytes(open(p,'rb').read())
    print("samples", len(a), "peak", max((abs(v) for v in a), default=0))
PYEOF

echo "--- 通过 pulse 采集"
timeout 4 parecord -d gal_sample-game.monitor --file-format=wav --rate=44100 --channels=2 --format=s16le /tmp/pc.wav 2>/dev/null
python3 - <<'PYEOF'
import wave, struct, os
p="/tmp/pc.wav"
if not os.path.exists(p): print("no data"); raise SystemExit
w=wave.open(p); d=w.readframes(w.getnframes())
s=struct.unpack("<%dh"%(len(d)//2), d)
print("samples", len(s), "peak", max((abs(v) for v in s), default=0))
PYEOF

wineserver -k 2>/dev/null
