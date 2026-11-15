#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 清理所有 Gal World 手工测试进程（避免 pkill -f 自匹配，用脚本隔离命令行）
set +euo pipefail

echo "=== 停 selkies"
for p in $(ps -eo pid,args | awk '/venv\/bin\/selkies/ && !/awk/ {print $1}'); do
  kill "$p" 2>/dev/null
done

echo "=== 停 pulse（只停会话用的那个）"
for p in $(ps -eo pid,args | awk '/pulseaudio --daemonize=no --exit-idle-time/ && !/awk/ {print $1}'); do
  kill "$p" 2>/dev/null
done

echo "=== 停 Xvfb :2"
for p in $(ps -eo pid,args | awk '/Xvfb :2 / && !/awk/ {print $1}'); do
  kill "$p" 2>/dev/null
done

echo "=== 停 wine"
for p in $(ps -eo pid,args | awk '/sample-game\.exe/ && !/awk/ {print $1}'); do
  kill "$p" 2>/dev/null
done
WINEPREFIX="$HOME/galworld/games/sample-game/wineprefix" wineserver -k 2>/dev/null

sleep 2
echo "=== 剩余"
ps -eo pid,args | grep -E "selkies|sample-game|Xvfb|pulseaudio" | grep -v grep | head -10
echo "=== done"
