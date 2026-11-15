#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# Gal World 元件同步：抓上游 → 校验 → 上传 Nexus → 生成 manifest
#
# 用法:
#   ./sync-artifacts.sh [--check] [--only <id>] [--dry-run]
#
#   --check    只比对上游最新 tag 与本地清单，输出差异，不下载不上传
#   --only ID  只同步指定元件（可重复）
#   --dry-run  下载并校验，但不上传
#
# 凭据（按需，绝不落盘）:
#   NEXUS_URL       默认 http://127.0.0.1:8081
#   NEXUS_REPO      默认 galworld-artifacts
#   NEXUS_USER      上传需要写权限；不传则匿名读取
#   NEXUS_PASS      同上。可用 `read -rs` 交互输入，或用 -p 参数（不建议）
#
# 下载策略：
#   1. 直连上游
#   2. 失败则走 gh-proxy.com 前缀（GitHub 资产）
#   3. 仍失败则 8 段并行 Range（按 Content-Length 校验总量）
#
# 依赖：curl、jq、sha256sum、yq（或 python3 解析 yaml）

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST_YAML="${ARTIFACT_SPEC:-$REPO_ROOT/tools/artifacts/artifacts.yaml}"
CACHE_DIR="${ARTIFACT_CACHE:-$REPO_ROOT/tools/artifacts/.cache}"
NEXUS_URL="${NEXUS_URL:-http://127.0.0.1:8081}"
NEXUS_REPO="${NEXUS_REPO:-galworld-artifacts}"
NEXUS_USER="${NEXUS_USER:-}"
NEXUS_PASS="${NEXUS_PASS:-}"
MIRRORS=(
  "https://gh-proxy.com/"
  "https://ghfast.top/"
)

CHECK_ONLY=0
DRY_RUN=0
ONLY_IDS=()

say()  { printf '\n== %s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check)   CHECK_ONLY=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --only)    ONLY_IDS+=("$2"); shift 2 ;;
    *) die "未知参数 $1" ;;
  esac
done

command -v curl >/dev/null || die "需要 curl"
command -v sha256sum >/dev/null || die "需要 sha256sum"
command -v jq >/dev/null || die "需要 jq"

mkdir -p "$CACHE_DIR"

# ---- YAML 解析：优先 yq，其次 python3 ----
if command -v yq >/dev/null 2>&1; then
  yaml_json() { yq -o=json "$1"; }
elif command -v python3 >/dev/null 2>&1; then
  yaml_json() {
    python3 - "$1" <<'PY'
import json,sys
try:
    import yaml
except ImportError:
    sys.exit("需要 PyYAML：pip install pyyaml")
print(json.dumps(yaml.safe_load(open(sys.argv[1],encoding='utf-8')), ensure_ascii=False))
PY
  }
else
  die "需要 yq 或 python3+PyYAML 解析 YAML"
fi

SPEC_JSON=$(yaml_json "$MANIFEST_YAML") || die "解析 artifacts.yaml 失败"

# ---- 上游 URL 构造 ----
build_upstream_url() {
  local item="$1" mirror_prefix="${2:-}"
  local type owner repo tag asset url
  type=$(jq -r '.source.type' <<<"$item")
  if [[ "$type" == "github" ]]; then
    owner=$(jq -r '.source.owner' <<<"$item")
    repo=$(jq -r '.source.repo' <<<"$item")
    tag=$(jq -r '.source.tag' <<<"$item")
    asset=$(jq -r '.source.asset' <<<"$item")
    printf '%shttps://github.com/%s/%s/releases/download/%s/%s' \
      "$mirror_prefix" "$owner" "$repo" "$tag" "$asset"
  else
    url=$(jq -r '.source.url // ""' <<<"$item")
    [[ -n "$url" ]] && printf '%s%s' "$mirror_prefix" "$url"
  fi
}

# ---- 进度显示 ----
# 下载大文件时用户看不到任何反馈，分不清「卡住」还是「在慢慢下」。
# 用一个后台线程每秒打印已收字节与速度，主流程结束或失败时关掉。
PROGRESS_PID=""

