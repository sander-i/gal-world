#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 服务重启后持续采样 90 秒：wine 是否打开音频 fd、播放端状态、采集峰值
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)
G=sample-game

echo "=== 重启服务 ==="
systemctl --user stop "galworld-game@$G.service" 2>/dev/null
systemctl --user reset-failed "galworld-game@$G.service" 2>/dev/null
for p in $(ps -eo pid,args | awk '/sample-game\.exe|winedbg|venv\/bin\/selkies/ && !/awk/ {print $1}'); do kill -9 "$p" 2>/dev/null; done
WINEPREFIX="$HOME/galworld/games/$G/wineprefix" wineserver -k 2>/dev/null
for p in $(ps -eo pid,args | awk '/Xvfb :2 / && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
sleep 3
rm -f /tmp/.X2-lock /tmp/.X11-unix/X2
~/galworld/bin/galworld start "$G"

echo "=== 采样 90 秒 ==="
printf "%-6s %-8s %-10s %-12s %s\n" "t" "winePid" "audioFd" "playDev" "peak"
for i in $(seq 1 30); do
  t=$((i*3))
  PID=$(pgrep -f "games/$G/.*\.exe" | head -1)
  if [[ -n "${PID:-}" ]]; then
    fd=$(ls -l /proc/$PID/fd 2>/dev/null | grep -c '/dev/snd')
  else
    fd="-"
  fi
  pst=$(head -1 /proc/asound/card0/pcm0p/sub0/status 2>/dev/null | sed 's/state: //')
  timeout 1 parecord -d "gal_${G}.monitor" --file-format=wav --rate=48000 --channels=2 \
    --format=s16le /tmp/s.wav 2>/dev/null
  pk=$(python3 - <<'PY'
import wave,struct,os
p='/tmp/s.wav'
try:
    w=wave.open(p); d=w.readframes(w.getnframes())
    s=struct.unpack('<%dh'%(len(d)//2),d)
    print(max((abs(v) for v in s),default=0))
except Exception:
    print("-")
PY
)
  rm -f /tmp/s.wav
  printf "%-6s %-8s %-10s %-12s %s\n" "${t}s" "${PID:-none}" "$fd" "${pst:-NA}" "$pk"
  sleep 2
done
