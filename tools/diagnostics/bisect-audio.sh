#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 二分：先直跑 wine 测电平，再起 selkies 测同一电平
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)
G=sample-game
D="$HOME/galworld/games/$G"

meas() {
  local tag="$1"
  timeout 3 parecord -d gal_sample-game.monitor --file-format=wav --rate=44100 \
    --channels=2 --format=s16le /tmp/m.wav 2>/dev/null
  python3 - "$tag" <<'PY'
import wave, struct, os, sys
p='/tmp/m.wav'
if not os.path.exists(p):
    print(sys.argv[1], "no-data"); raise SystemExit
w=wave.open(p); d=w.readframes(w.getnframes())
s=struct.unpack('<%dh'%(len(d)//2), d)
print(sys.argv[1], "peak", max((abs(v) for v in s), default=0))
PY
  rm -f /tmp/m.wav
}

echo "=== 清理"
systemctl --user stop "galworld-game@$G.service" 2>/dev/null
for p in $(ps -eo pid,args | awk '/sample-game\.exe|winedbg|venv\/bin\/selkies/ && !/awk/ {print $1}'); do kill -9 "$p" 2>/dev/null; done
WINEPREFIX="$D/wineprefix" wineserver -k 2>/dev/null
for p in $(ps -eo pid,args | awk '/Xvfb :2 / && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
sleep 3
rm -f /tmp/.X2-lock /tmp/.X11-unix/X2

export WINEPREFIX="$D/wineprefix"
export WINEDLLOVERRIDES="winecoreaudio.drv=d;winepulse.drv=d"
export ALSA_CONFIG_PATH="$HOME/galworld/data/alsa-$G.conf"
export PULSE_SERVER="unix:/run/user/$(id -u)/pulse/native"
export LIBGL_ALWAYS_SOFTWARE=1 LANG=C.UTF-8 DISPLAY=:2

setsid Xvfb :2 -screen 0 1280x720x24 -nolisten tcp >/dev/null 2>&1 </dev/null &
for _ in $(seq 1 40); do DISPLAY=:2 xdpyinfo >/dev/null 2>&1 && break; sleep 0.25; done

cd "$D"
wine sample-game.exe >/tmp/bisect.log 2>&1 &
sleep 16
echo -n "播放端: "; head -1 /proc/asound/card0/pcm0p/sub0/status
meas "[A] 只有 wine"

echo "=== 启动 selkies"
setsid ~/galworld/venv/bin/selkies --addr 0.0.0.0 --port 9002 \
  --enable-https --https-cert "$HOME/galworld/data/certs/server.crt" \
  --https-key "$HOME/galworld/data/certs/server.key" \
  --basic-auth-user "${GALWORLD_USER:?GALWORLD_USER 未设置}" \
  --basic-auth-password "${GALWORLD_PASSWORD:?GALWORLD_PASSWORD 未设置}" \
  --framerate 30-30 --manual-resolution --manual-width 1280 --manual-height 720 \
  --audio-enabled --audio-device-name gal_sample-game.monitor \
  >/tmp/selkies-bisect.log 2>&1 </dev/null &
sleep 10
meas "[B] wine + selkies 空闲"
curl -sk -u "$GALWORLD_USER:$GALWORLD_PASSWORD" https://127.0.0.1:9002/ -o /dev/null 2>/dev/null
sleep 8
echo -n "播放端: "; head -1 /proc/asound/card0/pcm0p/sub0/status
meas "[C] selkies 有客户端连接后"
echo "--- selkies 音频相关日志:"
grep -iE "sink|source|volume|audio" /tmp/selkies-bisect.log | tail -8
