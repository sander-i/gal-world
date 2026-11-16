#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# Gal World - 启动一个游戏的完整会话
# 用法: run-game.sh <game-id>
#
# 链路:
#   Xvfb :<disp>          ← wine 渲染（软件渲染 llvmpipe）
#   wine → ALSA hw:0,0,<slot> → snd-aloop → hw:0,1,<slot> → pulse source gal_<id>
#   pulse（外部常驻，由 pulse-setup.sh 管理）+ selkies（每游戏一个端口）
#
# 关键约束（实测得出，勿改）:
#   1. ALSA_CONFIG_PATH 必须指向不含 pulse 设备的配置，
#      否则 winealsa 枚举 32 位 pulse 插件时游戏确定性崩溃
#      1FB9A7A5 读 0000004C
#   2. WINEDLLOVERRIDES 禁用 winepulse.drv / winecoreaudio.drv
#   3. 工作目录必须是游戏目录（pfs 相对路径）

set -euo pipefail

ID="${1:?usage: run-game.sh <game-id>}"

# ---- 0. 确保拥有 audio 组（否则 wine 打不开 /dev/snd，报 -13 Permission denied）----
# systemd --user 的 manager 在用户加入 audio 组之前就启动了，其补充组集合被固化，
# 由它拉起的服务进程没有 audio 组。不能用 SupplementaryGroups=audio
# （manager 无权 setgroups → status=216/GROUP 直接失败）。
# 用 sg 重新执行自身：只在这个进程树内补上 audio 组，不需要 root。
if ! id -nG | tr ' ' '\n' | grep -qx audio; then
  if command -v sg >/dev/null 2>&1; then
    exec sg audio -c "$(printf '%q ' "$0" "$@")"
  else
    echo "WARN: not in audio group and 'sg' unavailable; audio will fail" >&2
  fi
fi

GALROOT="${GALWORLD_ROOT:-$HOME/galworld}"
DIR="$GALROOT/games/$ID"
ENVFILE="$DIR/game.env"

[[ -r "$ENVFILE" ]] || { echo "missing $ENVFILE" >&2; exit 1; }
# shellcheck disable=SC1090
set -a; . "$ENVFILE"; set +a

: "${DISPLAY_NUM:?}" "${PORT:?}" "${EXE:?}" "${WORKDIR:?}" "${WINEPREFIX:?}"
: "${WIDTH:=1280}" "${HEIGHT:=720}" "${LOOPBACK_SLOT:=0}" "${LOOPBACK_CARD:=0}" "${FRAMERATE:=30-30}"
# 早期手工注册的 game.env 可能没有 EXTRA_ENV；set -u 下缺键会让整个脚本退出
: "${EXTRA_ENV:=}"
: "${VIDEO_CRF:=12}" "${VIDEO_BITRATE:=40000,5000-80000}" "${KEYFRAME_INTERVAL:=60}" "${VIDEO_PAINTOVER_CRF:=12}"

# ---- wine 选择（必须在 source game.env 之后）----
# 顺序：game.env 的 WINE_HOME → 部署包解压的 <root>/.local/wine → 系统 PATH 里的 wine。
#
# 为什么要 WINE_HOME：旧式 WoW64 的 wine 把 32 位游戏跑在 32 位 Linux 进程里，
# 地址空间只有 4 GiB，其中 Wine 自身预留约 2.5 GiB。大型 32 位 galgame（如
# SenrenBanka）在 D3D 初始化时会把它顶满，wined3d 申请 64 MiB GL buffer 失败后
# 空指针写 → "has encountered a serious problem and needs to close"。
# 用 --enable-archs=i386,x86_64 编出的 wow64 Wine 让 32 位 PE 跑进 64 位进程，
# 上限消失。这类构建由一键部署的「构建 wow64 Wine」步骤装到 <root>/.local/wine11，
# 需要它的游戏在 game.env 里写 WINE_HOME=<root>/.local/wine11。
# 不前置就会 "wine: command not found"（2026-11-09 全新机器实测）。
: "${WINE_HOME:=}"
if [[ -n "$WINE_HOME" && -x "$WINE_HOME/bin/wine" ]]; then
  export PATH="$WINE_HOME/bin:$PATH"
