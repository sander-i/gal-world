#!/bin/bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# Gal World 音频测试：pulse 桥接 ALSA loopback
export XDG_RUNTIME_DIR=/run/user/$(id -u)
mkdir -p "$XDG_RUNTIME_DIR/pulse"
exec pulseaudio --daemonize=no --exit-idle-time=-1 --log-target=stderr -n \
  --load="module-native-protocol-unix" \
  --load="module-null-sink sink_name=output" \
  --load="module-alsa-source device=hw:0,1 source_name=gal_input"
