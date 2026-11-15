#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 单次运行游戏并抓 D3D9 设备创建参数
set +euo pipefail
export WINEPREFIX=$HOME/galworld/games/sample-game/wineprefix
export ALSA_CONFIG_PATH=$HOME/galworld/data/alsa-min.conf
export DISPLAY=:9
export WINEDEBUG=+d3d9
export WINEDLLOVERRIDES="winecoreaudio.drv=d;winepulse.drv=d"
rm -f /tmp/.X9-lock /tmp/.X11-unix/X9
Xvfb :9 -screen 0 1280x720x24 -nolisten tcp >/dev/null 2>&1 &
XP=$!
sleep 2
cd "$HOME/galworld/games/sample-game"
timeout 25 wine sample-game.exe > /tmp/d3d.log 2>&1
kill $XP 2>/dev/null
grep -E "BackBufferWidth|BackBufferHeight|CreateDevice|PresentationInterval|d3d9_create_swapchain|width|height" /tmp/d3d.log | head -25