elif [[ -d "$GALROOT/.local/wine/bin" ]]; then
  export PATH="$GALROOT/.local/wine/bin:$PATH"
fi

LOGFILE="$GALROOT/data/logs/$ID.log"
mkdir -p "$(dirname "$LOGFILE")"
exec >>"$LOGFILE" 2>&1

echo "[$(date '+%F %T')] start game=$ID display=:$DISPLAY_NUM port=$PORT slot=$LOOPBACK_SLOT"

PIDS=()
# 释放本游戏占用的 pulse 模块，把 snd-aloop 的 subdevice 还回可用池。
#
# 为什么必须做：snd-aloop 每张卡只有 8 个 subdevice（pcm_substreams 参数上限
# 就是 1-8），而 module-alsa-source 会长期占用 hw:0,1,<slot>。只加载不卸载的话，
# 跑过 8 个游戏之后第 9 个的 slot 越界，采集端打不开：
#   Failed to load module "module-alsa-source" (device=hw:0,1,8): initialization failed
# 游戏随后在播放 BGM 时 sound.tjs(42) super.play() 抛异常并退出，
# 日志里只剩 status=1/FAILURE（2026-11-14 game-6ea85b30 实测）。
# 门户停止会话走 systemctl stop → SIGTERM → 本 trap，所以清理要放这里，
# 不能只放在 stop-game.sh（那条路径只有命令行 galworld stop 才会走）。
release_audio() {
  command -v pactl >/dev/null 2>&1 || return 0
  pactl info >/dev/null 2>&1 || return 0
  local src="gal_${ID}_in" snk="gal_${ID}" m
  pactl list short modules 2>/dev/null \
    | awk -v s="$src" -v k="$snk" '
        $0 ~ /module-loopback/ && $0 ~ ("source=" s) && $0 ~ ("sink=" k) {print $1}
        $0 ~ /module-alsa-source/ && $0 ~ ("source_name=" s) {print $1}
        $0 ~ /module-null-sink/ && $0 ~ ("sink_name=" k) {print $1}
      ' \
    | while read -r m; do
        [[ -n "$m" ]] && pactl unload-module "$m" 2>/dev/null || true
      done
}

# ---- 优雅关闭：先请游戏自己退出，让它把存档写盘 ----
#
# 门户停止会话走 systemctl stop → SIGTERM → 本脚本的 trap，不走 stop-game.sh
# （那个只有命令行 galworld stop 会用）。所以优雅关闭必须放在这里。
# unit 配的是 KillMode=mixed + TimeoutStopSec=25：SIGTERM 只发给本脚本，
# 我们等游戏退出后自己收尾；超过 25s systemd 才会整组 SIGKILL。
#
# 直接杀 wine 会丢掉未落盘的进度，所以先发 WM_DELETE_WINDOW，
# 宽限期内每 3 秒重发（加载阶段会忽略关闭消息）。
GRACE_SECONDS="${GALWORLD_STOP_GRACE_SECONDS:-15}"

# 向所有「像游戏窗口」的窗口发关闭请求（面积 > 100000，排除光标/隐藏窗口）
request_game_close() {
  command -v xdotool >/dev/null 2>&1 || return 1
  command -v xwininfo >/dev/null 2>&1 || return 1
  local w area sent=1
  for w in $(xwininfo -root -children 2>/dev/null | awk '/^ +0x[0-9a-f]+/ {print $1}'); do
    area=$(xwininfo -id "$w" 2>/dev/null | awk '/Width:/{x=$2} /Height:/{y=$2} END{print (x&&y)?x*y:0}')
    if [[ "${area:-0}" -gt 100000 ]]; then
      xdotool windowclose "$w" 2>/dev/null || true
      sent=0
    fi
  done
  return $sent
}

