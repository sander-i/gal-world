#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 全面审计：逐跳验证音频链，每跳给出可观测证据
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)
G=${1:-sample-game}

echo "########## 1. 服务与进程 ##########"
systemctl --user is-active "galworld-game@$G.service"
echo "--- selkies 实际命令行:"
ps -eo pid,args | grep -E "venv/bin/selkies" | grep -v grep | head -2
echo "--- wine 进程:"
ps -eo pid,args | grep -E "games/$G/.*\.exe" | grep -v grep | head -2

echo
echo "########## 2. wine 是否打开 ALSA 播放设备 ##########"
for p in $(pgrep -f "games/$G/.*\.exe"); do
  echo "pid=$p audio-fd=$(ls -l /proc/$p/fd 2>/dev/null | grep -c '/dev/snd')"
  ls -l /proc/$p/fd 2>/dev/null | grep '/dev/snd' | head -3
done
echo "--- /proc/asound 播放/采集状态:"
for d in 0 1; do for s in 0 1; do
  printf "p%d/%d=%-10s " "$d" "$s" "$(head -1 /proc/asound/card0/pcm${d}p/sub${s}/status 2>/dev/null | sed 's/state: //')"
  printf "c%d/%d=%s\n" "$d" "$s" "$(head -1 /proc/asound/card0/pcm${d}c/sub${s}/status 2>/dev/null | sed 's/state: //')"
done; done

echo
echo "########## 3. loopback 映射是否正确 ##########"
cat /proc/asound/card0/pcm0p/sub0/hw_params 2>/dev/null | head -5
echo "--- 设备占用:"
for f in /dev/snd/pcmC0D0p /dev/snd/pcmC0D1p; do echo "$f: $(fuser $f 2>/dev/null || echo none)"; done

echo
echo "########## 4. pulse 侧 ##########"
pactl list short modules | grep -E "module-alsa-source|module-loopback"
echo "--- sinks/sources:"
pactl list short sinks | grep gal
pactl list short sources | grep gal
echo "--- 挂到 sink 上的播放流:"
pactl list sink-inputs 2>/dev/null | grep -E "Sink Input|Sink:|application.name|sample spec|Corked" | head -20

echo
echo "########## 5. 采集电平（唯一真相） ##########"
for src in "gal_${G}_in" "gal_${G}.monitor"; do
  timeout 3 parecord -d "$src" --file-format=wav --rate=48000 --channels=2 --format=s16le /tmp/aud.wav 2>/dev/null
  python3 - "$src" <<'PY'
import wave, struct, os, sys
p='/tmp/aud.wav'
if not os.path.exists(p): print(sys.argv[1], "NO-DATA"); raise SystemExit
w=wave.open(p); d=w.readframes(w.getnframes())
s=struct.unpack('<%dh'%(len(d)//2), d)
peak=max((abs(v) for v in s), default=0)
rms=(sum(v*v for v in s)/len(s))**.5 if s else 0
print(f"{sys.argv[1]}  peak={peak} rms={rms:.0f} frames={w.getnframes()}")
PY
  rm -f /tmp/aud.wav
done

echo
echo "########## 6. selkies 音频管线日志 ##########"
grep -iE "pcmflux|audio|non-silent|Opus" ~/galworld/data/logs/$G.log | tail -12
