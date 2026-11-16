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

# 1. 先请求游戏自己关闭，让它有机会存盘
#
# 直接 wineserver -k 是硬杀：游戏拿不到关闭消息，来不及写存档，
# 玩家会丢进度（部分引擎只在正常退出时才落盘）。
# 所以先给游戏窗口发 WM_DELETE_WINDOW，等它自己退出；超时才强杀。
#
# 没有窗口管理器，消息由 xdotool 直接投递给应用。实测要点：
#  - 只发一次不可靠：游戏刚起来、还在加载时收到关闭消息会被忽略；
#  - 所以宽限期内每隔 2 秒重发；
#  - 启动器阶段窗口很小，主窗口出现后才是真正的目标，每次重发都重新挑一遍。
# 宽限期默认 20s：KiriKiri 之类会把存档写盘放在退出流程末尾，
# 实测从收到 WM_CLOSE 到进程真正结束要接近 10s，给 10s 会在最后一刻被强杀。
GRACE_SECONDS="${GALWORLD_STOP_GRACE_SECONDS:-20}"

# 输出该显示号上「像游戏窗口」的 id：面积 > 100000 的都算（主窗口 + 启动器），
# 排除 1x1 的光标窗口、隐藏消息窗口 —— 给它们发关闭请求没有意义。
game_windows() {
  command -v xwininfo >/dev/null 2>&1 || return 0
  local disp=":$DISPLAY_NUM" w area
  for w in $(DISPLAY="$disp" xwininfo -root -children 2>/dev/null \
               | awk '/^ +0x[0-9a-f]+/ {print $1}'); do
    area=$(DISPLAY="$disp" xwininfo -id "$w" 2>/dev/null \
             | awk '/Width:/{w=$2} /Height:/{h=$2} END{print (w&&h)?w*h:0}')
    [[ "${area:-0}" -gt 100000 ]] && echo "$w"
  done
  return 0
}

request_close() {
  local w sent=0
  # 没有 xdotool 就当「没有可关闭的窗口」，让调用方走强杀兜底
  command -v xdotool >/dev/null 2>&1 || return 1
  for w in $(game_windows); do
    DISPLAY=":$DISPLAY_NUM" xdotool windowclose "$w" 2>/dev/null || true
    sent=1
  done
  [[ "$sent" -eq 1 ]]
}

if [[ -n "${DISPLAY_NUM:-}" ]]; then
  if request_close; then
    echo "[$(date '+%F %T')] 已向游戏窗口发送关闭请求，等待它存盘退出"
    waited=0
    while [[ "$waited" -lt "$GRACE_SECONDS" ]]; do
      sleep 1
      waited=$(( waited + 1 ))
      pgrep -f "run-game.sh $ID\$" >/dev/null 2>&1 || break
      # 加载阶段会忽略关闭消息，宽限期内重发
      [[ $(( waited % 2 )) -eq 0 ]] && request_close >/dev/null 2>&1 || true
    done
    if pgrep -f "run-game.sh $ID\$" >/dev/null 2>&1; then
      echo "[$(date '+%F %T')] 游戏未在 ${GRACE_SECONDS}s 内退出，改为强制结束"
    else
      echo "[$(date '+%F %T')] 游戏已正常关闭"
    fi
  else
    echo "[$(date '+%F %T')] 未找到游戏窗口，直接结束进程"
  fi
fi

# 2. 兜底：仍未退出就强杀 wine（触发 run-game.sh 的 wait 返回与 cleanup）
if pgrep -f "run-game.sh $ID\$" >/dev/null 2>&1 && [[ -n "${WINEPREFIX:-}" ]]; then
  WINEPREFIX="$WINEPREFIX" wineserver -k 2>/dev/null || true
fi

# 3. 等 run-game.sh 收尾
for _ in $(seq 1 20); do
  pgrep -f "run-game.sh $ID\$" >/dev/null 2>&1 || break
  sleep 0.5
done

# 4. 兜底：清理该端口的 selkies
if [[ -n "${PORT:-}" ]]; then
  if ss -lnt 2>/dev/null | grep -q ":$PORT "; then
    for p in $(pgrep -f "selkies.*--port $PORT" 2>/dev/null || true); do
      kill "$p" 2>/dev/null || true
    done
  fi
fi

# 5. 兜底：关掉该游戏的 Xvfb
if [[ -n "${DISPLAY_NUM:-}" ]]; then
  for p in $(pgrep -f "Xvfb :$DISPLAY_NUM " 2>/dev/null || true); do
    kill "$p" 2>/dev/null || true
  done
fi

# 6. 释放该游戏的 pulse 模块
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
