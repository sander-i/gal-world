#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# Gal World Portal —— Linux 一键引导脚本
#
# 一次准备基础环境：系统依赖 → JDK → 目录 → jar → 自签证书 → systemd 用户单元 → linger →
# 防火墙 → 健康检查。中间的 Wine / Selkies / Sunshine / 音频链等一律走页面「一键部署」，不在这里做。
#
# 用法:
#   ./deploy.sh [--jar <path>] [--root ~/galworld] [--port 8088]
#               [--no-tls] [--skip-cert] [--force-cert] [--skip-deps] [--allow-sudo-password]
#               [--token <部署令牌>] [--no-sudoers]
#               [--artifact-nexus <url> --artifact-user <u> --artifact-pass <p>]
#               [--artifact-pass-file <path>]
#
# 环境变量:
#   GALWORLD_ROOT       安装根目录（默认 ~/galworld）
#   GALWORLD_UNIT       systemd 单元名（默认 galworld-portal）
#   GALWORLD_CERT_PASS  p12 密码（默认 changeit；写入 ~/.config/galworld/creds.env，由单元 EnvironmentFile 读取）
#   GALWORLD_DEPLOY_TOKEN  固定部署令牌；不传则沿用单元里已有的，没有就随机生成一个写进单元
#   GALWORLD_ARTIFACT_NEXUS / _USER / _PASS  元件仓库地址与只读账号（可选）
#   GALWORLD_ARTIFACT_PASS_FILE  从文件读取元件仓库密码（避免出现在命令行与 ps 里）
#   GALWORLD_ARTIFACT_CANDIDATES  未指定仓库地址时依次探测的候选（逗号分隔，默认见下）
#   GALWORLD_DEPLOY_ALLOW_SUDO_PASSWORD      页面是否允许输入 sudo 密码（默认 false）
#
# 提权：脚本会把固定入口 /usr/local/sbin/galworld-sudo 与对应的 sudoers 白名单装好，
# 之后后台「一键部署」不再需要 sudo 密码；--no-sudoers 可跳过这一步。
# 元件账号写在 ~/.config/galworld/artifacts.env（0600），之后的运行不必再传。
#
# 幂等：重复执行只会替换 jar、按需重建证书、重启服务。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${GALWORLD_ROOT:-$HOME/galworld}"
UNIT="${GALWORLD_UNIT:-galworld-portal}"
PORT=8088
TLS=1
SKIP_CERT=0
FORCE_CERT=0
SKIP_DEPS=0
JAR_ARG=""
CERT_PASS="${GALWORLD_CERT_PASS:-changeit}"
DEPLOY_TOKEN="${GALWORLD_DEPLOY_TOKEN:-}"
TOKEN_ORIGIN=""   # 指定 / 沿用 / 生成
ARTIFACT_NEXUS="${GALWORLD_ARTIFACT_NEXUS:-}"
ARTIFACT_USER="${GALWORLD_ARTIFACT_USER:-}"
ARTIFACT_PASS="${GALWORLD_ARTIFACT_PASS:-}"
ARTIFACT_PASS_FILE="${GALWORLD_ARTIFACT_PASS_FILE:-}"
NO_SUDOERS=0
# 未显式给仓库地址时只探测本机；实际部署用 GALWORLD_ARTIFACT_CANDIDATES 指定。
ARTIFACT_CANDIDATES="${GALWORLD_ARTIFACT_CANDIDATES:-http://127.0.0.1:8081/repository/galworld-artifacts}"
ALLOW_SUDO_PASSWORD="${GALWORLD_DEPLOY_ALLOW_SUDO_PASSWORD:-false}"

