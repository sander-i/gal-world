#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 重启所有 Gal World 会话（清掉旧 Xvfb，按新参数重建）
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)

for g in sample-game demo-game; do
  echo "=== 停止 $g"
  systemctl --user stop "galworld-game@$g.service" 2>/dev/null
  d=$(grep -h '^DISPLAY_NUM=' "$HOME/galworld/games/$g/game.env" | cut -d= -f2)
  for p in $(ps -eo pid,args | awk -v d="$d" '$0 ~ ("Xvfb :" d " ") && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
  rm -f "/tmp/.X${d}-lock" "/tmp/.X11-unix/X${d}"
done
sleep 2

for g in sample-game demo-game; do
  echo "=== 启动 $g"
  systemctl --user start "galworld-game@$g.service"
done

sleep 40
echo "=== 状态"
~/galworld/bin/galworld list
for d in 2 3; do
  echo -n "display :$d  "
  DISPLAY=:$d xdpyinfo 2>/dev/null | awk '/dimensions/{print $2, $3}'
done
