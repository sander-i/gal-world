#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 构建 wow64 版 Wine（--enable-archs=i386,x86_64）并装到指定前缀。
#
# 用途：旧式 WoW64 的 wine 把 32 位游戏跑在 32 位 Linux 进程里，地址空间只有 4 GiB，
# Wine 自身还要预留约 2.5 GiB。大型 32 位 galgame 会在 D3D 初始化时顶满，
# wined3d 申请 64 MiB GL buffer 失败后空指针写，游戏弹 "has encountered a serious
# problem and needs to close"（SenrenBanka，2026-11-11 实测）。wow64 构建让 32 位 PE
# 跑进 64 位进程，4 GiB 上限消失。
#
# 用法:
#   build-wow64-wine.sh [--prefix DIR] [--version 11.21] [--jobs N] [--force]
#
# 现有构建会被复用（--force 强制重建）。编译依赖由一键部署的
# 「安装 wow64 Wine 编译依赖」步骤安装，本脚本不碰 apt。
set -euo pipefail

GALROOT="${GALWORLD_ROOT:-$HOME/galworld}"
PREFIX="$GALROOT/.local/wine11"
VERSION="${WINE_VERSION:-11.21}"
JOBS="$(nproc)"
WORK="${WINE_BUILD_DIR:-$HOME/wine-build}"
FORCE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix)  PREFIX="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --jobs)    JOBS="$2"; shift 2 ;;
    --force)   FORCE=1; shift ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

if [[ -x "$PREFIX/bin/wine" && $FORCE -eq 0 ]]; then
  echo "wow64 wine 已存在: $PREFIX ($("$PREFIX/bin/wine" --version 2>/dev/null || echo unknown))，跳过（--force 重建）"
  exit 0
fi

for tool in curl tar make gcc; do
  command -v "$tool" >/dev/null || { echo "缺少 $tool，先执行一键部署的「安装 wow64 Wine 编译依赖」步骤" >&2; exit 1; }
done

SERIES="${VERSION%.*}"
TARBALL="$WORK/wine-$VERSION.tar.xz"
mkdir -p "$WORK"

if [[ ! -s "$TARBALL" ]] || ! xz -t "$TARBALL" 2>/dev/null; then
  rm -f "$TARBALL"
  # 官方源在国内速度不稳，多试几次并支持断点续传
  for attempt in 1 2 3 4 5; do
    curl -fL --retry 8 --retry-all-errors --retry-delay 3 -C - --max-time 3600 \
      -o "$TARBALL" "https://dl.winehq.org/wine/source/$SERIES.x/wine-$VERSION.tar.xz" && break
    echo "下载失败（第 $attempt 次），重试…" >&2
    sleep 5
  done
fi
[[ -s "$TARBALL" ]] || { echo "下载失败: wine-$VERSION.tar.xz" >&2; exit 1; }
xz -t "$TARBALL"

SRC="$WORK/wine-$VERSION"
rm -rf "$SRC"
echo "解压到 $WORK"
tar -xf "$TARBALL" -C "$WORK"

cd "$SRC"
echo "configure: prefix=$PREFIX archs=i386,x86_64 jobs=$JOBS"
./configure --prefix="$PREFIX" --enable-archs=i386,x86_64 \
  --with-x --with-alsa --with-pulse --with-freetype --with-vulkan
make -j"$JOBS"
make install

"$PREFIX/bin/wine" --version
echo "wow64 wine 安装完成: $PREFIX"