start_progress() {
  local file="$1" label="$2"
  (
    local last=0
    while :; do
      sleep 1
      if [[ -f "$file" ]]; then
        local now; now=$(stat -c %s "$file" 2>/dev/null || echo 0)
        local delta=$(( now - last ))
        printf '\r  %s: %.1f MB  %s/s   ' "$label" \
          "$(awk -v b="$now" 'BEGIN{printf "%.1f", b/1048576}')" \
          "$(awk -v d="$delta" 'BEGIN{
              if (d >= 1048576) printf "%.1f MB", d/1048576
              else if (d >= 1024) printf "%.0f KB", d/1024
              else printf "%d B", d
            }')"
        last=$now
      fi
    done
  ) &
  PROGRESS_PID=$!
}

stop_progress() {
  if [[ -n "${PROGRESS_PID:-}" ]]; then
    kill "$PROGRESS_PID" 2>/dev/null || true
    wait "$PROGRESS_PID" 2>/dev/null || true
    PROGRESS_PID=""
    printf '\r%*s\r' 60 ''   # 清掉进度行
  fi
}

# 并行分段模式：统计所有 .part_N 的总和，并按总量显示百分比
start_progress_dir() {
  local prefix="$1" total="$2"
  (
    local last=0
    while :; do
      sleep 1
      local now=0 f
      for f in "$prefix".part_*; do
        [[ -f "$f" ]] && now=$(( now + $(stat -c %s "$f" 2>/dev/null || echo 0) ))
      done
      local delta=$(( now - last ))
      printf '\r  分段 %3d%%  %.1f/%.1f MB  %s/s   ' \
        "$(awk -v n="$now" -v t="$total" 'BEGIN{printf "%d", (t>0? n*100/t : 0)}')" \
        "$(awk -v b="$now" 'BEGIN{printf "%.1f", b/1048576}')" \
        "$(awk -v b="$total" 'BEGIN{printf "%.1f", b/1048576}')" \
        "$(awk -v d="$delta" 'BEGIN{
            if (d >= 1048576) printf "%.1f MB", d/1048576
            else if (d >= 1024) printf "%.0f KB", d/1024
            else printf "%d B", d
          }')"
      last=$now
    done
  ) &
  PROGRESS_PID=$!
}

trap 'stop_progress' EXIT INT TERM

# ---- 下载：直连（带低速熔断）→ 镜像 → 并行分段 ----
#
# 直连 GitHub 资产经常「连上但几乎不动」（实测 0-5 KB/s），
# 无熔断的 curl 会一直挂着，看起来像卡死。这里加 --speed-limit/--speed-time：
# 10 秒内低于 10 KB/s 就主动放弃，立刻转并行分段。
SPEED_LIMIT=10240
SPEED_TIME=10

