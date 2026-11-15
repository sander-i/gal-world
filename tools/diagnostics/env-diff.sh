#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 对比「服务启动的 wine」与「手动启动的 wine」环境差异
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)

echo "########## 服务版 wine 的环境 ##########"
PID=$(pgrep -f "games/sample-game/sample-game.exe" | head -1)
echo "pid=$PID"
tr '\0' '\n' < /proc/$PID/environ 2>/dev/null | grep -E "^(ALSA|PULSE|WINE|LIBGL|LANG|HOME|XDG|DISPLAY)" | sort

echo
echo "########## 服务版 wine 实际加载的库 ##########"
grep -oE "winealsa|winepulse|winecoreaudio|libasound|libpulse" /proc/$PID/maps 2>/dev/null | sort | uniq -c

echo
echo "########## winealsa 枚举结果（服务进程实时） ##########"
echo "--- winealsa devices 注册表:"
WINEPREFIX="$HOME/galworld/games/sample-game/wineprefix" wine reg query \
  "HKCU\\Software\\Wine\\Drivers\\winealsa.drv\\devices" 2>&1 | head -8

echo
echo "########## 该 prefix 的 ALSA 配置内容 ##########"
cat "$HOME/galworld/data/alsa-sample-game.conf"

echo
echo "########## 用这个配置枚举设备（用户态复现） ##########"
ALSA_CONFIG_PATH="$HOME/galworld/data/alsa-sample-game.conf" aplay -L 2>&1 | head -10
echo "--- 直接打开 default:"
ALSA_CONFIG_PATH="$HOME/galworld/data/alsa-sample-game.conf" timeout 2 aplay -D default -f S16_LE -r 44100 -c 2 /dev/zero 2>&1 | head -3
