#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# 构建并发布 Gitee Release。不会在本地创建 git tag；tag 由 Gitee Release 绑定。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORTAL="$REPO_ROOT/portal"
OWNER="${GITEE_OWNER:-sander-o}"
REPO="${GITEE_REPO:-gal-world}"
API="https://gitee.com/api/v5/repos/$OWNER/$REPO"
JAVA_HOME_LOCAL="${JAVA_HOME_LOCAL:-/g/Program Files/Java/corretto-17.0.14}"
DRY=0
[[ "${1:-}" == "--dry-run" ]] && DRY=1

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
# 从 0.0.1 起是首个可升级版本，所以默认与当前版本一致；需要阻断老版本时用环境变量抬高。
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
  TOKEN="${GITEE_TOKEN:-}"
  if [[ -z "$TOKEN" ]]; then
    cred="$(printf 'protocol=https\nhost=gitee.com\n\n' | git credential fill)" \
      || die "无法从 git credential 读取 Gitee 令牌，请设置 GITEE_TOKEN"
    TOKEN="$(printf '%s\n' "$cred" | sed -n 's/^password=//p')"
  fi
  [[ -n "$TOKEN" ]] || die "缺少 GITEE_TOKEN"
fi

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
RESP="$(auth_curl -fsS -X POST --connect-timeout 10 --max-time 60 \
  -H 'Accept: application/json' \
  --data-urlencode "tag_name=$TAG" \
  --data-urlencode "target_commitish=$COMMIT" \
  --data-urlencode "name=$TAG" \
  --data-urlencode "body@$RELEASE_BODY_FILE" \
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
