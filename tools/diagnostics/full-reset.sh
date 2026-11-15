#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# Gal World 彻底重置：清掉所有残留 Xvfb / wine / selkies，然后按当前配置重启
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)

echo "=== 停服务"
for g in sample-game demo-game; do
  systemctl --user stop "galworld-game@$g.service" 2>/dev/null
  systemctl --user reset-failed "galworld-game@$g.service" 2>/dev/null
done
sleep 2

echo "=== 杀 wine"
for p in $(ps -eo pid,args | awk '/sample-game\.exe|game\.exe|winedbg|winecfg/ && !/awk/ {print $1}'); do kill -9 "$p" 2>/dev/null; done
for g in sample-game demo-game; do WINEPREFIX="$HOME/galworld/games/$g/wineprefix" wineserver -k 2>/dev/null; done
sleep 2

echo "=== 杀 selkies"
for p in $(ps -eo pid,args | awk '/venv\/bin\/selkies/ && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
sleep 1

echo "=== 杀所有 Xvfb"
for p in $(ps -eo pid,args | awk '/Xvfb :/ && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
sleep 2
rm -f /tmp/.X*-lock
rm -f /tmp/.X11-unix/X*
touch /tmp/.X11-unix 2>/dev/null || true

echo "=== 清理 loopback 标记"
rm -f "$HOME"/galworld/data/loop-*.id

echo "=== 清理 pulse 里的旧 loopback 模块"
for m in $(pactl list short modules 2>/dev/null | awk '/module-loopback/ {print $1}'); do pactl unload-module "$m" 2>/dev/null; done

echo "=== 启动"
~/galworld/bin/galworld start sample-game
~/galworld/bin/galworld start demo-game
sleep 45

echo "=== 结果"
~/galworld/bin/galworld list
ss -lnt | grep -E "9002|9003"
for d in 2 3; do
  printf "display :%s " "$d"
  DISPLAY=":$d" xdpyinfo 2>/dev/null | awk '/dimensions/{print $2,$3}' || echo "DOWN"
done
