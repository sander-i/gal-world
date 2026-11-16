#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# Gal World Portal 部署：本机构建 → 上传 jar → 安装单元 → 重启 → 自检
#
# 用法（在 Windows Git Bash 下运行）:
#   ./deploy/portal-deploy.sh [--build-only]
#
# 依赖:
#   - 本机 JDK 17+（默认用 G:\Program Files\Java\corretto-17.0.14）
#   - 本机走 Windows OpenSSH（Git Bash 的 ssh 拿不到 Windows ssh-agent）
#   - 服务器别名已在 ~/.ssh/config 配好（默认 galworld，可用 SSH_TARGET 覆盖）

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORTAL_DIR="$REPO_ROOT/portal"
SSH_BIN="${SSH_BIN:-/c/Windows/System32/OpenSSH/ssh.exe}"
SCP_BIN="${SCP_BIN:-/c/Windows/System32/OpenSSH/scp.exe}"
SSH_TARGET="${SSH_TARGET:-galworld}"
JAVA_HOME_LOCAL="${JAVA_HOME_LOCAL:-/g/Program Files/Java/corretto-17.0.14}"
# scp 用相对家目录的路径：OpenSSH 9 起 scp 走 SFTP，远端不再展开 $HOME / ~
REMOTE_LIB='galworld/lib'
PORTAL_PORT="${GALWORLD_PORTAL_PORT:-8088}"

say() { printf '\n== %s\n' "$*"; }

say "构建前端"
cd "$PORTAL_DIR/frontend"
if [[ ! -d node_modules ]]; then
  npm ci || npm install
fi
npm run build

say "构建 jar"
cd "$PORTAL_DIR"
JAVA_HOME="$JAVA_HOME_LOCAL" mvn -q -B -DskipTests package
# 取最新产物：target 下可能残留旧版本名的 jar，按名字排序会选错
JAR=$(ls -1t target/gal-world-portal-*.jar | head -1)
echo "产物: $JAR ($(du -h "$JAR" | cut -f1))"

if [[ "${1:-}" == "--build-only" ]]; then
  exit 0
fi

say "上传 jar"
"$SSH_BIN" -o BatchMode=yes "$SSH_TARGET" "mkdir -p $REMOTE_LIB"
"$SCP_BIN" -o BatchMode=yes "$JAR" "$SSH_TARGET:$REMOTE_LIB/gal-world-portal.jar"

say "同步服务器脚本"
# 运维脚本也要随部署更新，否则改过的 backup.sh / run-game.sh 在服务器上还是旧版
"$SCP_BIN" -o BatchMode=yes "$REPO_ROOT"/deploy/bin/* "$SSH_TARGET:galworld/bin/"
"$SCP_BIN" -o BatchMode=yes "$REPO_ROOT"/tools/diagnostics/selfcheck.sh "$SSH_TARGET:galworld/bin/selfcheck.sh"
# 验收脚本也放服务器，/tmp 重启会清空，放 bin 下才能随时重跑
"$SCP_BIN" -o BatchMode=yes "$REPO_ROOT"/tools/diagnostics/m4-check.sh "$SSH_TARGET:galworld/bin/m4-check.sh"
"$SSH_BIN" -o BatchMode=yes "$SSH_TARGET" 'chmod +x $HOME/galworld/bin/*'

say "安装 systemd 单元"
"$SCP_BIN" -o BatchMode=yes "$REPO_ROOT/deploy/systemd/galworld-portal.service" \
  "$SSH_TARGET:.config/systemd/user/galworld-portal.service"

say "重启服务"
"$SSH_BIN" -o BatchMode=yes "$SSH_TARGET" '
  export XDG_RUNTIME_DIR=/run/user/$(id -u)
  systemctl --user daemon-reload
  systemctl --user enable galworld-portal.service >/dev/null 2>&1 || true
  systemctl --user restart galworld-portal.service
  for i in $(seq 1 30); do
    systemctl --user is-active --quiet galworld-portal.service && break
    sleep 1
  done
  systemctl --user is-active galworld-portal.service
'

say "自检"
"$SSH_BIN" -o BatchMode=yes "$SSH_TARGET" "
  for i in \$(seq 1 40); do
    code=\$(curl -s -o /dev/null -w '%{http_code}' https://127.0.0.1:$PORTAL_PORT/api/health -k || echo 000)
    [ \"\$code\" = \"200\" ] && { echo \"health 200 (\${i}s)\"; exit 0; }
    sleep 1
  done
  echo 'health FAILED' >&2
  journalctl --user -u galworld-portal -n 30 --no-pager | tail -30
  exit 1
"

echo
echo "门户: https://<server>:$PORTAL_PORT/"
