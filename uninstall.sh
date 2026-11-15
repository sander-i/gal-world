#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# Gal World —— 彻底卸载脚本（与 deploy.sh 同级）
#
# 与后台「一键卸载环境」的区别：页面的卸载只清到「门户还能跑」，方便切换环境；
# 这个脚本是连门户一起销毁，机器回到装之前的裸状态。
#
# 用法:
#   ./uninstall.sh [--root ~/galworld] [--unit galworld-portal] [--port 8088]
#                  [--yes] [--dry-run] [--keep-packages] [--keep-data]
#
#   --dry-run        只打印将要执行的动作，不做任何修改
#   --yes            跳过交互确认（用于脚本化；确认词见下）
#   --keep-packages  保留系统包与 i386 架构（默认全部 purge）
#   --keep-data      保留 ~/galworld/data（数据库、证书、日志）与 games
#
# 删除范围（默认全量）：
#   1. 门户 / 音频 / 游戏 systemd 用户单元，正在跑的游戏进程
#   2. ~/galworld 整个目录（含游戏与存档）、~/.config/galworld（元件仓库账号）
#   3. 安装时上传到 ~ 的 gal-world-portal.jar / deploy.sh / deploy-run*.log
#   4. snd-aloop 加载项、audio 组关系、linger
#   5. xvfb / PulseAudio / python3-venv / 中文字体 / 全部 i386 包，撤销 i386 架构
#   6. ufw 里 deploy.sh 放过行的门户端口
#   7. 提权入口 /usr/local/sbin/galworld-sudo 与 /etc/sudoers.d/galworld-deploy
#
# 不可逆：第 2 步会删掉 games/ 与存档。脚本默认要求手工输入「销毁」两个字确认。
# 清理干净后脚本会删除自身；还有残留时保留，方便重跑排查。

set -euo pipefail

ROOT="${GALWORLD_ROOT:-$HOME/galworld}"
UNIT="${GALWORLD_UNIT:-galworld-portal}"
PORT="${GALWORLD_PORTAL_PORT:-8088}"
DRY=0
YES=0
KEEP_PACKAGES=0
KEEP_DATA=0

say()  { printf '\n== %s\n' "$*"; }
fail() { printf '\n!! %s\n' "$*" >&2; exit 1; }
step() { if [[ "$DRY" -eq 1 ]]; then printf '   [dry] %s\n' "$*"; else printf '   %s\n' "$*"; fi; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root)          ROOT="${2:?--root 需要路径}"; shift 2 ;;
    --unit)          UNIT="${2:?--unit 需要单元名}"; shift 2 ;;
    --port)          PORT="${2:?--port 需要端口}"; shift 2 ;;
    --yes)           YES=1; shift ;;
    --dry-run)       DRY=1; shift ;;
    --keep-packages) KEEP_PACKAGES=1; shift ;;
    --keep-data)     KEEP_DATA=1; shift ;;
    -h|--help)       sed -n '2,/^set -euo pipefail$/p' "$0" | sed '$d'; exit 0 ;;
    *)               fail "未知参数: $1" ;;
  esac
done

# ---------- 0. 环境检查 ----------
say "环境检查"
[[ "$(id -u)" -ne 0 ]] || fail "请用门户运行账号执行（本脚本用 systemctl --user 停单元，root 下会操作错用户）"
command -v systemctl >/dev/null || fail "缺少 systemctl"
REAL_USER="$(id -un)"

SUDO=(sudo)
if ! sudo -n true 2>/dev/null; then
  echo "需要 sudo 权限完成系统级清理，下面可能要求输入密码。"
fi

echo "安装根目录: $ROOT"
echo "门户单元:   $UNIT"
echo "运行账号:   $REAL_USER"
[[ "$KEEP_PACKAGES" -eq 1 ]] && echo "系统包:     保留（--keep-packages）" || echo "系统包:     purge"
[[ "$KEEP_DATA" -eq 1 ]] && echo "data/games: 保留（--keep-data）" || echo "data/games: 删除（不可恢复）"
[[ "$DRY" -eq 1 ]] && echo "模式:       仅预览（--dry-run）"

