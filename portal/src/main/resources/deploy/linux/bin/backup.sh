#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# Gal World - 配置备份 / 恢复
# 用法:
#   backup.sh            创建带时间戳的配置快照
#   backup.sh list       列出已有快照
#   backup.sh restore [快照名]   恢复（缺省用最新快照）
#
# 备份范围（不含游戏本体与 wineprefix 的字体/驱动文件，那两者体积大且可重建）:
#   ~/galworld/bin/*            启动脚本
#   ~/galworld/games/*/game.env 每游戏参数
#   ~/galworld/games/*/wineprefix/{user,system}.reg  注册表（字体映射/代码页等修复）
#   ~/galworld/data/templates/  原始游戏配置模板
#   ~/galworld/data/certs/      TLS 证书
#   ~/.config/systemd/user/     systemd 单元
#   ~/.config/pulse/            pulse 配置

set -euo pipefail

GALROOT="${GALWORLD_ROOT:-$HOME/galworld}"
BKROOT="$GALROOT/data/backups"
STAMP=$(date '+%Y%m%d-%H%M%S')

usage() { sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

do_backup() {
  local dir="$BKROOT/config-$STAMP"
  mkdir -p "$dir"

  # 脚本
  [[ -d "$GALROOT/bin" ]] && cp -a "$GALROOT/bin" "$dir/bin"
  # 每游戏参数
  mkdir -p "$dir/games"
  for e in "$GALROOT"/games/*/game.env; do
    [[ -f "$e" ]] || continue
    local g; g=$(basename "$(dirname "$e")")
    mkdir -p "$dir/games/$g"
    cp -a "$e" "$dir/games/$g/"
  done

  # 每游戏 wineprefix 注册表：字体映射、代码页、驱动覆盖等环境修复都落在这里，
  # 丢了会导致中文变方框 / 名字变 ? 且很难查。字体文件本身不进快照
  # （每个 4MB×3，且 wine-fonts.sh 在每次启动时会幂等重建）。
  local regs=0
  for p in "$GALROOT"/games/*/wineprefix; do
    [[ -d "$p" ]] || continue
    local g; g=$(basename "$(dirname "$p")")
    mkdir -p "$dir/wineprefix/$g"
    cp -a "$p/user.reg" "$dir/wineprefix/$g/" 2>/dev/null && regs=$((regs + 1)) || true
    cp -a "$p/system.reg" "$dir/wineprefix/$g/" 2>/dev/null || true
  done
  [[ "$regs" -gt 0 ]] && echo "wineprefix_regs=$regs" >> "$dir/MANIFEST.tmp"
  # 模板与证书
  [[ -d "$GALROOT/data/templates" ]] && cp -a "$GALROOT/data/templates" "$dir/templates"
  [[ -d "$GALROOT/data/certs" ]] && cp -a "$GALROOT/data/certs" "$dir/certs"
  # systemd / pulse
  [[ -d "$HOME/.config/systemd/user" ]] && cp -a "$HOME/.config/systemd/user" "$dir/systemd-user"
  [[ -d "$HOME/.config/pulse" ]] && cp -a "$HOME/.config/pulse" "$dir/pulse"

  # 门户数据：优先用 JSON 导出（运行中也安全），失败再退回文件拷贝
  local portal_db="$GALROOT/data/portal/gal-world.mv.db"
  if curl -sk --max-time 20 -o "$dir/portal-export.json" \
       "https://127.0.0.1:8088/api/export" 2>/dev/null \
     && grep -q '"games"' "$dir/portal-export.json" 2>/dev/null; then
    echo "portal_db=json-export" >> "$dir/MANIFEST.tmp"
  elif [[ -f "$portal_db" ]]; then
    rm -f "$dir/portal-export.json"
    mkdir -p "$dir/portal"
    if cp "$portal_db" "$dir/portal/gal-world.mv.db" 2>/dev/null; then
      echo "portal_db=raw-copy" >> "$dir/MANIFEST.tmp"
    else
      echo "WARN: 门户数据库备份失败" >&2
    fi
  fi
  # 媒体文件（封面/图标/CG）：视频可达上百 MB，备份只收图片与元数据，
  # 视频体积大且可重新上传，打包进来会让快照从 3 MB 涨到近百 MB。
  # 媒体位置是 games/<id>.meta/media/，备份时保留该结构
  local media_root="$GALROOT/games"
  if compgen -G "$media_root/*.meta/media/*" >/dev/null 2>&1; then
    mkdir -p "$dir/media"
    while IFS= read -r f; do
      mkdir -p "$dir/media/$(dirname "$f")"
      cp -a "$media_root/$f" "$dir/media/$f" 2>/dev/null || true
    done < <(cd "$media_root" && find . -path '*.meta/media/*' -type f \
      \( -name '*.png' -o -name '*.jpg' -o -name '*.jpeg' -o -name '*.webp' -o -name '*.gif' \) | sed 's|^\./||')
    echo "media=images-only" >> "$dir/MANIFEST.tmp"
  fi

  # 记录生产环境的关键版本信息
  {
    echo "created_at=$STAMP"
    echo "host=$(hostname)"
    echo "galworld_root=$GALROOT"
    echo "selkies=$("$GALROOT/venv/bin/selkies" --version 2>/dev/null || echo unknown)"
    echo "wine=$(wine --version 2>/dev/null || echo unknown)"
    echo "pulse=$(pulseaudio --version 2>/dev/null || echo unknown)"
  } > "$dir/MANIFEST"
  # 合并门户数据的备份方式标记
  if [[ -f "$dir/MANIFEST.tmp" ]]; then
    cat "$dir/MANIFEST.tmp" >> "$dir/MANIFEST"
    rm -f "$dir/MANIFEST.tmp"
  fi

  # 健康校验：缺任何一条已知致命修复就标记 BAD
  local problems=()
  bash -n "$dir/bin/run-game.sh" 2>/dev/null || problems+=("run-game.sh 语法错误")
  grep -q -- '--audio-on-start true' "$dir/bin/run-game.sh" 2>/dev/null \
    || problems+=("缺少 --audio-on-start true")
  grep -q 'exec sg audio -c' "$dir/bin/run-game.sh" 2>/dev/null \
    || problems+=("run-game.sh 缺 audio 组守卫")
  grep -q 'exec sg audio -c' "$dir/bin/pulse-daemon.sh" 2>/dev/null \
    || problems+=("pulse-daemon.sh 缺 audio 组守卫")
  grep -q '^pcm\.pulse' "$dir/bin/run-game.sh" 2>/dev/null \
    && problems+=("run-game.sh 仍定义 pcm.pulse（会导致 -16 busy）")

  if [[ ${#problems[@]} -eq 0 ]]; then
    echo "health=OK" >> "$dir/MANIFEST"
    ln -sfn "$dir" "$BKROOT/latest"
    echo "备份完成（健康检查通过）: $dir"
  else
    echo "health=BAD" >> "$dir/MANIFEST"
    printf '  - %s\n' "${problems[@]}" > "$dir/REJECTED"
    echo "备份已拒绝（未更新 latest）: $dir" >&2
    printf '  - %s\n' "${problems[@]}" >&2
    return 1
  fi
  du -sh "$dir"
}

do_list() {
  ls -1d "$BKROOT"/config-* 2>/dev/null | sort || echo "（无快照）"
  [[ -L "$BKROOT/latest" ]] && echo "latest -> $(readlink "$BKROOT/latest")"
}

do_restore() {
  local src="${1:-}"
  if [[ -z "$src" ]]; then
    src=$(readlink -f "$BKROOT/latest" 2>/dev/null)
  elif [[ -d "$src" ]]; then
    src=$(readlink -f "$src")
  else
    src="$BKROOT/$src"
  fi
  [[ -d "$src" ]] || { echo "找不到快照: $src" >&2; exit 1; }

  # 拒绝恢复已知有缺陷的快照
  if grep -q '^health=BAD' "$src/MANIFEST" 2>/dev/null; then
    echo "拒绝恢复：$src 被标记为 BAD（见 $src/REJECTED）" >&2
    cat "$src/REJECTED" 2>/dev/null >&2
    exit 1
  fi
  # 老快照没有 health 标记时，现场复检关键项
  if ! grep -q '^health=OK' "$src/MANIFEST" 2>/dev/null; then
    if ! grep -q -- '--audio-on-start true' "$src/bin/run-game.sh" 2>/dev/null; then
      echo "拒绝恢复：该快照缺 --audio-on-start true，会导致无声" >&2; exit 1
    fi
    if ! grep -q 'exec sg audio -c' "$src/bin/pulse-daemon.sh" 2>/dev/null; then
      echo "拒绝恢复：该快照的 pulse-daemon.sh 缺 audio 组守卫，会导致无声" >&2; exit 1
    fi
  fi

  echo "从 $src 恢复"
  # 先备份当前状态，防止恢复错版本
  echo "--- 先对当前状态做一次快照"
  do_backup >/dev/null 2>&1 || true

  for g in "$GALROOT"/games/*/; do
    local name; name=$(basename "$g")
    # <id>.meta 是附属数据目录，没有对应服务
    [[ "$name" == *.meta ]] && continue
    systemctl --user stop "galworld-game@$name.service" 2>/dev/null || true
  done
  systemctl --user stop galworld-pulse.service 2>/dev/null || true

  [[ -d "$src/bin" ]] && cp -a "$src/bin/." "$GALROOT/bin/"
  if [[ -d "$src/games" ]]; then
    for d in "$src/games"/*/; do
      local g; g=$(basename "$d")
      [[ -f "$d/game.env" ]] && cp -a "$d/game.env" "$GALROOT/games/$g/game.env"
    done
  fi
  [[ -d "$src/templates" ]] && cp -a "$src/templates/." "$GALROOT/data/templates/"
  [[ -d "$src/certs" ]] && cp -a "$src/certs/." "$GALROOT/data/certs/"
  # wineprefix 注册表：必须在 wineserver 停掉之后覆盖，否则会被运行中的 Wine 写回
  if [[ -d "$src/wineprefix" ]]; then
    for d in "$src/wineprefix"/*/; do
      local g; g=$(basename "$d")
      local pfx="$GALROOT/games/$g/wineprefix"
      [[ -d "$pfx" ]] || continue
      WINEPREFIX="$pfx" wineserver -k 2>/dev/null || true
      cp -a "$d/user.reg" "$pfx/user.reg" 2>/dev/null || true
      cp -a "$d/system.reg" "$pfx/system.reg" 2>/dev/null || true
    done
  fi
  # media 保留 <id>.meta/media/ 结构，直接按目录覆盖回去
  if [[ -d "$src/media" ]]; then
    while IFS= read -r d; do
      mkdir -p "$GALROOT/games/$(dirname "$d")"
    done < <(cd "$src/media" && find . -type d | sed 's|^\./||')
    cp -a "$src/media/." "$GALROOT/games/" 2>/dev/null || true
  fi
  [[ -d "$src/systemd-user" ]] && cp -a "$src/systemd-user/." "$HOME/.config/systemd/user/"
  [[ -d "$src/pulse" ]] && cp -a "$src/pulse/." "$HOME/.config/pulse/"

  chmod +x "$GALROOT"/bin/* 2>/dev/null || true
  systemctl --user daemon-reload
  systemctl --user start galworld-pulse.service

  # 等 pulse 真正可用并带 audio 组，再起游戏；否则 module-alsa-source 建不出来
  local i ok=0
  for i in $(seq 1 30); do
    if pactl info >/dev/null 2>&1; then ok=1; break; fi
    sleep 1
  done
  if [[ "$ok" -eq 1 ]]; then
    local want
    want=$(getent group audio | cut -d: -f3)
    for _ in $(seq 1 10); do
      local bad=0
      for p in $(pgrep -u "$(id -u)" -f "pulseaudio --daemonize=no" 2>/dev/null); do
        grep -oP '^Groups:\s+\K.*' "/proc/$p/status" 2>/dev/null | tr ' ' '\n' | grep -qx "$want" || bad=1
      done
      [[ "$bad" -eq 0 ]] && break
      sleep 1
    done
    echo "pulseaudio 就绪"
  else
    echo "WARN: pulseaudio 未在 30s 内就绪" >&2
  fi

  for g in "$GALROOT"/games/*/; do
    local name; name=$(basename "$g")
    [[ -f "$g/game.env" ]] && systemctl --user start "galworld-game@$name.service"
  done
  sleep 5
  echo "恢复完成，当前服务："
  "$GALROOT"/bin/galworld list 2>/dev/null || true
}

case "${1:-backup}" in
  backup) do_backup ;;
  list)   do_list ;;
  restore) shift; do_restore "${1:-}" ;;
  *) usage; exit 1 ;;
esac