say()  { printf '\n== %s\n' "$*"; }
fail() { printf '\n!! %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar)        JAR_ARG="${2:?--jar 需要路径}"; shift 2 ;;
    --root)       ROOT="${2:?--root 需要路径}"; shift 2 ;;
    --port)       PORT="${2:?--port 需要端口}"; shift 2 ;;
    --no-tls)     TLS=0; shift ;;
    --skip-cert)  SKIP_CERT=1; shift ;;
    --force-cert) FORCE_CERT=1; shift ;;
    --skip-deps)  SKIP_DEPS=1; shift ;;
    --allow-sudo-password) ALLOW_SUDO_PASSWORD=true; shift ;;
    --token)      DEPLOY_TOKEN="${2:?--token 需要令牌}"; shift 2 ;;
    --no-sudoers) NO_SUDOERS=1; shift ;;
    --artifact-pass-file) ARTIFACT_PASS_FILE="${2:?--artifact-pass-file 需要路径}"; shift 2 ;;
    --artifact-nexus)  ARTIFACT_NEXUS="${2:?--artifact-nexus 需要 URL}"; shift 2 ;;
    --artifact-user)   ARTIFACT_USER="${2:?--artifact-user 需要账号}"; shift 2 ;;
    --artifact-pass)   ARTIFACT_PASS="${2:?--artifact-pass 需要密码}"; shift 2 ;;
    -h|--help)    sed -n '2,/^set -euo pipefail$/p' "$0" | sed '$d'; exit 0 ;;
    *)            fail "未知参数: $1" ;;
  esac
done

# ---------- 0. 提权入口脚本与系统依赖 ----------
# 提权入口 galworld-sudo 同时承担两件事：
#   1) 后台「一键部署」的固定提权动作（sudoers 只放行这几个子命令）；
#   2) 下面的系统依赖安装——包清单只维护这一份，避免 deploy.sh 与门户命令表各写一套。
# 旧版本脚本自带的包数组已删除：两份清单漂移的表现是「全新机器装完仍缺包」。
say "生成提权入口脚本"
mkdir -p "$ROOT/bin"
write_sudo_wrapper() {
  cat > "$ROOT/bin/galworld-sudo" <<'WRAPPER'
#!/usr/bin/env bash
# galworld-sudo-version: 3
# Gal World 固定提权入口 —— 由 deploy.sh 生成，只实现后台部署需要的几个动作。
# 必须经 sudo 调用（sudoers 放行的也是这里的子命令），参数只接受下表里的值。
set -euo pipefail

target_user() {
  local u="${SUDO_USER:-}"
  if [[ -z "$u" || "$u" = "root" ]]; then
    echo "galworld-sudo: 需要通过 sudo 调用（SUDO_USER 为空）" >&2
    exit 2
  fi
  printf '%s' "$u"
}

case "${1:-}" in
  packages)
    # wine 是 32 位二进制，缺 i386 架构则所有 :i386 包都装不上
    if ! dpkg --print-foreign-architectures | grep -qx i386; then
      dpkg --add-architecture i386
    fi
    apt-get update -q || echo "警告：apt-get update 失败，继续尝试安装"
    DEBIAN_FRONTEND=noninteractive apt-get install -y -q \
      openjdk-21-jre-headless curl openssl \
      x11-utils xvfb xdotool pulseaudio pulseaudio-utils python3-venv fonts-wqy-microhei
    DEBIAN_FRONTEND=noninteractive apt-get install -y -q \
      libc6:i386 libstdc++6:i386 libfreetype6:i386 libfontconfig1:i386 \
      libx11-6:i386 libxext6:i386 libxrender1:i386 libxi6:i386 \
      libxcursor1:i386 libxrandr2:i386 libxinerama1:i386 libxfixes3:i386 \
      libxcomposite1:i386 libgl1:i386 libasound2t64:i386 \
      libgnutls30t64:i386 libpulse0:i386
    ;;
  wine-deps)
    # wow64 Wine 的编译依赖（源码 --enable-archs=i386,x86_64）。只加构建期包，
    # 运行期仍是 packages 分支那套 i386 运行库，二者不冲突。
    DEBIAN_FRONTEND=noninteractive apt-get install -y -q \
      build-essential bison flex gcc-multilib gcc-mingw-w64-x86-64 gcc-mingw-w64-i686 \
      libx11-dev libxext-dev libxrender-dev libxfixes-dev libxi-dev libxcursor-dev \
      libxrandr-dev libxinerama-dev libxcomposite-dev libfreetype-dev libfontconfig-dev \
      libgnutls28-dev libasound2-dev libpulse-dev libgl1-mesa-dev libvulkan-dev \
      libudev-dev libxml2-dev libxslt1-dev libpng-dev libjpeg-dev libkrb5-dev \
      libcups2-dev libdbus-1-dev libsane-dev libgphoto2-dev libpcap-dev libpcsclite-dev \
      libsdl2-dev libwayland-dev libxkbcommon-dev libxkbregistry-dev libosmesa6-dev \
      ocl-icd-opencl-dev libncurses-dev libavcodec-dev libavformat-dev libavutil-dev \
      libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev gettext autoconf
    ;;
  snd-aloop)
    # 每张 snd-aloop 卡只有 8 个 subdevice（pcm_substreams 上限 1-8），
    # 单卡最多服务 8 个游戏。装两张卡把容量提到 16。
    #
    # 必须写 enable=1,1：只写 index=0,1 不会让模块多建卡（实测两张都配了
    # 也只有一个 /proc/asound/cards 条目），enable 才是"这张卡要不要启用"。
    # 要更多容量就加长这两个列表，并同步 GameSlotAllocator.LOOPBACK_CARDS。
    echo "options snd-aloop enable=1,1 index=0,1" > /etc/modprobe.d/snd-aloop.conf
    echo snd-aloop > /etc/modules-load.d/snd-aloop.conf
    modprobe -r snd-aloop 2>/dev/null || true
    modprobe snd-aloop
    ;;
  audio-group)
    usermod -aG audio "$(target_user)"
    ;;
  linger)
    loginctl enable-linger "$(target_user)"
    ;;
  uninstall-sys)
    rm -f /etc/modules-load.d/snd-aloop.conf /etc/modprobe.d/snd-aloop.conf
    modprobe -r snd-aloop 2>/dev/null || true
    gpasswd -d "$(target_user)" audio 2>/dev/null || true
    ;;
  uninstall-packages)
    # 彻底卸载：切环境前要把运行时依赖清干净，否则残留的旧包会影响下一套方案。
    # JDK 是门户自己的运行前提，必须留着；curl/openssl 也是基础工具，不动。
    DEBIAN_FRONTEND=noninteractive apt-get purge -y -q \
      x11-utils xvfb xdotool pulseaudio pulseaudio-utils python3-venv fonts-wqy-microhei \
      || echo "警告：部分基础包卸载失败"
    i386_pkgs="$(dpkg -l | awk '/^ii .*:i386/ {print $2}')"
    if [[ -n "$i386_pkgs" ]]; then
      # i386 里有 essential 包（libc6:i386），apt 默认拒绝，需要显式放行
      # shellcheck disable=SC2086
      DEBIAN_FRONTEND=noninteractive apt-get purge -y -q --allow-remove-essential $i386_pkgs \
        || echo "警告：部分 i386 包卸载失败"
    fi
    dpkg --remove-architecture i386 2>/dev/null || true
    apt-get autoremove -y -q || true
    ;;
  *)
    echo "用法: galworld-sudo {packages|wine-deps|snd-aloop|audio-group|linger|uninstall-sys|uninstall-packages}" >&2
    exit 64
    ;;