# ---------- 1. 确认 ----------
if [[ "$DRY" -eq 0 && "$YES" -eq 0 ]]; then
  echo
  echo "即将删除 $ROOT 下的游戏与存档，且不可恢复。"
  printf '确认请手打「销毁」后回车，其它输入取消: '
  read -r answer
  [[ "$answer" = "销毁" ]] || { echo "已取消"; exit 1; }
fi

# ---------- 2. 停服务与进程 ----------
say "停止门户与游戏会话"
for u in "$UNIT" galworld-pulse "galworld-game@*.service"; do
  if [[ "$DRY" -eq 1 ]]; then
    step "systemctl --user stop/disable $u"
  else
    systemctl --user stop "$u" 2>/dev/null || true
    systemctl --user disable "$u" 2>/dev/null || true
  fi
done
if [[ "$DRY" -eq 0 ]]; then
  pkill -f "[r]un-game.sh" 2>/dev/null || true
  pkill -f "[s]elkies" 2>/dev/null || true
  pkill -f "[X]vfb :" 2>/dev/null || true
fi
step "结束 run-game.sh / selkies / Xvfb 残留进程"

# ---------- 3. 删单元文件 ----------
say "删除 systemd 用户单元"
for f in "$HOME/.config/systemd/user/$UNIT.service" \
         "$HOME/.config/systemd/user/galworld-pulse.service" \
         "$HOME/.config/systemd/user/galworld-game@.service"; do
  if [[ -e "$f" || "$DRY" -eq 1 ]]; then
    step "rm -f $f"
    [[ "$DRY" -eq 1 ]] || rm -f "$f"
  fi
done
if [[ "$DRY" -eq 1 ]]; then
  step "systemctl --user daemon-reload"
else
  systemctl --user daemon-reload || true
  systemctl --user reset-failed 2>/dev/null || true
fi

# ---------- 4. 删用户目录 ----------
say "删除安装目录与配置"
if [[ "$KEEP_DATA" -eq 1 ]]; then
  for d in bin lib logs venv .local; do
    step "rm -rf $ROOT/$d"
    [[ "$DRY" -eq 1 ]] || rm -rf "$ROOT/$d"
  done
else
  step "rm -rf $ROOT"
  [[ "$DRY" -eq 1 ]] || rm -rf "$ROOT"
fi
step "rm -rf $HOME/.config/galworld"
[[ "$DRY" -eq 1 ]] || rm -rf "$HOME/.config/galworld"

say "删除安装时上传的临时文件"
for f in "$HOME/gal-world-portal.jar" "$HOME/deploy.sh"; do
  if [[ -e "$f" ]]; then
    step "rm -f $f"
    [[ "$DRY" -eq 1 ]] || rm -f "$f"
  fi
done
shopt -s nullglob
for f in "$HOME"/deploy-run*.log; do
  step "rm -f $f"
  [[ "$DRY" -eq 1 ]] || rm -f "$f"
done
shopt -u nullglob

# ---------- 5. 系统级清理 ----------
say "回滚系统级配置"
step "sudo rm -f /etc/modules-load.d/snd-aloop.conf"
step "sudo modprobe -r snd-aloop"
step "sudo gpasswd -d $REAL_USER audio"
step "sudo loginctl disable-linger $REAL_USER"
if [[ "$DRY" -eq 0 ]]; then
  "${SUDO[@]}" rm -f /etc/modules-load.d/snd-aloop.conf
  "${SUDO[@]}" modprobe -r snd-aloop 2>/dev/null || true
  "${SUDO[@]}" gpasswd -d "$REAL_USER" audio 2>/dev/null || true
  "${SUDO[@]}" loginctl disable-linger "$REAL_USER" 2>/dev/null || true
fi