graceful_close() {
  # wine 已经退出（正常结束 / 启动失败）就不用再等
  [[ -n "${WINE_PID:-}" ]] || return 0
  kill -0 "$WINE_PID" 2>/dev/null || return 0
  request_game_close || return 0
  echo "[$(date '+%F %T')] 已请求游戏关闭，等待存盘退出" >&2
  local i
  for i in $(seq 1 "$GRACE_SECONDS"); do
    sleep 1
    if ! kill -0 "$WINE_PID" 2>/dev/null; then
      echo "[$(date '+%F %T')] 游戏已正常退出" >&2
      return 0
    fi
    # 加载阶段会忽略关闭消息，隔几秒重发一次
    if [[ $((i % 3)) -eq 0 ]]; then
      request_game_close || true
    fi
  done
  echo "[$(date '+%F %T')] 游戏未在 ${GRACE_SECONDS}s 内退出，强制结束" >&2
  return 0
}

cleanup() {
  graceful_close
  local p
  for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done
  release_audio
}
trap cleanup EXIT TERM INT

# ---- 1. 虚拟显示 ----
# 屏幕必须等于游戏分辨率（小屏）：
#   - 大屏 3840x2160 会让 32 位 Wine 地址空间耗尽（实测 1300+ 次 mmap 失败）
#     → GStreamer 加载失败 → 游戏崩溃 → 无声音
#   - 小屏 1280x720 + Decorated=N（无标题栏）→ 窗口 1228x720 完整可见、音频正常
SCREEN_W="${SCREEN_W:-$WIDTH}"
SCREEN_H="${SCREEN_H:-$HEIGHT}"
if ! xdpyinfo -display ":$DISPLAY_NUM" >/dev/null 2>&1; then
  # 清掉崩溃残留的锁与 socket，否则 Xvfb 拒绝启动
  rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true
  Xvfb ":$DISPLAY_NUM" -screen 0 "${SCREEN_W}x${SCREEN_H}x24" -nolisten tcp >/dev/null 2>&1 &
  PIDS+=($!)
  for _ in $(seq 1 40); do
    xdpyinfo -display ":$DISPLAY_NUM" >/dev/null 2>&1 && break
    sleep 0.25
  done
  if ! xdpyinfo -display ":$DISPLAY_NUM" >/dev/null 2>&1; then
    echo "ERROR: Xvfb :$DISPLAY_NUM failed to start" >&2
    exit 1
  fi
fi
export DISPLAY=":$DISPLAY_NUM"

# ---- 2. ALSA 配置 ----
# 极简 ALSA_CONFIG_PATH，只定义 default 指向本游戏独占的 loopback 子设备。
# 绝不能定义 pcm.pulse：它和 default 指向同一 subdevice 时，winealsa 会把两者
# 当成两个设备分别打开，第二次报 "Device or resource busy" (-16) → 无声音。
# 不定义 pulse 也就不会加载 libasound 的 pulse 插件（32 位下会 Protocol error）。
unset XDG_CONFIG_HOME_OVERRIDE
ALSA_CONF="$GALROOT/data/alsa-$ID.conf"
cat > "$ALSA_CONF" <<EOF
pcm.!default {
    type hw
    card $LOOPBACK_CARD
    device 0
    subdevice $LOOPBACK_SLOT
}
ctl.!default {
    type hw
    card $LOOPBACK_CARD
}
EOF
export ALSA_CONFIG_PATH="$ALSA_CONF"

