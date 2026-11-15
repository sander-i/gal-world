#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 一键验证：后端测试 + 前端测试 + 主题变量门禁（可选加前端构建）。
#
# 与 .github/workflows/ci.yml 跑的是同一套命令，本地和任意 CI（Gitee Go、自建 runner）
# 都能用；Gitee 本身没有免费的 Actions，这个脚本就是那部分替代。
#
# 用法：
#   bash tools/ci.sh              # 测试 + 门禁
#   bash tools/ci.sh --build      # 再加一次前端生产构建
#   SKIP_INSTALL=1 bash tools/ci.sh   # 依赖已装好时跳过 npm ci
#
# 不需要游戏本体、Wine 或图形环境：后端测试用 mock，前端是纯 Node 单测。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND="$ROOT/portal/frontend"
DO_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --build) DO_BUILD=1 ;;
    -h|--help) sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$arg（可用 --build）" >&2; exit 2 ;;
  esac
done

say() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
fail() { printf '\033[31m失败：%s\033[0m\n' "$*" >&2; exit 1; }

command -v java >/dev/null 2>&1 || fail '找不到 java，需要 JDK 17+'
command -v node >/dev/null 2>&1 || fail '找不到 node，需要 Node 20+'

say '环境'
java -version 2>&1 | head -1
node --version

if [ "${SKIP_INSTALL:-0}" != "1" ]; then
  say '安装前端依赖'
  ( cd "$FRONTEND" && npm ci )
fi

say '前端单测'
( cd "$FRONTEND" && npm test )

say '主题变量门禁'
( cd "$FRONTEND" && npm run check:theme )

if [ "$DO_BUILD" = "1" ]; then
  say '前端生产构建'
  ( cd "$FRONTEND" && npm run build )
fi

say '后端测试'
# 优先用仓库自带的 Maven Wrapper，没有才退回系统 mvn
if [ -x "$ROOT/mvnw" ] || [ -f "$ROOT/mvnw" ]; then
  ( cd "$ROOT/portal" && sh "$ROOT/mvnw" -B test )
else
  command -v mvn >/dev/null 2>&1 || fail '既没有 mvnw 也没有 mvn'
  ( cd "$ROOT/portal" && mvn -B test )
fi

printf '\n\033[32m全部通过\033[0m\n'
