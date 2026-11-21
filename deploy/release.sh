#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 构建并发布 Release。不会在本地创建 git tag；tag 由 Release 绑定。
#
# 默认同时发 Gitee 与 GitHub，共用同一份 dist/ 产物：
#   - Gitee  正文用 docs/release-notes/<version>.zh.md（中文）
#   - GitHub 正文用 docs/release-notes/<version>.en.md（英文）
# 这条语言约定与 .github/workflows/release.yml 一致，两边各写各的。
#
# 用法：
#   ./deploy/release.sh               # Gitee + GitHub
#   ./deploy/release.sh --gitee-only  # 只发 Gitee
#   ./deploy/release.sh --github-only # 只发 GitHub
#   ./deploy/release.sh --dry-run     # 只构建，不发
#
# 预发布：GALWORLD_RELEASE_TAG=v0.1.0-rc.1（tag 不等于 v<version> 时自动标 prerelease）
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORTAL="$REPO_ROOT/portal"
OWNER="${GITEE_OWNER:-sander-o}"
REPO="${GITEE_REPO:-gal-world}"
API="https://gitee.com/api/v5/repos/$OWNER/$REPO"
GH_OWNER="${GITHUB_OWNER:-sander-i}"
GH_REPO="${GITHUB_REPO:-gal-world}"
GH_API="https://api.github.com/repos/$GH_OWNER/$GH_REPO"
JAVA_HOME_LOCAL="${JAVA_HOME_LOCAL:-/g/Program Files/Java/corretto-17.0.14}"
DRY=0
PUBLISH_GITEE=1
PUBLISH_GITHUB=1
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY=1 ;;
    --gitee-only) PUBLISH_GITHUB=0 ;;
    --github-only) PUBLISH_GITEE=0 ;;
    *) die "未知参数: $arg（可用：--dry-run / --gitee-only / --github-only）" ;;
  esac
done

say() { printf '\n== %s\n' "$*"; }
die() { echo "$*" >&2; exit 1; }

# 令牌只经 stdin 传给 curl -K -，不出现在任何进程的 argv 中。
auth_curl() {
  printf 'header = "Authorization: token %s"\n' "$TOKEN" | curl -K - "$@"
}

say "检查依赖"
need() { command -v "$1" >/dev/null 2>&1 || die "缺少必需命令: $1"; }
need awk
need stat
need sha256sum
need npm
need mvn
if [[ "$DRY" == "1" ]]; then
  # curl/jq/git/unzip 只有正式发布才用；本地 dry-run 不因缺它们失败。
  for t in curl jq git unzip; do
    command -v "$t" >/dev/null 2>&1 \
      || echo "警告: 未找到 $t（dry-run 不需要；正式发布必需）" >&2
  done
else
  need curl
  need jq
  need git
  need unzip
fi

# 从 portal/pom.xml 读取项目自身的 version（单次 awk，不依赖 maven-help-plugin）。
VERSION="$(awk '
  /<artifactId>[[:space:]]*gal-world-portal[[:space:]]*<\/artifactId>/ { found = 1; next }
  found && /<version>/ {
    sub(/^[[:space:]]*<version>[[:space:]]*/, "")
    sub(/[[:space:]]*<\/version>.*$/, "")
    print
    exit
  }
' "$PORTAL/pom.xml")"
[[ -n "$VERSION" ]] || die "无法从 $PORTAL/pom.xml 读取项目版本"

if [[ "$VERSION" == *SNAPSHOT* ]]; then
  if [[ "$DRY" == "1" ]]; then
    echo "警告: 版本是 SNAPSHOT ($VERSION)，dry-run 继续；正式发布会被拒绝" >&2
  else
    die "版本仍是 SNAPSHOT: $VERSION"
  fi
fi
if [[ "$DRY" == "1" ]]; then
  [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]+)?$ ]] \
    || die "无法识别版本号: $VERSION"
