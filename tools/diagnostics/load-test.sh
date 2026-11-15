#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# M4-4 并发压测：用同一游戏复制 4 个实例（独立 prefix / 显示 / 端口 / loopback slot）
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)

SRC=~/galworld/games/sample-game
echo "########## 准备 4 个测试实例 ##########"
for i in 1 2 3 4; do
  ID="load$i"
  DIR="$HOME/galworld/games/$ID"
  if [[ ! -d "$DIR" ]]; then
    mkdir -p "$DIR"
    # 只做符号链接，不复制 5.9GB 资源；wineprefix 需要独立（写状态）
    for f in sample-game.exe sample-game.pfs sample-game.pfs.000 sample-game.pfs.001 \
             sample-game.pfs.002 sample-game.pfs.003 movie font; do
      ln -sfn "$SRC/$f" "$DIR/$f"
    done
    cp -a "$SRC/savedata_cn" "$DIR/savedata_cn" 2>/dev/null || mkdir -p "$DIR/savedata_cn"
    echo "创建 $ID"
  fi
  cat > "$DIR/game.env" <<EOF
GAME_ID=$ID
GAME_NAME=压测实例$i
EXE=$DIR/sample-game.exe
WORKDIR=$DIR
WINEPREFIX=$DIR/wineprefix
WIDTH=1280
HEIGHT=720
DISPLAY_NUM=$((10+i))
PORT=$((9000+i+2))
LOOPBACK_SLOT=$i
FRAMERATE=30-30
EXTRA_ENV=
EOF
  echo "$ID: display :$((10+i)) port $((9000+i+2)) slot $i"
done

echo
echo "########## 初始化 wine prefix（首次较慢）##########"
for i in 1 2 3 4; do
  ID="load$i"
  PF="$HOME/galworld/games/$ID/wineprefix"
  if [[ ! -d "$PF" ]]; then
    ( export WINEPREFIX="$PF" WINEDEBUG=-all WINEDLLOVERRIDES="mscoree,mshtml="
      setsid Xvfb ":$((10+i))" -screen 0 1280x720x24 -nolisten tcp >/dev/null 2>&1 </dev/null &
      sleep 2
      DISPLAY=":$((10+i))" timeout 120 wineboot -u >/dev/null 2>&1
      WINEPREFIX="$PF" wineserver -k 2>/dev/null
    ) &
  fi
done
wait
echo "prefix 初始化完成"

echo
echo "########## 启动 4 路 ##########"
for i in 1 2 3 4; do
  systemctl --user start "galworld-game@load$i" 2>/dev/null
done

echo "########## 采样 ##########"
printf "%-8s %-8s %-10s %-10s %-12s\n" "t" "running" "cpu%" "memMB" "encoders"
for t in 15 30 45 60 90 120; do
  sleep 15
  n=$(systemctl --user is-active galworld-game@load1 galworld-game@load2 galworld-game@load3 galworld-game@load4 2>/dev/null | grep -c active || echo 0)
  cpu=$(ps -eo pcpu,args | grep -E "venv/bin/selkies|games/load[0-9]/.*exe" | grep -v grep | awk '{s+=$1} END{printf "%.0f", s}')
  mem=$(ps -eo rss,args | grep -E "venv/bin/selkies|games/load[0-9]/.*exe" | grep -v grep | awk '{s+=$1} END{printf "%.0f", s/1024}')
  printf "%-8s %-8s %-10s %-10s %-12s\n" "${t}s" "$n" "${cpu:-0}" "${mem:-0}" "4"
done

echo
echo "########## 每路音频电平 ##########"
for i in 1 2 3 4; do
  timeout 3 parecord -d "gal_load$i.monitor" --file-format=wav --rate=48000 \
    --channels=2 --format=s16le /tmp/lp.wav 2>/dev/null
  python3 - "load$i" <<'PY'
import wave,struct,sys
try:
    w=wave.open('/tmp/lp.wav'); d=w.readframes(w.getnframes())
    s=struct.unpack('<%dh'%(len(d)//2),d); print(sys.argv[1],"peak",max((abs(v) for v in s),default=0))
except Exception: print(sys.argv[1],"no-data")
PY
  rm -f /tmp/lp.wav
done

echo
echo "########## 端口 ##########"
ss -lnt | grep -E ":900[3456] " || echo "无监听"
