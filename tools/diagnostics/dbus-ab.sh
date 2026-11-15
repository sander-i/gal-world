#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 验证 D-Bus 会话总线是否是音频失效原因
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)
G=sample-game
D="$HOME/galworld/games/$G"

cleanup() {
  for p in $(ps -eo pid,args | awk '/sample-game\.exe|winedbg/ && !/awk/ {print $1}'); do kill -9 "$p" 2>/dev/null; done
  WINEPREFIX="$D/wineprefix" wineserver -k 2>/dev/null
  for p in $(ps -eo pid,args | awk '/Xvfb :2 / && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
  sleep 2
  rm -f /tmp/.X2-lock /tmp/.X11-unix/X2
}

probe() {
  local tag="$1"; shift
  cleanup
  setsid Xvfb :2 -screen 0 1280x720x24 -nolisten tcp >/dev/null 2>&1 </dev/null &
  for _ in $(seq 1 40); do DISPLAY=:2 xdpyinfo >/dev/null 2>&1 && break; sleep 0.25; done
  cd "$D"
  ( export WINEPREFIX="$D/wineprefix"
    export WINEDLLOVERRIDES="winecoreaudio.drv=d;winepulse.drv=d"
    export ALSA_CONFIG_PATH="$HOME/galworld/data/alsa-$G.conf"
    export PULSE_SERVER="unix:/run/user/$(id -u)/pulse/native"
    export LIBGL_ALWAYS_SOFTWARE=1 LANG=C.UTF-8 DISPLAY=:2
    "$@" wine sample-game.exe >/tmp/probe-$tag.log 2>&1 ) &
  sleep 30
  pst=$(head -1 /proc/asound/card0/pcm0p/sub0/status 2>/dev/null | sed 's/state: //')
  timeout 2 parecord -d "gal_${G}.monitor" --file-format=wav --rate=48000 --channels=2 --format=s16le /tmp/p.wav 2>/dev/null
  pk=$(python3 - <<'PY'
import wave,struct
try:
    w=wave.open('/tmp/p.wav'); d=w.readframes(w.getnframes())
    s=struct.unpack('<%dh'%(len(d)//2),d); print(max((abs(v) for v in s),default=0))
except Exception: print("-")
PY
)
  rm -f /tmp/p.wav
  echo "[$tag] playDev=$pst peak=$pk"
  cleanup
}

echo "=== A: 有 DBUS（手动默认）"
probe "A-dbus" env

echo "=== B: 无 DBUS（模拟 systemd）"
probe "B-nodbus" env -u DBUS_SESSION_BUS_ADDRESS

echo "=== C: 无 DBUS + WINEDEBUG=-all（完全模拟服务）"
probe "C-service" env -u DBUS_SESSION_BUS_ADDRESS WINEDEBUG=-all