else
  [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
    || die "正式发布版本必须是 X.Y.Z: $VERSION"
fi

# 更新门槛：低于该版本的安装不能自动升级（jar/H2 结构不兼容时抬高它）。
# 这是兼容性策略值，不是产品版本；产品版本只从 portal/pom.xml 读。
# 0.0.1 是首个可升级版本，所以默认值就是它；需要阻断老版本时用环境变量抬高。
MIN_FROM_VERSION="${GALWORLD_MIN_FROM_VERSION:-0.0.1}"

# 分支验证时用独立 tag（如 v<version>-pr7），默认仍是正式版本 tag
TAG="${GALWORLD_RELEASE_TAG:-v$VERSION}"
[[ "$TAG" =~ ^[A-Za-z0-9._/-]+$ ]] \
  || die "GALWORLD_RELEASE_TAG 只能包含字母、数字、. _ / -：$TAG"
# 分支包标记为预发布，避免占住 /releases/latest 把普通升级引到分支代码上
PRERELEASE=false
[[ "$TAG" == "v$VERSION" ]] || PRERELEASE=true
JAR="gal-world-portal-$VERSION.jar"
COMMIT=""
TOKEN=""

require_clean_tree() {
  local dirty
  dirty="$(git status --porcelain)" || die "无法检查工作区状态"
  if [[ -n "$dirty" ]]; then
    echo "工作区不干净（含未跟踪文件），发布前请先提交或清理：" >&2
    printf '%s\n' "$dirty" >&2
    exit 1
  fi
}

if [[ "$DRY" == "0" ]]; then
  # 发布前解析令牌并确认构建对应 HEAD，避免构建树与 tag 不一致。
  require_clean_tree
  COMMIT="$(git rev-parse HEAD)" || die "无法读取当前 HEAD"
  if [[ "$PUBLISH_GITEE" == "1" ]]; then
    TOKEN="${GITEE_TOKEN:-}"
    if [[ -z "$TOKEN" ]]; then
      cred="$(printf 'protocol=https\nhost=gitee.com\n\n' | git credential fill)" \
        || die "无法从 git credential 读取 Gitee 令牌，请设置 GITEE_TOKEN"
      TOKEN="$(printf '%s\n' "$cred" | sed -n 's/^password=//p')"
    fi
    [[ -n "$TOKEN" ]] || die "缺少 GITEE_TOKEN"
  fi
  if [[ "$PUBLISH_GITHUB" == "1" ]]; then
    GH_TOKEN="${GITHUB_TOKEN:-}"
    if [[ -z "$GH_TOKEN" ]]; then
      cred="$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill)" \
        || die "无法从 git credential 读取 GitHub 令牌，请设置 GITHUB_TOKEN"
      GH_TOKEN="$(printf '%s\n' "$cred" | sed -n 's/^password=//p')"
    fi
    [[ -n "$GH_TOKEN" ]] || die "缺少 GITHUB_TOKEN"
  fi
fi

# 构建与打包两个渠道共用：先出 dist/，再各自上传，保证 jar 的 sha256 一致。
say "构建前端与 jar"
cd "$PORTAL/frontend"; npm run build
cd "$PORTAL"; JAVA_HOME="$JAVA_HOME_LOCAL" mvn -q -o -B -DskipTests package

say "生成发布物"
DIST="$REPO_ROOT/dist"; rm -rf "$DIST"; mkdir -p "$DIST"
cp "$PORTAL/target/$JAR" "$DIST/$JAR"
# 合规：许可证与第三方声明随发布物一起提供，与 jar 内 META-INF 副本一致。
cp "$REPO_ROOT/LICENSE" "$DIST/LICENSE"
cp "$REPO_ROOT/THIRD-PARTY-NOTICES.md" "$DIST/THIRD-PARTY-NOTICES.md"
SIZE="$(stat -c %s "$DIST/$JAR")"
SHA="$(sha256sum "$DIST/$JAR" | awk '{print $1}')"
printf '%s  %s\n' "$SHA" "$JAR" >"$DIST/$JAR.sha256"
# manifest 覆盖 jar + 许可文件；更新器只取 artifact.name 指定的 jar，其余资产不影响更新。
MANIFEST_FILES=("$JAR" "$JAR.sha256" "release-manifest.json" "LICENSE" "THIRD-PARTY-NOTICES.md")
printf '{"schemaVersion":1,"version":"%s","tag":"%s","artifact":{"name":"%s","size":%s,"sha256":"%s"},"minFromVersion":"%s","dbSchema":1,"notes":"","rollbackNotes":"启动失败时恢复 pre-update 备份的 jar 与 H2"}\n' \
  "$VERSION" "$TAG" "$JAR" "$SIZE" "$SHA" "$MIN_FROM_VERSION" >"$DIST/release-manifest.json"

if [[ "$DRY" == "1" ]]; then
  say "dry-run 完成：$DIST"; exit 0
fi

# 正式发布前确认 jar 里打包的 galworld.version 就是本次 VERSION，
# 避免 pom 改了但资源过滤/前端构建产物没跟上时发布一个「版本号对不上」的包。
say "校验 jar 内版本"
EMBEDDED_VERSION="$(unzip -p "$DIST/$JAR" BOOT-INF/classes/application.yml \
  | awk '/^[[:space:]]+version:/ && !found { print $2; found = 1 }')"
[[ "$EMBEDDED_VERSION" == "$VERSION" ]] \
  || die "jar 内 galworld.version=${EMBEDDED_VERSION:-<空>} 与 VERSION=$VERSION 不一致"

# 构建不应改动受版本控制的文件；创建 Release 前再确认一次。
require_clean_tree

# Gitee API 没有删除 tag 的端点（DELETE /git/refs/tags/<tag> 与 /tags/<tag> 都是 404，
# DELETE /releases/tags/<tag> 是 405），镜像又不跟随 GitHub 的 tag 移动。同名 tag 已指向
# 别的提交时，重发 Release 会静默挂到旧提交上——这里提前失败，让「网页删 tag」显式发生。
if [[ "$PUBLISH_GITEE" == "1" ]]; then
say "检查 Gitee 侧 tag 与 Release"
TAGS_RESP="$(auth_curl -fsS --connect-timeout 10 --max-time 60 \
  -H 'Accept: application/json' "$API/tags?per_page=100")" \
  || die "读取 Gitee tag 列表失败"
EXISTING_TAG_SHA="$(printf '%s' "$TAGS_RESP" | jq -r --arg t "$TAG" \
  '.[] | select(.name == $t) | .commit.sha // empty' | head -n1)"
if [[ -n "$EXISTING_TAG_SHA" && "$EXISTING_TAG_SHA" != "$COMMIT" ]]; then
  cat >&2 <<EOF
Gitee 已存在 tag $TAG，指向 $EXISTING_TAG_SHA，本次发布的提交是 $COMMIT。
Gitee 镜像不跟随 tag 移动，重建 Release 会把它挂到旧提交上；Gitee API 无法删除 tag，
请先在网页删除后重跑本脚本：
  https://gitee.com/$OWNER/$REPO/tags
EOF
  exit 1
fi
if [[ -n "$EXISTING_TAG_SHA" ]]; then
  EXISTING_REL_ID="$(auth_curl -fsS --connect-timeout 10 --max-time 30 \
    -H 'Accept: application/json' "$API/releases/tags/$TAG" 2>/dev/null \
    | jq -r '.id // empty')" || EXISTING_REL_ID=""
  if [[ "$EXISTING_REL_ID" =~ ^[0-9]+$ ]]; then
    die "Gitee 已存在 $TAG 的 Release（id=$EXISTING_REL_ID），不能重复创建。先删除后重跑：
  printf 'header = \"Authorization: token %s\"\\n' \"\$GITEE_TOKEN\" | curl -K - -fsS -X DELETE \"$API/releases/$EXISTING_REL_ID\""
  fi
fi

say "创建 Release $TAG"
# body 里的中文必须走文件：Git Bash 把 argv 传给原生 curl.exe 时会按系统 ANSI
# 代码页（中文 Windows 是 GBK）转换，直接写在参数里会变成乱码字节，Gitee 按
# UTF-8 解出来就是「锟斤拷」。--data-urlencode name@file 读的是文件原始字节，
# 不经过 argv 编码转换。
#
# 有版本说明就用它（docs/release-notes/<version>.zh.md），没有则退回一行摘要。
RELEASE_BODY_FILE="$DIST/release-body.txt"
NOTES_ZH="$REPO_ROOT/docs/release-notes/$VERSION.zh.md"
if [[ -f "$NOTES_ZH" ]]; then
  cp "$NOTES_ZH" "$RELEASE_BODY_FILE"
else
  printf '发布 %s\n' "$TAG" >"$RELEASE_BODY_FILE"
fi
# curl 是原生 Windows 程序，不认 MSYS 的 /d/... 路径，转成 Windows 形式再传
RELEASE_BODY_ARG="$RELEASE_BODY_FILE"
if command -v cygpath >/dev/null 2>&1; then
  RELEASE_BODY_ARG="$(cygpath -w "$RELEASE_BODY_FILE")"
fi
RESP="$(auth_curl -fsS -X POST --connect-timeout 10 --max-time 60 \
  -H 'Accept: application/json' \
  --data-urlencode "tag_name=$TAG" \
  --data-urlencode "target_commitish=$COMMIT" \
  --data-urlencode "name=$TAG" \
  --data-urlencode "body@$RELEASE_BODY_ARG" \
  --data-urlencode "prerelease=$PRERELEASE" \
  "$API/releases")" || die "创建 Release $TAG 失败"
REL_ID="$(printf '%s' "$RESP" | jq -r '.id // empty')"
[[ "$REL_ID" =~ ^[0-9]+$ ]] || { printf '%s\n' "$RESP" >&2; die "创建 Release 响应缺少 id"; }

# 上传/校验失败时的统一恢复提示：不打印令牌，只打印从 stdin 喂 header 的命令。
recover_release() {
  local reason="$1"
  cat >&2 <<EOF
$reason
Release id=$REL_ID, tag=$TAG
如需删除该 Release 后重发，请先准备令牌（未导出时执行 read -rs GITEE_TOKEN）：
  printf 'header = "Authorization: token %s"\\n' "\$GITEE_TOKEN" | curl -K - -fsS -X DELETE "$API/releases/$REL_ID"
令牌来自 git credential fill 时，可先执行：
  GITEE_TOKEN=\$(printf 'protocol=https\\nhost=gitee.com\\n\\n' | git credential fill | sed -n 's/^password=//p')
删除后可重新运行 bash deploy/release.sh。
EOF
}

for f in "${MANIFEST_FILES[@]}"; do
  say "上传 $f"
  auth_curl -fsS -X POST --connect-timeout 10 --max-time 1200 \
    -H 'Accept: application/json' \
    -F "file=@$DIST/$f" \
    "$API/releases/$REL_ID/attach_files" >/dev/null \
    || { recover_release "上传 $f 失败"; exit 1; }
done

say "校验 Release 资产"
VERIFY_RESP="$(auth_curl -fsS --connect-timeout 10 --max-time 120 \
  -H 'Accept: application/json' \
  "$API/releases/$REL_ID")" \
  || { recover_release "读取 Release $REL_ID 失败"; exit 1; }
printf '%s' "$VERIFY_RESP" | jq -e . >/dev/null 2>&1 \
  || { recover_release "无法解析 Release $REL_ID 响应"; exit 1; }
MISSING=""
for f in "${MANIFEST_FILES[@]}"; do
  printf '%s' "$VERIFY_RESP" \
    | jq -e --arg n "$f" '.assets | any(.name == $n)' >/dev/null 2>&1 \
    || MISSING="$MISSING $f"
done
if [[ -n "$MISSING" ]]; then
  recover_release "Release 资产不完整，缺少:$MISSING"
  exit 1
fi

say "完成：https://gitee.com/$OWNER/$REPO/releases/tag/$TAG"
fi

# ---- GitHub Release ----
#
# 与 .github/workflows/release.yml 保持同一套约定：
#   - 正文用 docs/release-notes/<version>.en.md（Gitee 用 .zh.md）
#   - 资产与 Gitee 完全一致，更新器只认 manifest 里的 artifact.name
#   - prerelease 标记与 Gitee 同步（GALWORLD_RELEASE_TAG 不是 v<version> 时为 true）
#
# 端点差异：创建/查询在 api.github.com，上传资产必须在 uploads.github.com，
# 且上传 URL 要用 Release 响应里的 upload_url（去掉 {?name,label} 模板）。
# 用 api.github.com/.../assets 会返回 404。
if [[ "$PUBLISH_GITHUB" == "1" ]]; then
  say "检查 GitHub 侧 tag 与 Release"
  gh_curl() {
    printf 'header = "Authorization: Bearer %s"\n' "$GH_TOKEN" | curl -K - "$@"
  }
  # GitHub 的 tag 可以删除，但同名 tag 指向别的提交时仍然提前失败：
  # 静默复用旧 tag 会让 Release 挂错提交，排查起来比直接报错贵得多。
  GH_TAG_RESP="$(gh_curl -fsS --connect-timeout 10 --max-time 60 \
    -H 'Accept: application/vnd.github+json' \
    "$GH_API/git/ref/tags/$TAG" 2>/dev/null || true)"
  GH_EXISTING_SHA="$(printf '%s' "$GH_TAG_RESP" | jq -r '.object.sha // empty' 2>/dev/null)"
  if [[ -n "$GH_EXISTING_SHA" && "$GH_EXISTING_SHA" != "$COMMIT" ]]; then
    cat >&2 <<EOF
GitHub 已存在 tag $TAG，指向 $GH_EXISTING_SHA，本次发布的提交是 $COMMIT。
先删除该 tag 与对应 Release 后重跑：
  git push --delete github $TAG
  gh api -X DELETE "repos/$GH_OWNER/$GH_REPO/releases/tags/$TAG"
EOF
    exit 1
  fi

  # 正文：GitHub 用英文，缺英文说明时退回一行摘要（与 workflow 的兜底一致）
  NOTES_EN="$REPO_ROOT/docs/release-notes/$VERSION.en.md"
  GH_BODY_FILE="$DIST/release-body.en.txt"
  if [[ -f "$NOTES_EN" ]]; then
    cp "$NOTES_EN" "$GH_BODY_FILE"
  else
    echo "警告: 缺 docs/release-notes/$VERSION.en.md，GitHub 正文退回一行摘要" >&2
    printf 'Release %s\n' "$TAG" >"$GH_BODY_FILE"
  fi
  GH_BODY_ARG="$GH_BODY_FILE"
  if command -v cygpath >/dev/null 2>&1; then
    GH_BODY_ARG="$(cygpath -w "$GH_BODY_ARG")"
  fi

  say "创建 GitHub Release $TAG"
  # 用 jq 组 payload：正文含换行与引号，直接拼 shell 字符串会坏
  GH_PAYLOAD="$DIST/.gh-release.json"
  jq -n --arg tag "$TAG" --arg commit "$COMMIT" --arg body "$(cat "$GH_BODY_FILE")" \
        --argjson pre "$PRERELEASE" \
    '{tag_name:$tag, target_commitish:$commit, name:$tag, body:$body, prerelease:$pre, draft:false}' \
    >"$GH_PAYLOAD" || die "生成 GitHub Release payload 失败"
  GH_RESP="$(gh_curl -fsS -X POST --connect-timeout 10 --max-time 120 \
    -H 'Accept: application/vnd.github+json' \
    -H 'Content-Type: application/json' \
    --data-binary @"$GH_PAYLOAD" \
    "$GH_API/releases")" || die "创建 GitHub Release $TAG 失败"
  GH_REL_ID="$(printf '%s' "$GH_RESP" | jq -r '.id // empty')"
  [[ "$GH_REL_ID" =~ ^[0-9]+$ ]] || { printf '%s\n' "$GH_RESP" >&2; die "创建 Release 响应缺少 id"; }
  GH_UPLOAD_URL="$(printf '%s' "$GH_RESP" | jq -r '.upload_url // empty' \
    | sed 's/{.*}$//')"
  [[ -n "$GH_UPLOAD_URL" ]] || die "Release 响应缺少 upload_url"
  rm -f "$GH_PAYLOAD"

  for f in "${MANIFEST_FILES[@]}"; do
    say "上传到 GitHub $f"
    gh_curl -fsS -X POST --connect-timeout 10 --max-time 1200 \
      -H 'Accept: application/vnd.github+json' \
      -H 'Content-Type: application/octet-stream' \
      --data-binary @"$DIST/$f" \
      "$GH_UPLOAD_URL?name=$f" >/dev/null \
      || die "上传 $f 到 GitHub 失败（Release id=$GH_REL_ID）。
如需重发，先删除：
  gh api -X DELETE \"repos/$GH_OWNER/$GH_REPO/releases/$GH_REL_ID\"
  git push --delete github $TAG"
  done

  say "校验 GitHub Release 资产"
  GH_VERIFY="$(gh_curl -fsS --connect-timeout 10 --max-time 120 \
    -H 'Accept: application/vnd.github+json' \
    "$GH_API/releases/$GH_REL_ID")" || die "读取 GitHub Release $GH_REL_ID 失败"
  GH_MISSING=""
  for f in "${MANIFEST_FILES[@]}"; do
    printf '%s' "$GH_VERIFY" \
      | jq -e --arg n "$f" '.assets | any(.name == $n)' >/dev/null 2>&1 \
      || GH_MISSING="$GH_MISSING $f"
  done
  if [[ -n "$GH_MISSING" ]]; then
    die "GitHub Release 资产不完整，缺少:$GH_MISSING"
  fi

  say "完成：https://github.com/$GH_OWNER/$GH_REPO/releases/tag/$TAG"
fi
