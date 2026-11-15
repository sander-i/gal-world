#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 恢复演练：多处破坏 → restore → 端到端验证
set +euo pipefail
export XDG_RUNTIME_DIR=/run/user/$(id -u)

PASS=0; FAIL=0
chk() { # chk <描述> <实际> <期望>
  if [[ "$2" == "$3" ]]; then echo "  [PASS] $1 ($2)"; PASS=$((PASS+1));
  else echo "  [FAIL] $1 实际=$2 期望=$3"; FAIL=$((FAIL+1)); fi
}
chkf() { # chkf <描述> <条件表达式结果 0/1>
  if [[ "$2" == "0" ]]; then echo "  [PASS] $1"; PASS=$((PASS+1));
  else echo "  [FAIL] $1"; FAIL=$((FAIL+1)); fi
}

echo "########## 0. 建立基准快照 ##########"
~/galworld/bin/backup.sh backup >/dev/null 2>&1
BASE=$(readlink -f ~/galworld/data/backups/latest)
echo "基准 = $BASE"
chkf "基准快照健康" $([[ -f $BASE/MANIFEST ]] && grep -q '^health=OK' $BASE/MANIFEST; echo $?)

echo
echo "########## 1. 故意破坏 3 处配置 ##########"
# 1a. audio-on-start 值改成无效的 yes
sed -i 's/--audio-on-start true/--audio-on-start yes/' ~/galworld/bin/run-game.sh
chk "run-game.sh 已被改坏" "$(grep -c -- '--audio-on-start yes' ~/galworld/bin/run-game.sh)" "1"

# 1b. 删掉 pulse-daemon.sh 的 audio 组守卫
sed -i '/exec sg audio -c/d' ~/galworld/bin/pulse-daemon.sh
chk "pulse-daemon.sh 守卫已删" "$(grep -c 'exec sg audio -c' ~/galworld/bin/pulse-daemon.sh)" "0"

# 1c. 往 ALSA 配置模板里塞 pcm.pulse（会触发 -16 busy）
cp ~/galworld/bin/run-game.sh /tmp/before-drill.sh
sed -i 's|^ctl.!default {|pcm.pulse {\n    type hw\n    card 0\n}\nctl.!default {|' ~/galworld/bin/run-game.sh
chk "已塞入 pcm.pulse" "$(grep -c '^pcm.pulse {' ~/galworld/bin/run-game.sh)" "1"

echo
echo "########## 2. 确认坏配置下备份会被拒绝 ##########"
OUT=$(~/galworld/bin/backup.sh backup 2>&1)
echo "$OUT" | grep -q "已拒绝" && { echo "  [PASS] 坏配置备份被拒绝"; PASS=$((PASS+1)); } || { echo "  [FAIL] 坏配置备份未被拒绝"; FAIL=$((FAIL+1)); }
LATEST=$(readlink -f ~/galworld/data/backups/latest)
chk "latest 未被坏快照覆盖" "$LATEST" "$BASE"

echo
echo "########## 3. 执行恢复 ##########"
~/galworld/bin/backup.sh restore 2>&1 | sed 's/^/  /'

echo
echo "########## 4. 验证脚本已还原 ##########"
chk "audio-on-start=true 已还原" "$(grep -c -- '--audio-on-start true' ~/galworld/bin/run-game.sh)" "1"
chk "audio-on-start=yes 已消失" "$(grep -c -- '--audio-on-start yes' ~/galworld/bin/run-game.sh)" "0"
chk "run-game.sh 守卫在位" "$(grep -c 'exec sg audio -c' ~/galworld/bin/run-game.sh)" "1"
chk "pulse-daemon.sh 守卫在位" "$(grep -c 'exec sg audio -c' ~/galworld/bin/pulse-daemon.sh)" "1"
chk "pcm.pulse 已移除" "$(grep -c '^pcm.pulse {' ~/galworld/bin/run-game.sh)" "0"

echo
echo "########## 5. 等待会话就绪 ##########"
for i in $(seq 1 45); do
  n=$(pgrep -fc "games/.*\.exe" 2>/dev/null || echo 0)
  [[ "$n" -ge 2 ]] && break
  sleep 2
done
sleep 25

echo
echo "########## 6. 端到端音频验证 ##########"
chk "9002 监听" "$(ss -lnt | grep -c ':9002 ')" "1"
chk "9003 监听" "$(ss -lnt | grep -c ':9003 ')" "1"

for g in sample-game demo-game; do
  PID=$(pgrep -f "games/$g/.*\.exe" | head -1)
  GRP=$(grep -oP '^Groups:\s+\K.*' "/proc/$PID/status" 2>/dev/null || echo none)
  echo "$GRP" | tr ' ' '\n' | grep -qx 29 \
    && { echo "  [PASS] $g wine 有 audio 组 ($GRP)"; PASS=$((PASS+1)); } \
    || { echo "  [FAIL] $g wine 缺 audio 组 ($GRP)"; FAIL=$((FAIL+1)); }
done

PPID_=$(pgrep -u "$(id -u)" -f "pulseaudio --daemonize=no" | head -1)
GRP=$(grep -oP '^Groups:\s+\K.*' "/proc/$PPID_/status" 2>/dev/null || echo none)
echo "$GRP" | tr ' ' '\n' | grep -qx 29 \
  && { echo "  [PASS] pulse 有 audio 组 ($GRP)"; PASS=$((PASS+1)); } \
  || { echo "  [FAIL] pulse 缺 audio 组 ($GRP)"; FAIL=$((FAIL+1)); }

chk "alsa-source 模块数" "$(pactl list short modules | grep -c module-alsa-source)" "2"
chk "loopback 模块数(无重复)" "$(pactl list short modules | grep -c module-loopback)" "2"

for g in sample-game demo-game; do
  timeout 3 parecord -d "gal_$g.monitor" --file-format=wav --rate=48000 \
    --channels=2 --format=s16le /tmp/dr.wav 2>/dev/null
  PK=$(python3 - <<'PY'
import wave,struct
try:
    w=wave.open('/tmp/dr.wav'); d=w.readframes(w.getnframes())
    s=struct.unpack('<%dh'%(len(d)//2),d); print(max((abs(v) for v in s),default=0))
except Exception: print(0)
PY
)
  rm -f /tmp/dr.wav
  [[ "${PK:-0}" -gt 100 ]] \
    && { echo "  [PASS] $g 音频电平 peak=$PK"; PASS=$((PASS+1)); } \
    || { echo "  [FAIL] $g 音频电平 peak=$PK (标题画面无BGM时可能为0)"; FAIL=$((FAIL+1)); }
done

echo
echo "########## 7. selkies 音频参数 ##########"
ps -eo args | grep "venv/bin/selkies --addr" | grep -v grep | head -1 | tr ' ' '\n' \
  | grep -q -- '--audio-on-start' && echo "  [PASS] selkies 带 audio-on-start" || echo "  [FAIL] selkies 缺 audio-on-start"

echo
echo "########## 结果 ##########"
echo "PASS=$PASS FAIL=$FAIL"
