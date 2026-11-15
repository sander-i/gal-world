#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# Gal World - 停止一个游戏会话
# 用法: stop-game.sh <game-id>
#
# 顺序: 先杀 wine（触发 run-game.sh 的 wait 返回与 cleanup），再兜底清理。

set -euo pipefail

ID="${1:?usage: stop-game.sh <game-id>}"
GALROOT="${GALWORLD_ROOT:-$HOME/galworld}"
DIR="$GALROOT/games/$ID"
ENVFILE="$DIR/game.env"

[[ -r "$ENVFILE" ]] || { echo "missing $ENVFILE" >&2; exit 1; }
# shellcheck disable=SC1090
set -a; . "$ENVFILE"; set +a

echo "[$(date '+%F %T')] stop game=$ID"

# 1. 让 wine 自己退出（触发存档清理等正常收尾）
if [[ -n "${WINEPREFIX:-}" ]]; then
  WINEPREFIX="$WINEPREFIX" wineserver -k 2>/dev/null || true
fi

# 2. 等 run-game.sh 收尾
for _ in $(seq 1 20); do
  pgrep -f "run-game.sh $ID\$" >/dev/null 2>&1 || break
  sleep 0.5
done

# 3. 兜底：清理该端口的 selkies
if [[ -n "${PORT:-}" ]]; then
  if ss -lnt 2>/dev/null | grep -q ":$PORT "; then
    for p in $(pgrep -f "selkies.*--port $PORT" 2>/dev/null || true); do
      kill "$p" 2>/dev/null || true
    done
  fi
fi

# 4. 兜底：关掉该游戏的 Xvfb
if [[ -n "${DISPLAY_NUM:-}" ]]; then
  for p in $(pgrep -f "Xvfb :$DISPLAY_NUM " 2>/dev/null || true); do
    kill "$p" 2>/dev/null || true
  done
fi

# 5. 释放该游戏的 pulse 模块
# snd-aloop 每张卡只有 8 个 subdevice（pcm_substreams 上限就是 8），
# 而 module-alsa-source 会**长期占用** hw:0,1,<slot>。不卸载的话，跑过 8 个游戏
# 之后第 9 个的 slot 必然越界，表现为：
#   Failed to load module "module-alsa-source" (device=hw:0,1,8): initialization failed
#   → 游戏播放 BGM 时 sound.tjs 抛异常 → 进程退出（日志只有 status=1/FAILURE）
# 因此停止会话时必须把本游戏的 source / loopback / sink 一并卸掉，
# 让 subdevice 回到可用池。
if command -v pactl >/dev/null 2>&1 && pactl info >/dev/null 2>&1; then
  SRC="gal_${ID}_in"
  SNK="gal_${ID}"
  pactl list short modules 2>/dev/null \
    | awk -v s="$SRC" -v k="$SNK" '
        $0 ~ /module-loopback/ && $0 ~ ("source=" s) && $0 ~ ("sink=" k) {print $1}
        $0 ~ /module-alsa-source/ && $0 ~ ("source_name=" s) {print $1}
        $0 ~ /module-null-sink/ && $0 ~ ("sink_name=" k) {print $1}
      ' \
    | while read -r m; do
        [[ -n "$m" ]] && pactl unload-module "$m" 2>/dev/null || true
      done
fi

echo "[$(date '+%F %T')] stopped game=$ID"