esac
echo "galworld-sudo: $1 完成"
WRAPPER
  chmod 755 "$ROOT/bin/galworld-sudo"
}
write_sudo_wrapper
echo "已生成: $ROOT/bin/galworld-sudo"

install_system_deps() {
  # 代表性检查：全部到位就跳过，避免每次部署都跑一遍 apt update
  local -a probes=(openjdk-21-jre-headless xvfb pulseaudio python3-venv fonts-wqy-microhei libc6:i386)
  local pkg
  local missing=0

  if ! command -v apt-get >/dev/null 2>&1 || ! command -v dpkg-query >/dev/null 2>&1; then
    echo "未找到 apt-get/dpkg-query：非 Debian/Ubuntu 系，跳过自动安装"
    echo "请自行确认已装好 xvfb / pulseaudio / python3-venv / 文泉驿字体 / i386 运行库 / JDK 21"
    return 0
  fi

  for pkg in "${probes[@]}"; do
    if ! dpkg-query -W -f='${Status}' "$pkg" 2>/dev/null | grep -q 'install ok installed'; then
      missing=1
    fi
  done
  if [[ "$missing" -eq 0 ]]; then
    echo "已就绪"
    return 0
  fi

  echo "缺少基础包，通过提权入口安装…"
  # 首次运行没有缓存凭据时这一步会失败，与旧版要求一致：先 sudo -v 再跑
  if sudo -n "$ROOT/bin/galworld-sudo" packages; then
    echo "已安装"
    return 0
  fi
  echo "!! 安装失败：先执行 sudo -v 解锁凭据后重跑，或手工执行:"
  echo "!!   sudo $ROOT/bin/galworld-sudo packages"
  fail "系统依赖安装失败"
}

say "安装系统依赖"
if [[ "$SKIP_DEPS" -eq 1 ]]; then
  echo "已指定 --skip-deps，跳过"
