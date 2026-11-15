#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# Gal World - 为「exe 在子目录」的游戏补齐运行时资源
# 用法: game-assets.sh <workdir> <exedir>
#
# 背景（2026-11-14 game-6ea85b30 实测）：
#   汉化补丁常把启动程序放在 补丁/<补丁名>/ 下，而 data.xp3 / plugin/ 等
#   资源留在游戏根目录。Kirikiri 等引擎**按 exe 所在目录**找资源，
#   于是 initialize.tjs 里 Plugins.link("extrans.dll") 抛异常、进程直接退出，
#   表现为「启动 20 秒后自己关掉」，日志里只有一句 Exit code: 0。
#
# 做法：把工作目录顶层缺失的资源以**符号链接**补进 exe 目录。
#   - 不复制：xp3 动辄上百 MB，多份副本既占空间又容易版本不一致
#   - 幂等：已存在的条目一律不动，重复执行无副作用
#   - 保守：只补工作目录顶层条目；祖先目录（如 补丁/）跳过，避免自引用；
#           game.env / wineprefix 等门户配置也跳过（见 SKIP_NAMES）
#
# 该脚本由 run-game.sh 在 wine 启动前调用，也可手工执行。

set -euo pipefail

WORKDIR="${1:?usage: game-assets.sh <workdir> <exedir>}"
EXEDIR="${2:?usage: game-assets.sh <workdir> <exedir>}"

[[ -d "$WORKDIR" ]] || { echo "game-assets: 工作目录不存在: $WORKDIR" >&2; exit 1; }
[[ -d "$EXEDIR" ]] || { echo "game-assets: exe 目录不存在: $EXEDIR" >&2; exit 1; }

# 真实路径比较：exe 目录常带中文与符号链接，字符串比较会误判
WORK_REAL=$(cd "$WORKDIR" && pwd -P)
EXE_REAL=$(cd "$EXEDIR" && pwd -P)

# exe 就在工作目录里：引擎找资源的位置本来就是对的，什么都不用做
if [[ "$WORK_REAL" == "$EXE_REAL" ]]; then
  exit 0
fi

# exe 不在工作目录之下：说明 game.env 的 WORKDIR 配错了，不擅自造链接
if [[ "$EXE_REAL" != "$WORK_REAL"/* ]]; then
  echo "game-assets: WARN exe 目录 $EXE_REAL 不在工作目录 $WORK_REAL 之下，跳过" >&2
  exit 0
fi

# 不补进 exe 目录的条目：
#   game.env / *.meta  门户自己的配置文件，链过去只会让引擎误读
#   wineprefix         wine 前缀，链过去等于让 exe 目录变成第二个前缀入口
#   以点开头的隐藏项   编辑器/备份产生的临时文件
SKIP_NAMES=(game.env wineprefix)

is_skipped() {
  local name="$1"
  [[ "$name" == .* ]] && return 0
  local s
  for s in "${SKIP_NAMES[@]}"; do
    [[ "$name" == "$s" ]] && return 0
  done
  return 1
}

linked=0
while IFS= read -r -d '' entry; do
  name=$(basename "$entry")
  # 祖先目录：它是 exe 目录的上级，链进来会形成自引用
  if [[ "$EXE_REAL" == "$entry" || "$EXE_REAL" == "$entry"/* ]]; then
    continue
  fi
  if is_skipped "$name"; then
    continue
  fi
  # 已存在（真实文件或旧链接）就不碰，避免覆盖补丁自带的同名资源
  if [[ -e "$EXEDIR/$name" || -L "$EXEDIR/$name" ]]; then
    continue
  fi
  if ln -s "$entry" "$EXEDIR/$name" 2>/dev/null; then
    echo "game-assets: 链接 $name -> $entry"
    linked=$((linked + 1))
  else
    echo "game-assets: WARN 链接失败 $name" >&2
  fi
done < <(find "$WORK_REAL" -mindepth 1 -maxdepth 1 -print0)

if [[ "$linked" -gt 0 ]]; then
  echo "game-assets: 已为 $(basename "$EXE_REAL") 补齐 $linked 项资源"
fi