# ---- 3. wine 环境 ----
export WINEPREFIX WINEDEBUG="${WINEDEBUG_OVERRIDE:--all}"
# mscoree/mshtml 置 d：新建前缀时 wine 会弹「Wine Mono Installer / Gecko」对话框，
# 无头环境里没人点，游戏永远起不来。Kirikiri 等 GAL 引擎不需要 .NET。
export WINEDLLOVERRIDES="winecoreaudio.drv=d;winepulse.drv=d;mscoree=d;mshtml=d"
export LIBGL_ALWAYS_SOFTWARE=1
# 中文显示：字体由 wine-fonts.sh 补齐（见步骤 5 之前）；locale 缺省 C.UTF-8。
# 需要 CP936 的汉化补丁（game.env 里设 GAME_LANG=zh_CN.UTF-8）时，容器通常没有
# 生成 zh_CN locale，这里用用户级 localedef 兜底，否则 ANSI 文本会全变成 '?'。
export LANG="${GAME_LANG:-C.UTF-8}"
case "$LANG" in
  C|C.UTF-8|C.utf8|POSIX) ;;
  *)
    loc_base="${LANG%%.*}"
    if ! locale -a 2>/dev/null | tr 'A-Z' 'a-z' | grep -qx "${loc_base,,}.utf8"; then
      LOCROOT="${XDG_DATA_HOME:-$HOME/.local/share}/galworld/locale"
      if [[ ! -d "$LOCROOT/$loc_base.UTF-8" ]]; then
        mkdir -p "$LOCROOT"
        localedef -i "$loc_base" -f UTF-8 "$LOCROOT/$loc_base.UTF-8" >/dev/null 2>&1 || true
      fi
      [[ -d "$LOCROOT/$loc_base.UTF-8" ]] && export LOCPATH="$LOCROOT"
    fi
    ;;
esac
export PULSE_SERVER="${PULSE_SERVER:-unix:${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/pulse/native}"

# ---- 4. 音频链 ----
# snd-aloop 配对: 播放到 device0/subdeviceN → 从 device1/subdeviceN 采集。
# 同一 subdevice 的播放端与采集端互斥：pulse 若占用了 hw:0,0,N 做采集，
# wine 就打不开同 subdevice 播放，报 "Device or resource busy" (-16)。
# 所以只采集 hw:0,1,<slot>，绝不能碰 hw:0,0,<slot>。
AUDIO_ARGS=()
if pactl info >/dev/null 2>&1; then
  SNK="gal_${ID}"
  SRC="gal_${ID}_in"

  pactl list short sinks 2>/dev/null | grep -qw "$SNK" || \
    pactl load-module module-null-sink sink_name="$SNK" rate=44100 channels=2 >/dev/null 2>&1 || true

  pactl list short sources 2>/dev/null | grep -qw "$SRC" || \
    pactl load-module module-alsa-source device="hw:${LOOPBACK_CARD},1,${LOOPBACK_SLOT}" \
      source_name="$SRC" channels=2 rate=44100 tsched=1 >/dev/null 2>&1 || true

  # loopback 必须幂等：先按 pulse 实际加载的模块判断，不能用文件标记
  # （标记和真实状态会脱节，导致同一 source→sink 叠建多次，
  #   相位相消后振幅被抵消，表现为"有声音但极小声/没声音"）
  lb_count=$(pactl list short modules 2>/dev/null \
    | awk -v s="$SRC" -v k="$SNK" '$0 ~ /module-loopback/ && $0 ~ ("source=" s) && $0 ~ ("sink=" k)' \
    | wc -l)
  if [[ "$lb_count" -eq 0 ]]; then
    pactl load-module module-loopback source="$SRC" sink="$SNK" latency_msec=100 \
      adjust_time=10 source_dont_move=true sink_dont_move=true >/dev/null 2>&1 || true
  elif [[ "$lb_count" -gt 1 ]]; then
    # 多余的重复 loopback 全部卸载，只留一条
    pactl list short modules 2>/dev/null \
      | awk -v s="$SRC" -v k="$SNK" '$0 ~ /module-loopback/ && $0 ~ ("source=" s) && $0 ~ ("sink=" k) {print $1}' \
      | tail -n +2 | while read -r m; do pactl unload-module "$m" 2>/dev/null || true; done
  fi

  SOURCE="gal_${ID}.monitor"
  if pactl list short sources 2>/dev/null | grep -qw "$SOURCE"; then
    AUDIO_ARGS=(--audio-enabled --audio-device-name "$SOURCE")
  else
    echo "WARN: pulse source '$SOURCE' missing; audio disabled"
  fi
