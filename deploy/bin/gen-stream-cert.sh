#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# Gal World - 生成 Selkies 用的自签证书
#
# 用法: gen-stream-cert.sh [--force] [--extra-ip <server-ip>] [--extra-dns galworld.local]
#
# 为什么要这个脚本：
#   Selkies 的串流强制 HTTPS，门户通过反向代理连它（目标写死 127.0.0.1）。
#   TLS 主机名校验要求证书的 SAN 里包含实际连接用的主机名——缺 127.0.0.1 时
#   门户转发会以 SSLHandshakeException 失败。手工生成的旧证书没有这一项，
#   每次换机器都要有人记得加，所以固化成脚本。
#
# 产出:
#   $GALROOT/data/certs/server.crt   证书（含 SAN: 127.0.0.1, localhost, 本机名, 额外项）
#   $GALROOT/data/certs/server.key   私钥（600）
#
# 幂等：证书存在且未加 --force 时直接退出。

set -euo pipefail

GALROOT="${GALWORLD_ROOT:-$HOME/galworld}"
CERT_DIR="$GALROOT/data/certs"
CRT="$CERT_DIR/server.crt"
KEY="$CERT_DIR/server.key"
DAYS="${GALWORLD_CERT_DAYS:-3650}"
FORCE=0
EXTRA_IPS=()
EXTRA_DNS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --force) FORCE=1; shift ;;
    --extra-ip) EXTRA_IPS+=("${2:?--extra-ip 需要值}"); shift 2 ;;
    --extra-dns) EXTRA_DNS+=("${2:?--extra-dns 需要值}"); shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

if [[ -f "$CRT" && -f "$KEY" && $FORCE -eq 0 ]]; then
  echo "证书已存在，跳过（加 --force 重新生成）: $CRT"
  exit 0
fi

mkdir -p "$CERT_DIR"

# SAN 里必须有 127.0.0.1：门户代理连的就是它
SAN="DNS:localhost,IP:127.0.0.1,IP:::1"
HOSTNAME_FQDN="$(hostname -f 2>/dev/null || hostname)"
if [[ -n "$HOSTNAME_FQDN" ]]; then
  SAN="$SAN,DNS:$HOSTNAME_FQDN"
fi
# 本机所有 IPv4 也加进去，方便同机直连排查
for ip in $(hostname -I 2>/dev/null || true); do
  SAN="$SAN,IP:$ip"
done
for ip in "${EXTRA_IPS[@]:-}"; do
  [[ -n "$ip" ]] && SAN="$SAN,IP:$ip"
done
for d in "${EXTRA_DNS[@]:-}"; do
  [[ -n "$d" ]] && SAN="$SAN,DNS:$d"
done

echo "生成自签证书"
echo "  SAN: $SAN"

openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "$KEY" -out "$CRT" -days "$DAYS" \
  -subj "/CN=galworld.local/O=Gal World" \
  -addext "subjectAltName=$SAN" >/dev/null 2>&1

chmod 600 "$KEY"
chmod 644 "$CRT"

echo "完成: $CRT / $KEY"
openssl x509 -in "$CRT" -noout -ext subjectAltName | tail -n +2