download_one() {
  local url="$1" out="$2" expect_size="$3"

  if [[ -f "$out" ]]; then
    local have; have=$(stat -c %s "$out" 2>/dev/null || echo 0)
    if [[ "$expect_size" -gt 0 && "$have" -eq "$expect_size" ]]; then
      say "已有完整文件，跳过下载: $(basename "$out")"
      return 0
    fi
  fi

  local tmp="$out.part"
  say "下载 $(basename "$out") ← $url"
  [[ "$expect_size" -gt 0 ]] && say "目标大小: $(awk -v b="$expect_size" 'BEGIN{printf "%.1f MB", b/1048576}')"

  start_progress "$tmp" "单连"
  if curl -fL --retry 2 --retry-delay 2 --connect-timeout 15 --max-time 900 \
       --speed-limit "$SPEED_LIMIT" --speed-time "$SPEED_TIME" \
       -o "$tmp" "$url" 2>/dev/null; then
    stop_progress
    mv -f "$tmp" "$out"; return 0
  fi
  stop_progress
  warn "单连太慢或失败（阈值 ${SPEED_LIMIT} B/s × ${SPEED_TIME}s），转并行分段"
  rm -f "$tmp"

  local total
  total=$(curl -sIL --max-time 30 "$url" | tr -d '\r' \
          | grep -i '^content-length:' | tail -1 | awk '{print $2}')
  if [[ -z "${total:-}" || "$total" -le 0 ]]; then
    warn "取不到 Content-Length，无法分段"; return 1
  fi

  local parts=8
  local chunk=$(( (total + parts - 1) / parts ))
  local i
  rm -f "$tmp" "$tmp".part_*
  say "并行分段下载：${parts} 路，每段 $(awk -v c="$chunk" 'BEGIN{printf "%.1f MB", c/1048576}')"
  local pids=()
  start_progress_dir "$tmp" "$total"
  for ((i=0; i<parts; i++)); do
    # 同一行声明两个变量在 set -u 下不可靠（end 依赖 start），逐行写
    local start=$((i*chunk))
    local end=$(( start + chunk - 1 ))
    [[ "$end" -ge "$total" ]] && end=$((total-1))
    curl -fsSL --max-time 900 -r "${start}-${end}" -o "$tmp.part_$i" "$url" &
    pids+=($!)
  done
  local ok=1
  for p in "${pids[@]}"; do wait "$p" || ok=0; done
  stop_progress
  [[ "$ok" -eq 1 ]] || { warn "并行分段失败"; return 1; }

  cat "$tmp".part_* > "$tmp" 2>/dev/null
  rm -f "$tmp".part_*
  local got; got=$(stat -c %s "$tmp")
  [[ "$got" -eq "$total" ]] || { warn "分段拼接后大小不符 ($got != $total)"; return 1; }
  mv -f "$tmp" "$out"
}

# ---- 校验 ----
verify_one() {
  local file="$1" expect="$2"
  local actual; actual=$(sha256sum "$file" | awk '{print $1}')
  if [[ -z "$expect" ]]; then
    say "清单无 sha256，记录实测值: $actual"
    return 0
  fi
  [[ "$actual" == "$expect" ]] || { warn "SHA256 不符 期望=$expect 实际=$actual"; return 1; }
  say "SHA256 校验通过"
}

# ---- 上传 Nexus ----
upload_one() {
  local local_file="$1" remote_path="$2"
  local url="$NEXUS_URL/repository/$NEXUS_REPO/$remote_path"
  if [[ -z "$NEXUS_USER" ]]; then
    warn "未提供 NEXUS_USER（需要写权限），跳过上传"
    return 1
  fi
  say "上传 $remote_path"
  curl -fsS -u "$NEXUS_USER:$NEXUS_PASS" -T "$local_file" "$url" \
    || { warn "上传失败"; return 1; }
  # 回读校验，避免半截文件进仓库
  local back="$CACHE_DIR/.verify"
  curl -fsS -u "$NEXUS_USER:$NEXUS_PASS" -o "$back" "$url" || { warn "回读失败"; return 1; }
  local a b
  a=$(sha256sum "$local_file" | awk '{print $1}')
  b=$(sha256sum "$back" | awk '{print $1}')
  rm -f "$back"
  [[ "$a" == "$b" ]] || { warn "回读 SHA256 不一致"; return 1; }
  say "回读校验一致"
}

# ---- --check 模式：只比上游 tag ----
if [[ "$CHECK_ONLY" -eq 1 ]]; then
  say "比对上游版本"
  local_count=$(jq '.artifacts | length' <<<"$SPEC_JSON")
  for ((n=0; n<local_count; n++)); do
    item=$(jq ".artifacts[$n]" <<<"$SPEC_JSON")
    id=$(jq -r '.id' <<<"$item")
    type=$(jq -r '.source.type' <<<"$item")
    if [[ "$type" == "github" ]]; then
      owner=$(jq -r '.source.owner' <<<"$item")
      repo=$(jq -r '.source.repo' <<<"$item")
      tag=$(jq -r '.source.tag' <<<"$item")
      latest=$(curl -fsSL --max-time 30 \
        "https://api.github.com/repos/$owner/$repo/releases/latest" 2>/dev/null \
        | jq -r '.tag_name // ""')
      if [[ -n "$latest" && "$latest" != "$tag" ]]; then
        printf '  [更新] %-24s 清单=%s 上游=%s\n' "$id" "$tag" "$latest"
      else
        printf '  [一致] %-24s %s\n' "$id" "$tag"
      fi
    else
      printf '  [跳过] %-24s 非 GitHub 源\n' "$id"
    fi
  done
  exit 0
