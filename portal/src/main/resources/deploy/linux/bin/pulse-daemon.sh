#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# Gal World - PulseAudio 守护（systemd 用）
# 若已有 pulse 在跑则直接常驻等待（不重复启动），否则以纯净配置启动。
#
# 必须自身带 audio 组：pulse 要打开 /dev/snd 才能加载 module-alsa-source。
# systemd --user 的 manager 组集合被固化，服务进程没有 audio 组，
# 会导致 module-alsa-source 初始化失败 → 采集源建不出来 → 无声。
set -euo pipefail

if ! id -nG | tr ' ' '\n' | grep -qx audio; then
  exec sg audio -c "$(printf '%q ' "$0" "$@")"
fi

export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
mkdir -p "$XDG_RUNTIME_DIR/pulse" ~/.config/pulse

if pactl info >/dev/null 2>&1; then
  # 已有 pulse 在跑：但如果它没有 audio 组，就加载不了 module-alsa-source，
  # 采集源永远建不出来（表现为完全无声）。此时杀掉它自己重启。
  stale=0
  for p in $(pgrep -u "$(id -u)" -f "pulseaudio --daemonize=no" 2>/dev/null); do
    if ! grep -oP '^Groups:\s+\K.*' "/proc/$p/status" 2>/dev/null | tr ' ' '\n' | grep -qx "$(getent group audio | cut -d: -f3)"; then
      echo "stale pulseaudio pid=$p without audio group; restarting it"
      kill -9 "$p" 2>/dev/null || true
      stale=1
    fi
  done
  if [[ "$stale" -eq 1 ]]; then
    sleep 2
  else
    echo "pulseaudio already running with audio group; waiting"
    while pactl info >/dev/null 2>&1; do sleep 10; done
    echo "pulseaudio gone; exiting so systemd can retry"
    exit 1
  fi
fi

cat > ~/.config/pulse/daemon.conf <<'EOF'
default-sample-format = s16le
default-sample-rate = 44100
alternate-sample-rate = 44100
exit-idle-time = -1
EOF

# 必须禁用 module-udev-detect：它会独占整张 snd-aloop 卡（hw:0,0 与 hw:0,1 全被抓住），
# 导致 wine 打不开播放端、pulse 也建不了采集源。只加载必要模块。
cat > ~/.config/pulse/default.pa <<'EOF'
load-module module-native-protocol-unix
load-module module-always-sink
EOF

exec pulseaudio --daemonize=no --exit-idle-time=-1 --log-target=stderr -n \
  --load="module-native-protocol-unix" \
  --load="module-always-sink"
