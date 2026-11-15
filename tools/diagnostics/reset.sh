#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 彻底重置 Gal World 运行环境（会话 + pulse + loopback 模块）
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)

echo "=== 停会话"
~/galworld/bin/stop-game.sh sample-game >/dev/null 2>&1

echo "=== 停 selkies / Xvfb / 游戏"
for p in $(ps -eo pid,args | awk '/venv\/bin\/selkies/ && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
for p in $(ps -eo pid,args | awk '/Xvfb :[0-9]/ && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
for p in $(ps -eo pid,args | awk '/sample-game\.exe/ && !/awk/ {print $1}'); do kill -9 "$p" 2>/dev/null; done
WINEPREFIX=$HOME/galworld/games/sample-game/wineprefix wineserver -k 2>/dev/null

echo "=== 停 pulse"
for p in $(ps -eo pid,args | awk '/pulseaudio --(start|daemonize)/ && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
sleep 2

echo "=== 清标记"
rm -f /run/user/$(id -u)/pulse/gal_*.loopback

echo "=== 重建"
~/galworld/bin/pulse-setup.sh 2>&1 | tail -12

sleep 1
echo "=== 剩余进程"
ps -eo pid,args | grep -E "selkies|sample-game|Xvfb|pulseaudio" | grep -v grep | head -5
