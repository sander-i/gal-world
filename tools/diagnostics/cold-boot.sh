#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 冷启动模拟：完全停掉所有相关进程，再按开机流程启动
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)

echo "########## 停止阶段 ##########"
for g in sample-game demo-game; do
  systemctl --user stop "galworld-game@$g.service" 2>/dev/null
  systemctl --user reset-failed "galworld-game@$g.service" 2>/dev/null
done
systemctl --user stop galworld-pulse.service 2>/dev/null
systemctl --user reset-failed galworld-pulse.service 2>/dev/null

for p in $(ps -eo pid,args | awk '/run-game\.sh|sample-game\.exe|game\.exe|winedbg|venv\/bin\/selkies|pulseaudio/ && !/awk/ {print $1}'); do kill -9 "$p" 2>/dev/null; done
for g in sample-game demo-game; do WINEPREFIX="$HOME/galworld/games/$g/wineprefix" wineserver -k 2>/dev/null; done
for p in $(ps -eo pid,args | awk '/Xvfb :/ && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
sleep 4
rm -f /tmp/.X*-lock /tmp/.X11-unix/X* 2>/dev/null
rm -rf /run/user/$(id -u)/pulse
echo "已全部停止"

echo
echo "########## 模拟开机：按依赖顺序启动 ##########"
echo "--- snd-aloop 模块（开机由 /etc/modules-load.d 加载）"
lsmod | grep -q snd_aloop && echo "已加载" || echo "未加载(异常)"
ls /dev/snd/ 2>/dev/null | tr '\n' ' '; echo

echo "--- 启动 galworld-pulse"
systemctl --user start galworld-pulse.service
sleep 6
systemctl --user is-active galworld-pulse.service
pactl info >/dev/null 2>&1 && echo "pulse 可连接" || echo "pulse 不可连接"

echo "--- 启动游戏服务"
systemctl --user start galworld-game@sample-game.service
systemctl --user start galworld-game@demo-game.service
sleep 45

echo
echo "########## 验收 ##########"
~/galworld/bin/galworld list
echo "--- 端口:"
ss -lnt | grep -E "9002|9003"
echo "--- 进程组（必须含 29=audio）:"
for g in sample-game demo-game; do
  PID=$(pgrep -f "games/$g/.*\.exe" | head -1)
  echo -n "$g: "; [[ -n "${PID:-}" ]] && grep -oP '^Groups:\s+\K.*' /proc/$PID/status || echo "no-process"
done
echo "--- 播放端:"
for d in 0 1; do for s in 0 1; do printf "p%d/%d=" "$d" "$s"; head -1 "/proc/asound/card0/pcm${d}p/sub${s}/status" 2>/dev/null|sed 's/state: //'; done; done
echo "--- 音频电平:"
for g in sample-game demo-game; do
  timeout 3 parecord -d "gal_${g}.monitor" --file-format=wav --rate=48000 --channels=2 --format=s16le /tmp/cc.wav 2>/dev/null
  python3 - "$g" <<'PY'
import wave,struct,sys
try:
    w=wave.open('/tmp/cc.wav'); d=w.readframes(w.getnframes())
    s=struct.unpack('<%dh'%(len(d)//2),d)
    print(sys.argv[1], "peak", max((abs(v) for v in s), default=0))
except Exception as e: print(sys.argv[1], "no-data")
PY
  rm -f /tmp/cc.wav
done