fi

# ---- 主流程 ----
NEW_MANIFEST_ITEMS=()
count=$(jq '.artifacts | length' <<<"$SPEC_JSON")

for ((n=0; n<count; n++)); do
  item=$(jq ".artifacts[$n]" <<<"$SPEC_JSON")
  id=$(jq -r '.id' <<<"$item")

  if [[ ${#ONLY_IDS[@]} -gt 0 ]]; then
    match=0
    for want in "${ONLY_IDS[@]}"; do [[ "$want" == "$id" ]] && match=1; done
    [[ "$match" -eq 0 ]] && continue
  fi

  platform=$(jq -r '.platform' <<<"$item")
  name=$(jq -r '.name' <<<"$item")
  version=$(jq -r '.version' <<<"$item")
  file=$(jq -r '.file' <<<"$item")
  expect_size=$(jq -r '.size // 0' <<<"$item")
  expect_sha=$(jq -r '.sha256 // ""' <<<"$item")

  remote_path="$platform/$name/$version/$file"
  local_path="$CACHE_DIR/$platform-$name-$version-$file"

  say "[$id] $platform/$name@$version"

  # 1. 下载：直连优先（快时不用分段），慢或失败就换镜像
  if ! download_one "$(build_upstream_url "$item")" "$local_path" "$expect_size"; then
    for m in "${MIRRORS[@]}"; do
      if download_one "$(build_upstream_url "$item" "$m")" "$local_path" "$expect_size"; then
        break
      fi
    done
  fi
  [[ -f "$local_path" ]] || { warn "[$id] 所有源均失败，跳过"; continue; }

  # 2. 校验
  actual_sha=$(sha256sum "$local_path" | awk '{print $1}')
  if [[ -n "$expect_sha" && "$actual_sha" != "$expect_sha" ]]; then
    warn "[$id] SHA256 不符，删除重下"
    rm -f "$local_path"; continue
  fi
  verify_one "$local_path" "$expect_sha" || continue

  actual_size=$(stat -c %s "$local_path")

  # 3. 上传
  if [[ "$DRY_RUN" -eq 0 ]]; then
    upload_one "$local_path" "$remote_path" || warn "[$id] 上传失败（本地已缓存）"
  fi

  # 4. 汇总
  NEW_MANIFEST_ITEMS+=("$(jq -nc \
    --arg id "$id" --arg platform "$platform" --arg version "$version" \
    --arg file "$remote_path" --arg sha "$actual_sha" --argjson size "$actual_size" \
    --arg upstream "$(build_upstream_url "$item")" \
    --arg license "$(jq -r '.license // ""' <<<"$item")" \
    --arg note "$(jq -r '.note // ""' <<<"$item")" \
    '{id:$id, platform:$platform, version:$version, file:$file,
      sha256:$sha, size:$size, upstream:{url:$upstream, license:$license},
      note:$note}')")
done

[[ ${#NEW_MANIFEST_ITEMS[@]} -eq 0 ]] && die "没有可汇总的元件"

# ---- 生成 manifest ----
MANIFEST_FILE="$CACHE_DIR/manifest.json"
jq -n --arg ts "$(date -Iseconds)" \
   --argjson items "$(printf '%s\n' "${NEW_MANIFEST_ITEMS[@]}" | jq -s '.')" \
   '{schema:1, generatedAt:$ts, artifacts:$items}' > "$MANIFEST_FILE"

say "manifest 生成: $MANIFEST_FILE（$(jq '.artifacts|length' "$MANIFEST_FILE") 项）"

if [[ "$DRY_RUN" -eq 0 && -n "$NEXUS_USER" ]]; then
  upload_one "$MANIFEST_FILE" "manifest.json" || warn "manifest 上传失败"
fi

say "完成"
