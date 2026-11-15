#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# Gal World - PulseAudio 音频源配置（幂等，可重复执行）
#
# 每个游戏需要一条音频链：
#   wine(ALSA hw:0,0,<slot>) → snd-aloop → (hw:0,1,<slot>)
#     → pulse source  gal_<id>_in
#     → module-loopback
#     → pulse sink    gal_<id>
#     → selkies 采集  gal_<id>.monitor
#
# 为什么不能直接用 module-alsa-source 当采集源:
#   Selkies 的 --audio-device-name 语义是「sink 名」，它会把 .monitor 后缀去掉
#   然后确保同名 sink 存在。若名字是一个 source，pulse 会把新建的 sink 改名成
#   名字.2，导致 selkies 报 "Loaded module-null-sink but it never appeared"。
#   所以必须提供同名的 sink，并用 loopback 把 ALSA 采集源灌进去。

set -euo pipefail

GALROOT="${GALWORLD_ROOT:-$HOME/galworld}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
mkdir -p "$XDG_RUNTIME_DIR/pulse" ~/.config/pulse

# ---- 1. 确保 pulse 在跑 ----
if pactl info >/dev/null 2>&1; then
  echo "pulseaudio already running"
else
  cat > ~/.config/pulse/default.pa <<'EOF'
.include /etc/pulse/default.pa
load-module module-always-sink
EOF
  pulseaudio --start --exit-idle-time=-1 2>/dev/null || true
  for _ in $(seq 1 20); do pactl info >/dev/null 2>&1 && break; sleep 0.25; done
fi
pactl info >/dev/null 2>&1 || { echo "ERROR: pulseaudio failed to start" >&2; exit 1; }

# ---- 2. 为每个注册游戏建链 ----
shopt -s nullglob
for env in "$GALROOT"/games/*/game.env; do
  ( set -a; . "$env"; set +a
    id="${GAME_ID:-$(basename "$(dirname "$env")")}"
    slot="${LOOPBACK_SLOT:-0}"
    card="${LOOPBACK_CARD:-0}"
    src="gal_${id}_in"
    sink="gal_${id}"

    # 2a. ALSA 采集源（loopback 的采集侧子设备）
    #     - 必须显式给 channels/rate：缺省参数打开子设备会失败
    #     - tsched=1：tsched=0 在小缓冲下产生周期性静音空洞（电磁音）
    #     - 不要加 latency_msec：pulse 16.1 下加了必失败
    if pactl list short sources 2>/dev/null | grep -qw "$src"; then
      echo "source $src exists"
    else
      if pactl load-module module-alsa-source device="hw:${card},1,${slot}" \
           source_name="$src" channels=2 rate=44100 tsched=1 \
           >/dev/null 2>&1; then
        echo "source $src created (hw:${card},1,$slot)"
      else
        echo "WARN: failed to create source $src (hw:${card},1,$slot)" >&2
      fi
    fi

    # 2b. 同名 sink（selkies 会 strip .monitor 后探测它）
    #     采样率必须与采集源一致，否则 loopback 重采样引入失真
    if pactl list short sinks 2>/dev/null | grep -qw "$sink"; then
      echo "sink $sink exists"
    else
      if pactl load-module module-null-sink sink_name="$sink" rate=44100 channels=2 >/dev/null 2>&1; then
        echo "sink $sink created"
      else
        echo "WARN: failed to create sink $sink" >&2
      fi
    fi

    # 2c. loopback: source → sink（同频直通，adjust_time 让缓冲自行微调）
    lb_marker="$XDG_RUNTIME_DIR/pulse/gal_${id}.loopback"
    if [[ -f "$lb_marker" ]]; then
      echo "loopback for $id already registered"
    else
      if m=$(pactl load-module module-loopback \
               source="$src" sink="$sink" latency_msec=100 adjust_time=10 \
               source_dont_move=true sink_dont_move=true 2>/dev/null); then
        echo "$m" > "$lb_marker"
        echo "loopback $src -> $sink (module $m)"
      else
        echo "WARN: failed to create loopback $src -> $sink" >&2
      fi
    fi
  )
done
shopt -u nullglob

echo "--- sinks ---"
pactl list short sinks
echo "--- sources ---"
pactl list short sources
