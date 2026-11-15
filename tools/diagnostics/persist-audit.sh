#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 持久化审计：确认重启后所有修复仍然生效
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)

echo "########## 1. 关键修复是否写进文件（不会被运行时状态覆盖）##########"
echo -n "run-game.sh 的 sg audio 补组: "
grep -q 'exec sg audio -c' ~/galworld/bin/run-game.sh && echo OK || echo MISSING
echo -n "run-game.sh 的 --audio-on-start true: "
grep -q -- '--audio-on-start true' ~/galworld/bin/run-game.sh && echo OK || echo MISSING
echo -n "run-game.sh 的 ALSA_CONFIG_PATH 极简配置: "
grep -q 'export ALSA_CONFIG_PATH=' ~/galworld/bin/run-game.sh && echo OK || echo MISSING
echo -n "run-game.sh 的 pcm.pulse 已移除(不应存在): "
grep -q '^pcm.pulse' ~/galworld/bin/run-game.sh && echo "STILL-THERE-BAD" || echo OK

echo
echo "########## 2. systemd 单元 ##########"
for f in galworld-pulse.service "galworld-game@.service"; do
  echo "--- $f"
  systemctl --user cat "$f" 2>/dev/null | grep -E "ExecStart|SupplementaryGroups|WantedBy"
  echo -n "enabled: "; systemctl --user is-enabled "$f" 2>&1
done

echo
echo "########## 3. linger（决定开机是否启动 user 服务）##########"
loginctl show-user "$(id -un)" 2>/dev/null | grep -i linger

echo
echo "########## 4. 内核模块 snd-aloop 开机加载 ##########"
cat /etc/modules-load.d/snd-aloop.conf 2>/dev/null || echo "MISSING /etc/modules-load.d/snd-aloop.conf"
echo -n "当前已加载: "; lsmod | grep -c snd_aloop

echo
echo "########## 5. pulse 配置（重启后会重新读取）##########"
echo "--- ~/.config/pulse/default.pa:"
cat ~/.config/pulse/default.pa 2>/dev/null
echo "--- daemon.conf:"
cat ~/.config/pulse/daemon.conf 2>/dev/null || echo "(无)"

echo
echo "########## 6. 证书与模板 ##########"
ls -la ~/galworld/data/certs/ 2>/dev/null | head -4
ls -la ~/galworld/data/templates/ 2>/dev/null

echo
echo "########## 7. 各游戏 game.env ##########"
for e in ~/galworld/games/*/game.env; do
  echo "--- $e"
  cat "$e"
done

echo
echo "########## 8. 自动启动（Boot）检查 ##########"
echo "--- 是否有 enable 的游戏实例:"
ls -la ~/.config/systemd/user/default.target.wants/ 2>/dev/null
