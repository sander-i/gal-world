#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 把旧命名的媒体迁移到新方案，并在 DB 里建 game_media 记录。
#
# 旧: cover.png / cg.mp4 / cg-high.mp4 / cg-low.png
# 新: cover-0.png / cg-0.mp4（每类从 0 开始编号）
#
# 只重命名文件；DB 记录由门户扫描后自动补（见下方说明）。

set -euo pipefail
G=~/galworld

echo "=== 迁移前"
for d in "$G"/games/*.meta/media; do
  [ -d "$d" ] && echo "--- $(basename "$(dirname "$d")")" && ls "$d"
done

for d in "$G"/games/*.meta/media; do
  [ -d "$d" ] || continue
  id=$(basename "$(dirname "$d")" | sed 's/\.meta$//')

  # 封面：cover.png → cover-0.png
  for f in "$d"/cover.*; do
    [ -f "$f" ] || continue
    ext="${f##*.}"
    case "$ext" in
      png|jpg|jpeg|webp|gif)
        mv "$f" "$d/cover-0.$ext" && echo "  cover → cover-0.$ext"
        ;;
    esac
  done

  # CG：三档合并为一个列表，按 高→标准→低 的顺序编号
  n=0
  for base in cg-high cg cg-low; do
    for f in "$d"/$base.*; do
      [ -f "$f" ] || continue
      ext="${f##*.}"
      mv "$f" "$d/cg-$n.$ext" && echo "  $base.$ext → cg-$n.$ext"
      n=$((n + 1))
    done
  done
done

echo
echo "=== 迁移后"
for d in "$G"/games/*.meta/media; do
  [ -d "$d" ] && echo "--- $(basename "$(dirname "$d")")" && ls "$d"
done
