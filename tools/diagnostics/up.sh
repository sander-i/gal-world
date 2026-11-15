#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 启动 pulse 常驻 + 音频链 + 游戏会话
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)

echo "=== 停旧会话"
~/galworld/bin/stop-game.sh sample-game >/dev/null 2>&1

echo "=== 启动 pulse"
if ! pactl info >/dev/null 2>&1; then
  setsid nohup ~/galworld/bin/pulse-daemon.sh >/tmp/pa.log 2>&1 </dev/null &
  for _ in $(seq 1 20); do pactl info >/dev/null 2>&1 && break; sleep 0.3; done
fi
pactl info >/dev/null 2>&1 && echo "pulse ok" || { echo "pulse FAILED"; tail -5 /tmp/pa.log; exit 1; }

echo "=== 建音频链"
rm -f /run/user/$(id -u)/pulse/gal_*.loopback
~/galworld/bin/pulse-setup.sh 2>&1 | tail -8

echo "=== 启动游戏"
cd ~
setsid nohup ~/galworld/bin/run-game.sh sample-game >/dev/null 2>&1 </dev/null &
sleep 35

echo "=== 状态"
~/galworld/bin/galworld status sample-game | tail -3
echo "=== 窗口"
DISPLAY=:2 xwininfo -root -tree 2>/dev/null | grep '"' | head -4
echo "=== 关键日志"
grep -E "placed at|Capture started|Stream settings active|non-silent|WARN" ~/galworld/data/logs/sample-game.log | grep -v "^+" | tail -6
