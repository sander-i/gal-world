#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 拆解 run-game.sh，定位哪一部分导致音频失效
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)
G=sample-game
D="$HOME/galworld/games/$G"
SRC="$HOME/galworld/bin/run-game.sh"

measure() {
  local tag="$1"
  sleep 32
  local pst pk
  pst=$(head -1 /proc/asound/card0/pcm0p/sub0/status 2>/dev/null | sed 's/state: //')
  timeout 2 parecord -d "gal_${G}.monitor" --file-format=wav --rate=48000 --channels=2 --format=s16le /tmp/q.wav 2>/dev/null
  pk=$(python3 - <<'PY'
import wave,struct
try:
    w=wave.open('/tmp/q.wav'); d=w.readframes(w.getnframes())
    s=struct.unpack('<%dh'%(len(d)//2),d); print(max((abs(v) for v in s),default=0))
except Exception: print("-")
PY
)
  rm -f /tmp/q.wav
  echo "[$tag] playDev=$pst peak=$pk"
}

cleanup() {
  systemctl --user stop "galworld-game@$G.service" 2>/dev/null
  for p in $(ps -eo pid,args | awk '/run-game\.sh|sample-game\.exe|winedbg|venv\/bin\/selkies/ && !/awk/ {print $1}'); do kill -9 "$p" 2>/dev/null; done
  WINEPREFIX="$D/wineprefix" wineserver -k 2>/dev/null
  for p in $(ps -eo pid,args | awk '/Xvfb :2 / && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
  sleep 3
  rm -f /tmp/.X2-lock /tmp/.X11-unix/X2
}

run_variant() {
  local tag="$1" filter="$2"
  cleanup
  local f="/tmp/variant-$tag.sh"
  if [[ -n "$filter" ]]; then
    eval "$filter" > "$f"
  else
    cp "$SRC" "$f"
  fi
  chmod +x "$f"
  setsid nohup "$f" "$G" >/dev/null 2>&1 </dev/null &
  measure "$tag"
}

echo "=== V1: 原样 run-game.sh"
run_variant "V1-full" ""

echo "=== V2: 去掉窗口守护循环（xdotool/xwininfo 轮询）"
run_variant "V2-no-wm-daemon" \
  "awk '/---- 5b/,/---- 6/{ if (/^if command -v xdotool/) p=1; if (p&&/^fi\$/) {p=0; print \"echo skip-wm-daemon\"; next} if(!p) print }' '$SRC'"

echo "=== V3: 去掉 selkies（只跑 wine）"
run_variant "V3-no-selkies" \
  "sed '/---- 6. selkies/,\$d' '$SRC'"

cleanup