if [[ "$KEEP_PACKAGES" -eq 0 ]]; then
  say "卸载系统依赖"
  PKGS=(x11-utils xvfb xdotool pulseaudio pulseaudio-utils python3-venv fonts-wqy-microhei)
  step "sudo apt-get purge -y ${PKGS[*]}"
  step "sudo apt-get purge -y --allow-remove-essential <全部 :i386 包>"
  step "sudo dpkg --remove-architecture i386"
  step "sudo apt-get autoremove -y"
  if [[ "$DRY" -eq 0 ]]; then
    if command -v apt-get >/dev/null 2>&1; then
      "${SUDO[@]}" env DEBIAN_FRONTEND=noninteractive apt-get purge -y -q "${PKGS[@]}" || echo "警告：部分包卸载失败"
      i386_pkgs="$("${SUDO[@]}" dpkg -l | awk '/^ii .*:i386/ {print $2}')"
      if [[ -n "$i386_pkgs" ]]; then
        # shellcheck disable=SC2086
        "${SUDO[@]}" env DEBIAN_FRONTEND=noninteractive apt-get purge -y -q --allow-remove-essential $i386_pkgs \
          || echo "警告：部分 i386 包卸载失败"
      fi
      "${SUDO[@]}" dpkg --remove-architecture i386 2>/dev/null || true
      "${SUDO[@]}" apt-get autoremove -y -q || true
    else
      echo "非 Debian/Ubuntu 系：跳过系统包卸载，请自行确认已清理 ${PKGS[*]} 与 i386 运行库"
    fi
  fi
fi

if command -v ufw >/dev/null 2>&1 \
   && "${SUDO[@]}" -n ufw status 2>/dev/null | head -1 | grep -q 'Status: active'; then
  say "收回防火墙放行"
  step "sudo ufw delete allow $PORT/tcp"
  [[ "$DRY" -eq 1 ]] || "${SUDO[@]}" ufw delete allow "$PORT/tcp" >/dev/null 2>&1 || true
fi

# ---------- 6. 删提权入口（最后做，删完就没有免密路径了） ----------
say "删除提权入口"
step "sudo rm -f /etc/sudoers.d/galworld-deploy"
step "sudo rm -f /usr/local/sbin/galworld-sudo"
if [[ "$DRY" -eq 0 ]]; then
  "${SUDO[@]}" rm -f /etc/sudoers.d/galworld-deploy
  "${SUDO[@]}" rm -f /usr/local/sbin/galworld-sudo
fi

# ---------- 7. 校验 ----------
if [[ "$DRY" -eq 1 ]]; then
  echo
  echo "预览结束，未做任何修改。去掉 --dry-run 即执行。"
  exit 0
fi

say "残留检查"
left=0
check_absent() {
  if [[ -e "$1" ]]; then
    echo "   仍在: $1"; left=1
  fi
  return 0
}
[[ "$KEEP_DATA" -eq 1 ]] || check_absent "$ROOT"
check_absent "$HOME/.config/galworld"
check_absent "$HOME/.config/systemd/user/$UNIT.service"
check_absent "$HOME/.config/systemd/user/galworld-pulse.service"
check_absent "$HOME/.config/systemd/user/galworld-game@.service"
check_absent "/usr/local/sbin/galworld-sudo"
check_absent "/etc/sudoers.d/galworld-deploy"

systemctl --user is-active "$UNIT" >/dev/null 2>&1 && { echo "   门户仍在运行"; left=1; }
if [[ "$KEEP_PACKAGES" -eq 0 ]] && dpkg --print-foreign-architectures 2>/dev/null | grep -qx i386; then
  echo "   i386 架构仍在"; left=1
fi
if [[ "$left" -eq 0 ]]; then
  echo "   无残留：gal-world 已从本机彻底移除"
  # 自删只在清干净时做：还有残留时保留脚本，方便重跑排查
  SELF="$(readlink -f "$0" 2>/dev/null || printf '%s' "$0")"
  if [[ -f "$SELF" ]]; then
    rm -f "$SELF" && echo "   已删除卸载脚本自身: $SELF"
  fi
else
  echo "   有残留，见上；重跑本脚本或手工清理（脚本已保留）"
fi