else
  echo "WARN: pulseaudio not reachable; audio disabled"
fi

# ---- 5. wine ----
# 清掉上次会话残留的 wineserver：门户停服务时 systemd 常留下 wineserver64，
# 新会话会附着到这个旧 server（带着旧的 locale / 字体状态），
# 表现为一启动就弹全是 ? 的错误框然后退出（2026-11-05 demo-game 实测）。
WINEPREFIX="$WINEPREFIX" wineserver -k >/dev/null 2>&1 || true
# 字体兜底：Wine 找不到 Tahoma/System 这类 Windows family 时会回退到只有拉丁
# 字形的内置字体，中文变方框。wine-fonts.sh 幂等补齐（第二次起约 10ms）。
FONT_FIX="$(dirname "$0")/wine-fonts.sh"
if [[ -x "$FONT_FIX" ]]; then
  "$FONT_FIX" "$WINEPREFIX" || echo "WARN: wine-fonts.sh 失败，继续启动" >&2
else
  echo "WARN: 找不到 wine-fonts.sh，中文可能显示为方框" >&2
fi

# ---- 5a. 子目录 exe 的资源补齐 ----
# 汉化补丁常把启动程序放在 补丁/<补丁名>/，而 xp3 与 plugin/ 留在游戏根目录。
# Kirikiri 按 exe 所在目录找资源，缺 plugin/extrans.dll 会在 initialize.tjs
# 抛异常后直接退出（日志只有 Exit code: 0，很难定位）。
# game-assets.sh 幂等地把工作目录顶层缺失项以符号链接补进 exe 目录。
ASSETS_FIX="$(dirname "$0")/game-assets.sh"
if [[ -x "$ASSETS_FIX" ]]; then
  "$ASSETS_FIX" "$WORKDIR" "$(dirname "$EXE")" \
    || echo "WARN: game-assets.sh 失败，继续启动" >&2
fi

cd "$WORKDIR"
# 启动后盯 msgbox：补丁自带的启动器常依赖私有 DLL（如 KrkrUniversalPatch.dll），
# 缺了会弹一个空标题的 MessageBox 并卡住等人点按钮——进程活着、端口在听，
# 但画面永远停在白框上，从门户看就是「RUNNING 却没画面」。
# 单独开 +msgbox 通道（其余仍关掉），抓到就写一条明确的 WARN，
# 否则这类问题只能靠逐层加 WINEDEBUG 手工排查。
MSGBOX_LOG="$GALROOT/data/logs/.msgbox-$ID.log"
rm -f "$MSGBOX_LOG"
# shellcheck disable=SC2086
WINEDEBUG="${WINEDEBUG_OVERRIDE:--all},+msgbox" wine "$EXE" $EXTRA_ENV 2> >(tee -a "$MSGBOX_LOG" >&2) &
WINE_PID=$!
PIDS+=("$WINE_PID")

# 后台检查：出现 MSGBOX_OnInit 说明游戏被对话框拦住了
(
  for _ in $(seq 1 60); do
    sleep 1
    [[ -s "$MSGBOX_LOG" ]] || continue
    line=$(grep -m1 -a "msgbox:MSGBOX_OnInit" "$MSGBOX_LOG" 2>/dev/null || true)
    [[ -n "$line" ]] || continue
    echo "[$(date '+%F %T')] WARN: 游戏弹出阻塞对话框，画面会一直停在这里：" >&2
    echo "  ${line#*MSGBOX_OnInit }" >&2
    echo "  常见原因：补丁 exe 依赖的私有 DLL 缺失（如 KrkrUniversalPatch.dll）、" >&2
    echo "  游戏版本与补丁不匹配。可尝试在门户编辑页改用游戏根目录的原版 exe。" >&2
    break
  done
) &
PIDS+=($!)

