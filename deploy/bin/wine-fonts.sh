#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# Gal World - Wine 前缀的中文字体兜底（幂等）
# 用法：wine-fonts.sh <wineprefix> [中文字体文件]
#
# 为什么需要:
#   Wine 按字体文件内部的 family 名注册字体。容器里通常只有文泉驿 / Droid 这类
#   中文字体，而游戏请求的是 Tahoma / MS Shell Dlg / System 这些 Windows 字体名。
#   名字找不到时 Wine 回退到内置 tahoma.ttf 和 vgasys.fon —— 它们只有拉丁字形，
#   中文要么画成方框（字体回退失败），要么在 ANSI 转换时变成 '?'（代码页问题）。
#   Windows 有 GDI 字体链接兜底，Wine 没有，所以必须显式补。
#
# 做法:
#   用 fontTools 把一份同时覆盖 ASCII + 中日韩的字体，改写成 Tahoma /
#   MS Shell Dlg / System 三个 family 名，放进前缀 Fonts 并登记注册表。
#
# 踩坑记录 (2026-11-04):
#   底字体必须同时有 ASCII 和 CJK。Droid Sans Fallback 没有 ASCII 字形，
#   用它改名会让菜单里的 "(S)" 变成三个方框。默认用文泉驿微米黑。
#
# 退出码：0 表示修复完成或无需修复；1 表示参数/前缀错误。

set -euo pipefail

PREFIX="${1:?usage: wine-fonts.sh <wineprefix> [cjk-font]}"
SRC="${2:-/usr/share/fonts/truetype/wqy/wqy-microhei.ttc}"
GALROOT="${GALWORLD_ROOT:-$HOME/galworld}"

[[ -d "$PREFIX" ]] || { echo "[wine-fonts] 前缀不存在：$PREFIX" >&2; exit 1; }

FONTS="$PREFIX/drive_c/windows/Fonts"
STAMP="$PREFIX/.galworld-fonts-v1"
USERREG="$PREFIX/user.reg"

log() { echo "[wine-fonts] $*"; }

mkdir -p "$FONTS"

# ---- 快路径：字体与注册表都齐了就退出 (每次启动都会调用，必须便宜)----
if [[ -f "$STAMP" && -f "$FONTS/tahoma.ttf" && -f "$FONTS/ms_shell_dlg.ttf" \
      && -f "$FONTS/system_cjk.ttf" ]] \
   && grep -q 'Wine\\\\Fonts\\\\Replacements' "$USERREG" 2>/dev/null \
   && grep -q '"Tahoma"="WenQuanYi Micro Hei"' "$USERREG" 2>/dev/null; then
  exit 0
fi

# ---- 1. 生成 family 名被改写的字体 ----
PY="$GALROOT/venv/bin/python"
if [[ -x "$PY" ]] && "$PY" -c 'import fontTools' >/dev/null 2>&1 && [[ -f "$SRC" ]]; then
  "$PY" - "$SRC" "$FONTS" <<'PY'
import sys
from fontTools.ttLib import TTFont

src, dst = sys.argv[1], sys.argv[2]

def load(path):
    try:
        return TTFont(path, fontNumber=0)   # .ttc
    except Exception:
        return TTFont(path)                 # .ttf / .otf

for family, fname in (("Tahoma", "tahoma.ttf"),
                      ("MS Shell Dlg", "ms_shell_dlg.ttf"),
                      ("System", "system_cjk.ttf")):
    f = load(src)
    ps = family.replace(" ", "")
    for rec in list(f["name"].names):
        nid = rec.nameID
        new = {1: family, 2: "Regular", 3: family + " Regular; " + ps,
               4: family + " Regular", 6: ps, 16: family, 17: "Regular",
               21: family, 22: "Regular"}.get(nid)
        if new is None:
            continue
        rec.string = new.encode("utf-16-be")
    f.save(dst + "/" + fname)
PY
  # Wine 读 C:\windows\Fonts 下的 vgasys.fon / svgasys.fon 作为 System 位图字体，
  # 用同样的中文 TrueType 覆盖 (Wine 按内容而不是扩展名解析)。
  cp -f "$FONTS/system_cjk.ttf" "$FONTS/vgasys.fon"
  cp -f "$FONTS/system_cjk.ttf" "$FONTS/svgasys.fon"
  log "已生成 Tahoma / MS Shell Dlg / System 中文字体 (底字体：$(basename "$SRC"))"
else
  log "WARN: 缺少 fontTools 或底字体，跳过生成 ($SRC)"
fi

# ---- 2. 注册表登记 (幂等)----
reg() {
  WINEPREFIX="$PREFIX" WINEDEBUG=-all wine reg add "$1" /v "$2" /t REG_SZ /d "$3" /f \
    >/dev/null 2>&1 || log "WARN: reg add 失败：$1\\$2"
}

FONTS_KEY='HKLM\Software\Microsoft\Windows NT\CurrentVersion\Fonts'
reg "$FONTS_KEY" 'Tahoma (TrueType)'     'C:\windows\Fonts\tahoma.ttf'
reg "$FONTS_KEY" 'MS Shell Dlg (TrueType)' 'C:\windows\Fonts\ms_shell_dlg.ttf'
reg "$FONTS_KEY" 'System (TrueType)'     'C:\windows\Fonts\system_cjk.ttf'

# Replacements 只在请求的 family 找不到时生效，用于日文系名字。
REPL_KEY='HKCU\Software\Wine\Fonts\Replacements'
for face in Tahoma 'MS Shell Dlg' 'MS Sans Serif' System Courier \
            'MS Gothic' 'MS PGothic' 'MS UI Gothic' 'Yu Gothic'; do
  reg "$REPL_KEY" "$face" 'WenQuanYi Micro Hei'
done

# ---- 3. 校验：Fonts 注册表不能指向不存在的文件 ----
# 这类脏条目会让 Wine 静默回退到内置字体，中文全部变方框，是 2026-11-04
# tenshi-reboot 排查半天的根因。
if [[ -f "$PREFIX/system.reg" ]]; then
  sed -n '/^\[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion\\\\Fonts\]/,/^\[/p' "$PREFIX/system.reg" \
    | sed -n 's/.*="C:\\\\windows\\\\Fonts\\\\\([^"]*\)".*/\1/p' \
    | while IFS= read -r fn; do
        [[ -f "$FONTS/$fn" ]] || log "WARN: 字体注册表指向不存在的文件：$fn"
      done
fi

touch "$STAMP"
log "完成：$PREFIX"
