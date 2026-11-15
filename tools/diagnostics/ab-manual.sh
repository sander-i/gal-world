#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# A/B 对照：手动启动 wine（不经 systemd），用同样的采样方法观察
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)
G=sample-game
D="$HOME/galworld/games/$G"

echo "=== 彻底清理 ==="
systemctl --user stop "galworld-game@$G.service" 2>/dev/null
systemctl --user reset-failed "galworld-game@$G.service" 2>/dev/null
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
wine sample-game.exe >/tmp/manual-run.log 2>&1 &

printf "%-6s %-8s %-8s %-10s %s\n" "t" "winePid" "audioFd" "playDev" "peak"
for i in $(seq 1 20); do
  t=$((i*3))
  PID=$(pgrep -f "games/$G/.*\.exe" | head -1)
  [[ -n "${PID:-}" ]] && fd=$(ls -l /proc/$PID/fd 2>/dev/null | grep -c '/dev/snd') || fd="-"
  pst=$(head -1 /proc/asound/card0/pcm0p/sub0/status 2>/dev/null | sed 's/state: //')
  timeout 1 parecord -d "gal_${G}.monitor" --file-format=wav --rate=48000 --channels=2 --format=s16le /tmp/s.wav 2>/dev/null
  pk=$(python3 - <<'PY'
import wave,struct
try:
    w=wave.open('/tmp/s.wav'); d=w.readframes(w.getnframes())
    s=struct.unpack('<%dh'%(len(d)//2),d); print(max((abs(v) for v in s),default=0))
except Exception: print("-")
PY
)
  rm -f /tmp/s.wav
  printf "%-6s %-8s %-8s %-10s %s\n" "${t}s" "${PID:-none}" "$fd" "${pst:-NA}" "$pk"
  sleep 2
done