# ---- 5a2. 补丁 / 汉化组的更新弹窗：自动选「否」 ----
#
# 部分汉化补丁的启动器开场会弹「检测到新版本，是否更新？」。这类弹窗挡在
# 游戏前面，串流画面一直停在弹窗上，玩家在浏览器里也点不到它 —— 会话看起来
# 像是卡死了。这里在启动阶段盯着窗口，标题命中更新类关键词就发 Esc
# （Windows 对话框里 Esc 等价于「取消 / 否」）。
#
# 只在启动阶段盯，进画面后不再扫，避免误关游戏自己的窗口。
#
# 默认**关闭**（GALWORLD_DISMISS_UPDATE_DIALOG=1 才启用）：
# Esc 是否真的落在「否」上取决于弹窗的按钮定义，实际用下来有误触风险，
# 先停用。要试的话把环境变量设成 1。
if [[ "${GALWORLD_DISMISS_UPDATE_DIALOG:-0}" == "1" ]] && command -v xdotool >/dev/null 2>&1; then
  (
    for _ in $(seq 1 60); do
      sleep 2
      for w in $(xdotool search --onlyvisible --name '.+' 2>/dev/null || true); do
        name=$(xdotool getwindowname "$w" 2>/dev/null || true)
        case "$name" in
          *更新*|*升级*|*新版本*|*アップデート*|*バージョン*|*Update*|*update*)
            echo "[$(date '+%F %T')] 自动取消更新弹窗: $name" >&2
            # 没有窗口管理器，先显式聚焦再发键，保证 Esc 落在对话框上
            xdotool windowfocus "$w" 2>/dev/null || true
            xdotool key Escape 2>/dev/null || true
            sleep 1
            ;;
        esac
      done
    done
  ) &
  PIDS+=($!)
fi

# ---- 5b. 窗口定位 ----
# 把游戏主窗口居中到屏幕（屏幕 WIDTHxHEIGHT，窗口通常略小）。
# 不移动到负坐标：窗口部分移出屏幕时游戏会判定不可见并暂停 BGM。
if command -v xdotool >/dev/null 2>&1; then
  (
    while :; do
      best=0; bestid=""
      for w in $(xdotool search --pid "$WINE_PID" 2>/dev/null || true); do
        area=$(xwininfo -id "$w" 2>/dev/null | awk '/Width:/{w=$2} /Height:/{h=$2} END{print (w&&h)?w*h:0}')
        if [[ "${area:-0}" -gt "$best" ]]; then best="$area"; bestid="$w"; fi
      done
      if [[ -n "$bestid" && "$best" -gt 100000 ]]; then
        read -r ww wh <<<"$(xwininfo -id "$bestid" 2>/dev/null | awk '/Width:/{w=$2} /Height:/{h=$2} END{print w, h}')"
        # 屏幕高度不够时 Wine 会把超屏窗口钳到 SCREEN_H-20，超出的部分直接裁掉（不缩放），
        # 表现为游戏底部画面缺失（2026-11-05 demo-game 实测：720 屏 → 窗口 700、底部丢 72px）。
        # 这里只告警不自动改：改 HEIGHT 需要重启会话，交给门户接入流程或人工处理。
        clamp_key="${ww:-0}x${wh:-0}"
        if [[ "${wh:-0}" -ge $(( SCREEN_H - 20 )) && "$clamp_key" != "${CLAMP_WARNED:-}" ]]; then
          CLAMP_WARNED="$clamp_key"
          sug=$(( ((wh + 21 + 7) / 8) * 8 ))
          echo "[$(date '+%T')] WARN: 窗口 $clamp_key 顶到屏幕高 $SCREEN_H（Wine 钳制上限 $((SCREEN_H - 20))），底部画面可能被裁；建议先设 HEIGHT=$sug，仍被钳制就继续加（门户接入会自动试探）"
        fi
        tx=$(( (WIDTH - ${ww:-WIDTH}) / 2 )); [[ $tx -lt 0 ]] && tx=0
        ty=$(( (HEIGHT - ${wh:-HEIGHT}) / 2 )); [[ $ty -lt 0 ]] && ty=0
        cur=$(xwininfo -id "$bestid" 2>/dev/null | awk '/Absolute upper-left X:/{x=$4} /Absolute upper-left Y:/{y=$4} END{print x","y}')
        if [[ "$cur" != "$tx,$ty" ]]; then
          xdotool windowmove --sync "$bestid" "$tx" "$ty" 2>/dev/null || true
          echo "[$(date '+%T')] window $bestid moved $cur -> $tx,$ty (size ${ww}x${wh})"
        fi
      fi
      sleep 2
    done
  ) &
  PIDS+=($!)