else
  install_system_deps
fi

# ---------- 1. 运行环境 ----------
say "检查运行环境"
command -v systemctl >/dev/null || fail "缺少 systemctl：本脚本依赖 systemd user 单元"
command -v curl >/dev/null || fail "缺少 curl：健康检查需要"
if ! systemctl --user is-system-running >/dev/null 2>&1; then
  echo "警告：systemd --user 未就绪，单元注册可能失败（非 systemd 环境不支持本方式）"
fi

# ---------- 2. JDK 17+ ----------
say "检查 JDK"
JAVA_BIN="${JAVA_BIN:-}"
if [[ -z "$JAVA_BIN" ]]; then
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
  else
    JAVA_BIN="$(command -v java || true)"
  fi
fi
[[ -n "$JAVA_BIN" && -x "$JAVA_BIN" ]] || fail "找不到 java；请安装 JDK 17+ 或设置 JAVA_HOME"
JAVA_VER="$("$JAVA_BIN" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
JAVA_MAJOR="${JAVA_VER%%.*}"
# PATH 里的 java 可能是 JDK 8（例如 Nexus 自带的），自动找机器上可用的 17+
if ! [[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || [[ "$JAVA_MAJOR" -lt 17 ]]; then
  for cand in /usr/lib/jvm/*/bin/java /opt/*/bin/java "$HOME"/.jdks/*/bin/java; do
    [[ -x "$cand" ]] || continue
    v="$("$cand" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
    maj="${v%%.*}"
    if [[ "$maj" =~ ^[0-9]+$ ]] && [[ "$maj" -ge 17 ]]; then
      JAVA_BIN="$cand"; JAVA_VER="$v"; JAVA_MAJOR="$maj"
      break
    fi
  done
fi
[[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || fail "无法识别 JDK 版本: $JAVA_VER"
[[ "$JAVA_MAJOR" -ge 17 ]] || fail "需要 JDK 17+，当前: $JAVA_VER（$JAVA_BIN）"
echo "使用: $JAVA_BIN ($JAVA_VER)"

# ---------- 3. 找 jar ----------
say "定位 jar"
if [[ -n "$JAR_ARG" ]]; then
  JAR="$JAR_ARG"
elif compgen -G "$REPO_ROOT/portal/target/gal-world-portal-*.jar" >/dev/null; then
  JAR="$(ls -1 "$REPO_ROOT"/portal/target/gal-world-portal-*.jar | head -1)"
elif [[ -f "$ROOT/lib/gal-world-portal.jar" ]]; then
  JAR=""
  echo "沿用已安装的 $ROOT/lib/gal-world-portal.jar"
else
  fail "找不到 jar：用 --jar 指定，或在 portal/target 下先 mvn package"
fi
[[ -z "$JAR" || -f "$JAR" ]] || fail "jar 不存在: $JAR"

# ---------- 4. 目录与 jar ----------
say "创建目录"
mkdir -p "$ROOT"/{lib,data,data/certs,data/logs,games,logs,bin}
if [[ -n "$JAR" ]]; then
  cp -f "$JAR" "$ROOT/lib/gal-world-portal.jar"
  echo "已安装: $ROOT/lib/gal-world-portal.jar ($(du -h "$JAR" | cut -f1))"
fi

# ---------- 5. 证书 ----------
KEYSTORE="$ROOT/data/certs/galworld.p12"
if [[ "$TLS" -eq 1 && "$SKIP_CERT" -eq 0 ]]; then
  if [[ -f "$KEYSTORE" && "$FORCE_CERT" -eq 0 ]]; then
    echo "证书已存在，跳过（--force-cert 可重建）: $KEYSTORE"
  else
    say "生成自签证书"
    command -v openssl >/dev/null || fail "需要 openssl 生成证书；可加 --no-tls 跳过"
    TMP_DIR="$(mktemp -d)"
    trap 'rm -rf "$TMP_DIR"' EXIT
    openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
      -keyout "$TMP_DIR/key.pem" -out "$TMP_DIR/cert.pem" \
      -subj "/CN=galworld" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" >/dev/null 2>&1
    openssl pkcs12 -export -inkey "$TMP_DIR/key.pem" -in "$TMP_DIR/cert.pem" \
      -name galworld -passout "pass:$CERT_PASS" -out "$KEYSTORE"
    chmod 600 "$KEYSTORE"
    echo "已写入: $KEYSTORE"
  fi
elif [[ "$TLS" -eq 1 ]]; then
  echo "已指定 --skip-cert：沿用现有 $KEYSTORE（不存在时服务会启动失败）"
fi

# ---------- 5.5 部署令牌与元件仓库 ----------
# 页面「一键部署」要用令牌；未配置固定令牌时后端每次启动随机生成，只能翻 journal 抄，
# 无法做到无人值守。这里一律写一个固定令牌进单元，重启后不变。
say "准备部署令牌"
UNIT_DIR="$HOME/.config/systemd/user"
mkdir -p "$UNIT_DIR"
UNIT_FILE="$UNIT_DIR/$UNIT.service"
if [[ -n "$DEPLOY_TOKEN" ]]; then
  TOKEN_ORIGIN="指定"
elif [[ -f "$UNIT_FILE" ]] && grep -q '^Environment=GALWORLD_DEPLOY_TOKEN=' "$UNIT_FILE"; then
  DEPLOY_TOKEN="$(sed -n 's/^Environment=GALWORLD_DEPLOY_TOKEN=//p' "$UNIT_FILE" | head -1)"
  TOKEN_ORIGIN="沿用"
fi
if [[ -z "$DEPLOY_TOKEN" ]]; then
  if command -v openssl >/dev/null 2>&1; then
    DEPLOY_TOKEN="$(openssl rand -hex 16)"
  else
    DEPLOY_TOKEN="$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')"
  fi
  TOKEN_ORIGIN="生成"
fi
echo "部署令牌（$TOKEN_ORIGIN）：$DEPLOY_TOKEN"

# 元件仓库账号：密码更适合放文件（命令行与 ps 都看得见）。账号密码统一落
# ~/.config/galworld/artifacts.env（0600），由 systemd 以 EnvironmentFile 读取；
# 之后重跑 deploy.sh 不传也能沿用。
CREDS_DIR="$HOME/.config/galworld"
CREDS_FILE="$CREDS_DIR/artifacts.env"
if [[ -z "$ARTIFACT_PASS" && -n "$ARTIFACT_PASS_FILE" ]]; then
  [[ -r "$ARTIFACT_PASS_FILE" ]] || fail "读不到元件密码文件: $ARTIFACT_PASS_FILE"
  ARTIFACT_PASS="$(tr -d '\r\n' < "$ARTIFACT_PASS_FILE")"
fi
if [[ -z "$ARTIFACT_USER" && -z "$ARTIFACT_PASS" && -f "$CREDS_FILE" ]]; then
  ARTIFACT_USER="$(sed -n 's/^GALWORLD_ARTIFACT_USER=//p' "$CREDS_FILE" | head -1)"
  ARTIFACT_PASS="$(sed -n 's/^GALWORLD_ARTIFACT_PASS=//p' "$CREDS_FILE" | head -1)"
  [[ -n "$ARTIFACT_USER" ]] && echo "沿用已保存的元件账号: $CREDS_FILE"
fi
if [[ -n "$ARTIFACT_USER" || -n "$ARTIFACT_PASS" ]]; then
  mkdir -p "$CREDS_DIR"
  chmod 700 "$CREDS_DIR"
  (umask 077
   printf 'GALWORLD_ARTIFACT_USER=%s\nGALWORLD_ARTIFACT_PASS=%s\n' \
     "$ARTIFACT_USER" "$ARTIFACT_PASS" > "$CREDS_FILE")
  chmod 600 "$CREDS_FILE"
  echo "元件账号已写入: $CREDS_FILE"
fi

# 元件仓库地址：不显式给就按候选顺序探测，避免新机沿用 application.yml 里的 127.0.0.1。
# 探测只认 manifest.json 可达（200），401 说明需要账号，其它候选继续试。
say "探测元件仓库"
resolve_artifact_nexus() {
  local cand code
  local -a auth=()
  if [[ -n "$ARTIFACT_USER" ]]; then
    auth=(-u "$ARTIFACT_USER:${ARTIFACT_PASS:-}")
  fi
  local IFS=','
  for cand in $ARTIFACT_CANDIDATES; do
    cand="${cand% }"; cand="${cand# }"; cand="${cand%/}"
    [[ -n "$cand" ]] || continue
    code="$(curl -sk -m 5 "${auth[@]}" -o /dev/null -w '%{http_code}' "$cand/manifest.json" || true)"
    code="${code:-000}"
    if [[ "$code" = "200" ]]; then
      ARTIFACT_NEXUS="$cand"
      echo "使用: $cand"
      return 0
    fi
    echo "跳过 $cand（HTTP $code）"
  done
  echo "!! 没有可用的元件仓库：页面「一键安装」的 Wine / Selkies 会下载失败"
  echo "!! 用 --artifact-nexus <url> 或 GALWORLD_ARTIFACT_NEXUS 指定"
  return 0
}
if [[ -n "$ARTIFACT_NEXUS" ]]; then
  echo "使用: $ARTIFACT_NEXUS（已指定）"
else
  resolve_artifact_nexus
fi

# ---------- 5.6 固定提权入口 ----------
# 后台「一键部署」的提权步骤原先走 `sudo -n sh -c "<整行>"`，页面必须输密码，
# 而放行 sh 的 sudoers 等于放行任意 root 命令。这里装一个只实现固定动作的包装脚本，
# sudoers 只放行对应子命令：既不需要密码，权限边界也仍然是「只能做这几件事」。
SUDO_WRAPPER="/usr/local/sbin/galworld-sudo"
SUDOERS_FILE="/etc/sudoers.d/galworld-deploy"
say "安装提权入口"

install_sudo_entry() {
  local tmp_sudoers sub
  tmp_sudoers="$(mktemp)"
  {
    echo "# 由 gal-world deploy.sh 生成：只放行固定提权入口，勿手工改"
    for sub in packages wine-deps snd-aloop audio-group linger uninstall-sys uninstall-packages; do
      echo "$(id -un) ALL=(root) NOPASSWD: $SUDO_WRAPPER $sub"
    done
  } > "$tmp_sudoers"
  if sudo -n install -o root -g root -m 0755 "$ROOT/bin/galworld-sudo" "$SUDO_WRAPPER" \
      && sudo -n visudo -cf "$tmp_sudoers" >/dev/null \
      && sudo -n install -o root -g root -m 0440 "$tmp_sudoers" "$SUDOERS_FILE"; then
    rm -f "$tmp_sudoers"
    return 0
  fi
  rm -f "$tmp_sudoers"
  return 1
}

if [[ "$NO_SUDOERS" -eq 1 ]]; then
  echo "已指定 --no-sudoers，跳过（页面仍需 sudo 密码）"
elif [[ "$(id -u)" -eq 0 ]]; then
  echo "以 root 运行，跳过 sudoers 安装（门户不要用 root 跑）"
elif install_sudo_entry && sudo -n -l "$SUDO_WRAPPER" audio-group >/dev/null 2>&1; then
  echo "已安装: $SUDO_WRAPPER"
  echo "已写入: $SUDOERS_FILE"
  echo "后台「一键部署」不再需要 sudo 密码"
else
  echo "!! 自动安装失败（需要一次性 sudo 凭据）"
  echo "!! 手工执行:"
  echo "!!   sudo install -o root -g root -m 0755 $ROOT/bin/galworld-sudo $SUDO_WRAPPER"
  echo "!!   sudo sh -c 'for s in packages wine-deps snd-aloop audio-group linger uninstall-sys; do echo \"$(id -un) ALL=(root) NOPASSWD: $SUDO_WRAPPER \$s\"; done > $SUDOERS_FILE && chmod 440 $SUDOERS_FILE'"
  echo "!!   sudo visudo -cf $SUDOERS_FILE"
fi

# ---------- 6. systemd 用户单元 ----------
say "写入 systemd 用户单元"
# 元件源写进单元；账号密码走 EnvironmentFile：单元文件是 0644，密码不能落在里面
EXTRA_ENV=""
if [[ -n "$ARTIFACT_NEXUS" ]]; then
  EXTRA_ENV+="Environment=GALWORLD_ARTIFACT_NEXUS=$ARTIFACT_NEXUS"$'\n'
fi
if [[ -f "$CREDS_FILE" ]]; then
  EXTRA_ENV+="EnvironmentFile=-$CREDS_FILE"$'\n'
fi
# 证书口令同样走 0600 的 EnvironmentFile，避免明文写进 0644 的单元文件
if [[ "$TLS" -eq 1 ]]; then
  CREDS_ENV="$CREDS_DIR/creds.env"
  mkdir -p "$CREDS_DIR"
  chmod 700 "$CREDS_DIR"
  (umask 077
   printf 'GALWORLD_KEYSTORE_PASSWORD=%s\n' "$CERT_PASS" > "$CREDS_ENV")
  chmod 600 "$CREDS_ENV"
  EXTRA_ENV+="EnvironmentFile=-$CREDS_ENV"$'\n'
fi
cat > "$UNIT_DIR/$UNIT.service" <<EOF
[Unit]
Description=Gal World Portal (game library + session control)
After=network.target

[Service]
Type=simple
Environment=GALWORLD_ROOT=$ROOT
Environment=XDG_RUNTIME_DIR=/run/user/%U
Environment=GALWORLD_TLS=$([[ "$TLS" -eq 1 ]] && echo true || echo false)
Environment=GALWORLD_KEYSTORE=$KEYSTORE
Environment=GALWORLD_KEY_ALIAS=galworld
Environment=GALWORLD_PORTAL_PORT=$PORT
Environment=GALWORLD_DEPLOY_ALLOW_SUDO_PASSWORD=$ALLOW_SUDO_PASSWORD
Environment=GALWORLD_DEPLOY_TOKEN=$DEPLOY_TOKEN
$EXTRA_ENV
ExecStart=$JAVA_BIN -Xms128m -Xmx512m -Dfile.encoding=UTF-8 \\
  -jar $ROOT/lib/gal-world-portal.jar
Restart=on-failure
RestartSec=5
TimeoutStopSec=20

[Install]
WantedBy=default.target
EOF
echo "已写入: $UNIT_DIR/$UNIT.service"

# ---------- 7. 启动 ----------
say "启动服务"
# 门户是 --user 单元：没开 linger 时，最后一个登录会话结束 systemd 会把用户管理器
# 连同门户一起停掉，表现为「SSH 一断开网站就打不开」。这里必须真的开起来，不能只提示。
if ! loginctl show-user "$(id -un)" -p Linger 2>/dev/null | grep -q 'Linger=yes'; then
  echo "未开启 linger，尝试开启（需要 sudo）…"
  if sudo -n loginctl enable-linger "$(id -un)" 2>/dev/null; then
    echo "已开启 linger: $(id -un)"
  else
    echo "!! 自动开启失败，请手工执行: sudo loginctl enable-linger $(id -un)"
    echo "!! 否则注销 SSH 后门户会随用户管理器一起停止"
  fi
fi
systemctl --user daemon-reload
systemctl --user enable "$UNIT.service" >/dev/null 2>&1 || true
systemctl --user restart "$UNIT.service"
echo "等待服务就绪…"
for _ in $(seq 1 40); do
  if systemctl --user is-active --quiet "$UNIT.service"; then break; fi
  sleep 1
done
systemctl --user is-active "$UNIT.service" || {
  journalctl --user -u "$UNIT.service" -n 40 --no-pager
  fail "服务未启动"
}

# ---------- 8. 放行端口 ----------
# 只在 ufw 已启用时补规则：没有防火墙的机器不需要，也不该在这里装防火墙。
if command -v ufw >/dev/null 2>&1 && sudo -n ufw status 2>/dev/null | head -1 | grep -q 'Status: active'; then
  say "放行防火墙端口 $PORT/tcp"
  if sudo -n ufw allow "$PORT/tcp" >/dev/null 2>&1; then
    echo "已放行 $PORT/tcp"
  else
    echo "!! 放行失败，请手工执行: sudo ufw allow $PORT/tcp"
  fi
fi

# ---------- 9. 健康检查 ----------
say "健康检查"
SCHEME="http"; CURL_TLS=""
if [[ "$TLS" -eq 1 ]]; then SCHEME="https"; CURL_TLS="-k"; fi
for i in $(seq 1 40); do
  CODE="$(curl -s $CURL_TLS -o /dev/null -w '%{http_code}' "$SCHEME://127.0.0.1:$PORT/api/health" || echo 000)"
  if [[ "$CODE" = "200" ]]; then
    echo "health 200（${i}s）"
    echo
    echo "门户: $SCHEME://<server>:$PORT/"
    echo "部署令牌: $DEPLOY_TOKEN"
    echo "（已写入 $UNIT.service，重启后不变；填到后台「管理」页即可）"
    exit 0
  fi
  sleep 1
done
journalctl --user -u "$UNIT" -n 40 --no-pager
fail "健康检查失败（$SCHEME://127.0.0.1:$PORT/api/health 非 200）"