else
  echo "WARN: xdotool not found, window may be off-screen"
fi

# ---- 6. selkies ----
SELKIES="$GALROOT/venv/bin/selkies"
CERT="$GALROOT/data/certs/server.crt"
KEY="$GALROOT/data/certs/server.key"

# 串流只对本机开放，由门户反向代理对外提供。
#
# 背景：Selkies 原本用 HTTP Basic 认证，但门户把串流页放在 iframe 里，
# Basic 会触发浏览器原生登录弹窗，无法用样式覆盖。关掉认证后端口就裸奔，
# 局域网任何人都能直连 9002 看游戏画面。
#
# 现在的做法：
#   1. --addr 127.0.0.1  → 只监听回环，外部扫 9002 直接连不上
#   2. --subfolder /stream/<id>  → 所有路由（含静态资源与 WebSocket）带前缀，
#      门户的 StreamProxyController / StreamWsBridge 原样转发，路径不用重写
#   3. --enable-basic-auth=false  → iframe 内无弹窗（外部已进不来，不需要认证）
#
# 访问链路：浏览器 → 门户 /stream/<id>/ → 127.0.0.1:<port>/stream/<id>/
#
# 注意：iframe 与门户同源，浏览器侧不带任何凭据，抓包也拿不到可复用的东西。
"$SELKIES" --addr 127.0.0.1 --port "$PORT" \
  --enable-https --https-cert "$CERT" --https-key "$KEY" \
  --enable-basic-auth=false \
  --subfolder "/stream/$ID" \
  --framerate "$FRAMERATE" \
  --manual-resolution --manual-width "$WIDTH" --manual-height "$HEIGHT" \
  --rate-control-mode crf --video-crf "${VIDEO_CRF:-12}" \
  --video-paintover-crf "${VIDEO_PAINTOVER_CRF:-12}" \
  --paint-over-jpeg-quality 95 \
  --video-bitrate "${VIDEO_BITRATE:-40000,5000-80000}" \
  --keyframe-interval "${KEYFRAME_INTERVAL:-60}" \
  "${AUDIO_ARGS[@]}" \
  --audio-on-start true \
  --ui-title "$GAME_NAME" \
  --frame-ancestors "${FRAME_ANCESTORS:-*}" \
  ${SELKIES_DEBUG:+--debug} &
PIDS+=($!)

# 对外可达地址：取本机第一个非环回 IPv4，取不到就退回 localhost（仅用于日志）
HOST_IP="$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -vE '^(127\.|::)' | head -1 || true)"
echo "[$(date '+%F %T')] up: https://${HOST_IP:-localhost}:$PORT/"

wait "$WINE_PID"
echo "[$(date '+%F %T')] wine exited, stopping session"
