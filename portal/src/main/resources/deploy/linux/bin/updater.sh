#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

# Gal World 远程更新器。由 Portal 通过 systemd-run --user 拉起，独立于 Portal 存活。
# 只更新 jar 与 H2；不触碰 games/、wineprefix、存档。
set -Eeuo pipefail

GALROOT="${GALWORLD_ROOT:-$HOME/galworld}"
JAR="$GALROOT/lib/gal-world-portal.jar"
DB="$GALROOT/data/portal/gal-world.mv.db"
REQ="$GALROOT/data/update-request.json"
STATUS="$GALROOT/data/update-status.json"
LOG="$GALROOT/data/update.log"
LOCK="$GALROOT/data/.update.lock"
TMP="$GALROOT/lib/.update"
PORTAL_PORT="${GALWORLD_PORTAL_PORT:-8088}"
# 更新源可用 GALWORLD_UPDATE_API 指定；未指定时用 Portal 写进请求文件的地址。
# systemd-run 的瞬态单元不继承 Portal 的环境变量，所以请求文件是主要来源。
API="${GALWORLD_UPDATE_API:-}"
DEFAULT_API="https://gitee.com/api/v5/repos/sander-o/gal-world"
UNZIP="${UNZIP:-unzip}"
# 私有 fork / 镜像的只读令牌：优先 GALWORLD_UPDATE_TOKEN，其次该文件（必须 0600，
# 内容 GITEE_TOKEN=... 或 UPDATE_TOKEN=...）。
UPDATE_ENV="${GALWORLD_UPDATE_ENV:-$HOME/.config/galworld-update.env}"
UPDATE_TOKEN=""

# 运行期状态。EXIT/ERR trap 依赖这些全局量判断是否需要补写 failed / 拉起门户。
PHASE=""
TERMINAL=0
PORTAL_STOPPED=0
PORTAL_STARTED=0
NO_RESTART=0
LOCK_HELD=0
ERR_AT=""
JOB_ID=""
JOB_STARTED_AT=""
START_TS=""

log() { printf '%s %s\n' "$(date -Is)" "$*" >>"$LOG"; }
die() { log "ERROR: $*"; phase failed "$*"; exit 1; }

# 依赖缺失时可能连 jq 都没有，用纯 printf 写日志与状态，避免卡在 queued。
require_cmds() {
  local missing="" c
  for c in jq curl unzip sha256sum flock systemd-run; do
    command -v "$c" >/dev/null 2>&1 || missing="$missing $c"
  done
  [[ -z "$missing" ]] && return 0
  # 尽量保留请求里的 jobId/version（jq 可能正是缺失项，用 sed 提取）。
  local req_version="" req_job=""
  if [[ -f "$REQ" ]]; then
    req_version="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$REQ" 2>/dev/null | tail -n 1 || true)"
    req_job="$(sed -n 's/.*"jobId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$REQ" 2>/dev/null | tail -n 1 || true)"
  fi
  printf '%s ERROR: 缺少必需命令:%s\n' "$(date -Is)" "$missing" >>"$LOG" 2>/dev/null || true
  mkdir -p "$(dirname "$STATUS")" 2>/dev/null || true
  printf '{"phase":"failed","message":"缺少必需命令:%s","version":"%s","jobId":"%s","finishedAt":"%s"}\n' \
    "$missing" "$req_version" "$req_job" "$(date -Is)" >"$STATUS.tmp" 2>/dev/null \
    && mv -f "$STATUS.tmp" "$STATUS" 2>/dev/null || true
  exit 1
}

# 只读令牌只从环境或 0600 的 env 文件读取；不写日志、不放进 argv。
load_token() {
  UPDATE_TOKEN="${GALWORLD_UPDATE_TOKEN:-}"
  [[ -n "$UPDATE_TOKEN" ]] && return 0
  [[ -f "$UPDATE_ENV" ]] || return 0
  local perm token
  perm="$(stat -c %a "$UPDATE_ENV" 2>/dev/null || true)"
  if [[ "$perm" != "600" ]]; then
    log "忽略 $UPDATE_ENV：权限为 ${perm:-未知}，应为 600"
    return 0
  fi
  token="$(sed -n 's/^[[:space:]]*\(GITEE_TOKEN\|UPDATE_TOKEN\)[[:space:]]*=[[:space:]]*//p' "$UPDATE_ENV" \
    | tail -n 1 || true)"
  token="${token%$'\r'}"
  token="${token%\"}"; token="${token#\"}"
  token="${token%\'}"; token="${token#\'}"
  UPDATE_TOKEN="$token"
}

# 更新源统一走一个入口：令牌经 curl -K - 的 stdin 传入，不出现在进程列表或日志。
source_curl() {
  if [[ -n "${UPDATE_TOKEN:-}" ]]; then
    printf 'header = "Authorization: token %s"\n' "$UPDATE_TOKEN" | curl -K - "$@"
  else
    curl "$@"
  fi
}

# 兜底：任何未显式处理的失败（set -e、jq/df/cp/install/mv/mkdir 等）都会走到这里。
# 不覆盖已经写入的终态（success/rolled_back/failed），并在门户已停且未成功启动时尝试拉起。
on_error() {
  local rc=$?
  trap - ERR EXIT
  set +e
  if [[ "$rc" -ne 0 && "${LOCK_HELD:-0}" -eq 1 ]]; then
    if [[ "${TERMINAL:-0}" -eq 0 ]]; then
      phase failed "更新器异常退出 (exit $rc${ERR_AT:+; $ERR_AT})"
    fi
    if [[ "${PORTAL_STOPPED:-0}" -eq 1 && "${PORTAL_STARTED:-0}" -eq 0 && "${NO_RESTART:-0}" -eq 0 ]]; then
      log "异常退出后尝试恢复门户"
      if start_portal; then
        PORTAL_STARTED=1
        log "门户已恢复启动"
      else
        log "门户恢复启动失败，需人工处理"
      fi
    fi
  fi
  exit "$rc"
}

install_traps() {
  trap 'ERR_AT="line $LINENO: $BASH_COMMAND"' ERR
  trap on_error EXIT
}

phase() {
  local p="$1" msg="${2:-}" now fin=""
  now="$(date -Is)"
  [[ -n "${JOB_STARTED_AT:-}" ]] || JOB_STARTED_AT="$now"
  case "$p" in
    success|rolled_back|failed) fin="$now" ;;
  esac
  mkdir -p "$(dirname "$STATUS")"
  jq -c -n --arg jobId "${JOB_ID:-}" --arg phase "$p" --arg version "${TARGET:-}" \
    --arg to "${TARGET:-}" --arg from "${CUR:-}" \
    --arg message "$msg" --arg startedAt "$JOB_STARTED_AT" \
    --arg phaseStartedAt "$now" --arg fin "$fin" \
    '{jobId:$jobId,phase:$phase,version:$version,toVersion:$to,fromVersion:$from,
      message:$message,startedAt:$startedAt,phaseStartedAt:$phaseStartedAt}
     + (if $fin == "" then {} else {finishedAt:$fin} end)' \
    >"$STATUS.tmp"
  mv -f "$STATUS.tmp" "$STATUS"
  PHASE="$p"
  case "$p" in
    success|rolled_back|failed) TERMINAL=1 ;;
    *) TERMINAL=0 ;;
  esac
  log "phase=$p ${msg}"
}

ver_norm() {
  local v="${1:-}"
  v="$(printf '%s' "$v" | tr '[:upper:]' '[:lower:]')"
  v="${v#v}"
  printf '%s' "${v%%-*}"
}

ver_gt() {
  local a b
  a="$(ver_norm "$1")"; b="$(ver_norm "$2")"
  [[ "$a" != "$b" && "$(printf '%s\n%s\n' "$a" "$b" | sort -V | tail -1)" == "$a" ]]
}

# 不要用 awk exit 提前结束：unzip 写管道会收到 SIGPIPE，pipefail 下整条管道判失败。
jar_version() {
  "$UNZIP" -p "$1" BOOT-INF/classes/application.yml 2>/dev/null \
    | awk '/^[[:space:]]+version:/ && !found {print $2; found=1}'
}

# 同样不能 grep -q：提前退出会让 unzip 收到 SIGPIPE。
has_static_entry() {
  "$UNZIP" -l "$1" | grep 'BOOT-INF/classes/static/index.html' >/dev/null
}

# 覆盖更新时目标版本必须自带更新接口：降级后还要能从页面升回来
has_updater_api() {
  "$UNZIP" -l "$1" | grep 'BOOT-INF/classes/com/galworld/update/UpdateService.class' >/dev/null
}

# 只匹配启动级致命错误，不限定 journal 级别：门户 unit 没设 StandardOutput，
# Spring Boot 的 stdout（含 APPLICATION FAILED TO START）在 journal 里是 LOG_INFO，
# 用 -p err 会把它们全部过滤掉，守卫等于失效。改用内容白名单，避免误杀
# CompanySchemaMigration 每次启动都会打印的无害 BadSqlGrammarException WARN。
journal_has_errors() {
  command -v journalctl >/dev/null 2>&1 || return 1
  [[ -n "${START_TS:-}" ]] || return 1
  local out
  out="$(journalctl --user -u galworld-portal --since "$START_TS" -n 200 --no-pager 2>/dev/null || true)"
  [[ -n "$out" ]] || return 1
  grep -Eq 'APPLICATION FAILED TO START|SchemaManagementException|FlywayException|BeanCreationException|Failed to start' <<<"$out"
}

# $2 = 1 时检查启动日志（正常更新）；回滚只做 HTTP+版本校验（$2 = 0），
# 避免旧版本启动日志里的历史 WARN 让回滚再次失败。
smoke() {
  local want="$1" check_journal="${2:-1}" i
  for i in $(seq 1 60); do
    local body
    body="$(curl -sk --max-time 3 "https://127.0.0.1:$PORTAL_PORT/api/health" || true)"
    if [[ -n "$body" ]] && [[ "$(printf '%s' "$body" | jq -r .version 2>/dev/null)" == "$want" ]]; then
      local code
      code="$(curl -sko /dev/null -w '%{http_code}' "https://127.0.0.1:$PORTAL_PORT/api/games" || true)"
      [[ "$code" == "200" ]] || { sleep 2; continue; }
      code="$(curl -sko /dev/null -w '%{http_code}' "https://127.0.0.1:$PORTAL_PORT/api/settings" || true)"
      [[ "$code" == "200" ]] || { sleep 2; continue; }
      code="$(curl -sko /dev/null -w '%{http_code}' "https://127.0.0.1:$PORTAL_PORT/" || true)"
      [[ "$code" == "200" ]] || { sleep 2; continue; }
      if [[ "$check_journal" -eq 1 ]] && journal_has_errors; then
        log "启动日志包含致命错误或数据库迁移错误，冒烟失败"
        return 1
      fi
      return 0
    fi
    sleep 2
  done
  return 1
}

# 只把 inactive/failed 视为已停。空值、unknown、active 等都算停服失败，
# 避免 systemctl 查询失败被误判成「服务已停」而带库替换。
is_stopped_state() {
  case "${1:-}" in
    inactive|failed) return 0 ;;
    *) return 1 ;;
  esac
}

stop_portal() {
  systemctl --user stop galworld-portal.service || true
  local i state=""
  for i in $(seq 1 30); do
    state="$(systemctl --user show galworld-portal.service -p ActiveState --value 2>/dev/null || true)"
    if is_stopped_state "$state"; then return 0; fi
    sleep 1
  done
  log "停止门户失败: ActiveState=${state:-<empty>}"
  return 1
}

start_portal() {
  export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
  systemctl --user start galworld-portal.service
}

fail_manual() {
  local msg="$1"
  log "ERROR: $msg"
  # 服务状态由调用方决定：这里的失败路径不再交给 EXIT trap 自动拉起门户。
  NO_RESTART=1
  PORTAL_STOPPED=0
  phase failed "$msg"
  exit 1
}

rollback() {
  log "开始回滚到 $CUR"
  if ! stop_portal; then
    PORTAL_STOPPED=0
    PORTAL_STARTED=0
    fail_manual "回滚失败：无法停止门户，需人工恢复: $BK"
  fi
  PORTAL_STOPPED=1
  PORTAL_STARTED=0

  # 恢复 jar 与数据库都用同目录 tmp + mv 原子替换；恢复成功前不写 rolled_back。
  if ! install -m 664 "$BK/jar" "$JAR.tmp" || ! mv -f "$JAR.tmp" "$JAR"; then
    fail_manual "回滚失败：恢复 jar 失败，服务已停止，需人工恢复: $BK"
  fi
  if [[ -f "$BK/gal-world.mv.db" ]]; then
    if ! cp -a "$BK/gal-world.mv.db" "$DB.tmp" || ! mv -f "$DB.tmp" "$DB"; then
      fail_manual "回滚失败：恢复数据库失败，服务已停止，需人工恢复: $BK"
    fi
    # H2 的残留文件是 gal-world.trace.db / gal-world.lock.db，
    # 不是 gal-world.mv.db.trace.db，这里要从 DB 路径去掉 .mv.db 后缀再拼。
    local db_base="${DB%.mv.db}"
    rm -f "$db_base.trace.db" "$db_base.lock.db" || log "清理数据库残留文件失败"
  fi

  START_TS="$(date -Is)"
  if start_portal; then
    PORTAL_STARTED=1
  fi
  if [[ "$PORTAL_STARTED" -eq 1 ]] && smoke "$CUR" 0; then
    log "回滚成功，当前 $CUR"
    phase rolled_back "已回滚到 $CUR"
    exit 0
  fi

  if ! stop_portal; then log "回滚后无法停止门户"; fi
  PORTAL_STOPPED=1
  PORTAL_STARTED=0
  fail_manual "回滚失败，服务已停止，需人工恢复: $BK"
}

main() {
  install_traps

  mkdir -p "$TMP" "$(dirname "$LOG")"
  # 依赖检查放在写 queued 之前：缺 jq/curl/unzip 等时立即失败，不留下无法推进的排队状态。
  require_cmds
  load_token
  exec 9>"$LOCK"
  if ! flock -n 9; then
    log "another updater running; lock held"
    # 只有「状态文件里的 queued 属于本次请求」时才补写 failed；
    # jobId 不同说明另一个任务正在执行（例如残留判定触发的新申请），绝不能覆盖它的状态。
    if command -v jq >/dev/null 2>&1 && [[ -f "$STATUS" ]]; then
      local st_phase st_job req_job
      st_phase="$(jq -r '.phase // empty' "$STATUS" 2>/dev/null || true)"
      st_job="$(jq -r '.jobId // empty' "$STATUS" 2>/dev/null || true)"
      req_job="$(jq -r '.jobId // empty' "$REQ" 2>/dev/null || true)"
      if [[ "$st_phase" == "queued" && -n "$req_job" && "$st_job" == "$req_job" ]]; then
        TARGET="$(jq -r '.version // empty' "$REQ" 2>/dev/null || true)"
        JOB_ID="$req_job"
        phase failed "另一个更新器正在运行（锁冲突），请稍后重试"
      else
        log "锁被其他任务持有 (status jobId=${st_job:-<empty>}, request jobId=${req_job:-<empty>})，不改写状态"
      fi
    fi
    exit 1
  fi
  # 拿到锁之后才允许 trap 改写状态/恢复门户，避免并发实例覆盖正在执行的任务。
  LOCK_HELD=1

  [[ -f "$REQ" ]] || die "缺少 $REQ"
  TARGET="$(jq -r '.version // empty' "$REQ")"
  TAG="$(jq -r '.tag // empty' "$REQ")"
  JOB_ID="$(jq -r '.jobId // empty' "$REQ")"
  # 旧版门户写出的请求没有 override 字段，缺失一律按普通升级处理
  OVERRIDE="$(jq -r '.override // false' "$REQ" 2>/dev/null || echo false)"
  # 任务开始时间取申请时间；缺失时退回当前时间。phase 内只更新 phaseStartedAt。
  JOB_STARTED_AT="$(jq -r '.requestedAt // empty' "$REQ" 2>/dev/null || true)"
  [[ -n "$JOB_STARTED_AT" ]] || JOB_STARTED_AT="$(date -Is)"
  [[ -n "$TARGET" && -n "$TAG" ]] || die "update-request 缺少 version/tag"
  [[ -f "$JAR" ]] || die "缺少当前 jar: $JAR"

  # 更新源优先级：环境变量 > Portal 写入的请求文件 > 内置默认值。
  API="${API:-$(jq -r '.releaseApi // empty' "$REQ" 2>/dev/null || true)}"
  API="${API:-$DEFAULT_API}"
  API="${API%/}"

  CUR="$(jar_version "$JAR")"
  phase precheck "当前 $CUR，目标 $TARGET"
  if [[ "$OVERRIDE" == "true" ]]; then
    log "覆盖更新：忽略版本方向（当前 $CUR，目标 $TARGET）"
  else
    ver_gt "$TARGET" "$CUR" || die "目标版本不高于当前版本"
  fi

  local free_kb
  free_kb="$(df -Pk "$GALROOT" | awk 'NR==2 {print $4}')"
  [[ "${free_kb:-0}" -ge 2097152 ]] || die "磁盘余量不足 2 GiB"

  phase downloading "获取 Release $TAG"
  local rel manifest_url jar_url
  rel="$(source_curl -fsSL --retry 3 --connect-timeout 10 --max-time 30 \
    -H 'Accept: application/json' "$API/releases/tags/$TAG")" \
    || die "查询 Release 失败"
  # 用 jq 的 first() 取代 | head -1，避免 jq 被 SIGPIPE 影响 pipefail。
  manifest_url="$(printf '%s' "$rel" | jq -r '
    first(.assets[] | select(.name=="release-manifest.json") | .browser_download_url) // empty')"
  jar_url="$(printf '%s' "$rel" | jq -r --arg n "gal-world-portal-$TARGET.jar" '
    first(.assets[] | select(.name==$n) | .browser_download_url) // empty')"
  [[ -n "$manifest_url" && -n "$jar_url" ]] || die "Release 缺少 manifest 或 jar"

  source_curl -fsSL --retry 3 --connect-timeout 10 --max-time 30 \
    -o "$TMP/manifest.json" "$manifest_url" || die "下载 manifest 失败"
  local mv mt size sha minfrom
  mv="$(jq -r .version "$TMP/manifest.json")"
  mt="$(jq -r .tag "$TMP/manifest.json")"
  size="$(jq -r .artifact.size "$TMP/manifest.json")"
  sha="$(jq -r .artifact.sha256 "$TMP/manifest.json")"
  minfrom="$(jq -r '.minFromVersion // ""' "$TMP/manifest.json")"
  [[ "$size" =~ ^[0-9]+$ ]] || die "manifest artifact.size 不是数字"
  [[ "$(ver_norm "$mv")" == "$(ver_norm "$TARGET")" ]] || die "manifest version 不匹配"
  [[ "$mt" == "$TAG" ]] || die "manifest tag 不匹配"
  if [[ "$OVERRIDE" != "true" && -n "$minfrom" ]] \
      && ver_gt "$minfrom" "$CUR" && [[ "$(ver_norm "$minfrom")" != "$(ver_norm "$CUR")" ]]; then
    die "当前版本 $CUR 低于 minFromVersion $minfrom"
  fi

  phase downloading "下载 $jar_url"
  # --max-filesize 用 manifest 声明值兜底，避免异常大文件打满磁盘。
  source_curl -fL --retry 3 --connect-timeout 10 --max-time 1800 --max-filesize "$size" \
    -o "$TMP/new.jar" "$jar_url" || die "下载 jar 失败"

  phase verifying "校验大小与 sha256"
  [[ "$(stat -c %s "$TMP/new.jar")" == "$size" ]] || die "jar 大小不匹配"
  [[ "$(sha256sum "$TMP/new.jar" | awk '{print $1}')" == "$sha" ]] || die "sha256 不匹配"
  "$UNZIP" -tq "$TMP/new.jar" >/dev/null || die "jar 损坏"
  [[ "$(jar_version "$TMP/new.jar")" == "$TARGET" ]] || die "jar 内版本不匹配"
  has_static_entry "$TMP/new.jar" || die "jar 缺少前端入口"
  if [[ "$OVERRIDE" == "true" ]]; then
    has_updater_api "$TMP/new.jar" || die "目标版本不含更新器，拒绝覆盖"
  fi

  phase backing_up "准备备份 jar 与数据库"
  phase stopping "停止服务"
  if ! stop_portal; then die "停服失败"; fi
  PORTAL_STOPPED=1
  PORTAL_STARTED=0

  BK="$GALROOT/data/backups/pre-update-$CUR-$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$BK"
  cp -a "$JAR" "$BK/jar"
  if [[ -f "$DB" ]]; then cp -a "$DB" "$BK/gal-world.mv.db"; fi
  [[ -s "$BK/jar" ]] || die "备份 jar 失败"

  phase replacing "替换 jar"
  install -m 664 "$TMP/new.jar" "$JAR.tmp"
  mv -f "$JAR.tmp" "$JAR"

  phase starting "启动 $TARGET"
  START_TS="$(date -Is)"
  if start_portal; then
    PORTAL_STARTED=1
  else
    rollback
  fi

  phase verifying "启动后校验"
  if ! smoke "$TARGET"; then
    log "冒烟失败，开始回滚"
    rollback
  fi

  phase success "更新完成 $CUR -> $TARGET"
  rm -f "$REQ" || log "删除 update-request.json 失败"
  rm -rf "$TMP" || log "清理临时目录失败"
  ls -1dt "$GALROOT"/data/backups/pre-update-* 2>/dev/null | tail -n +4 | xargs -r rm -rf \
    || log "清理旧备份失败"
  log "更新成功 $CUR -> $TARGET"
}

if [[ "${1:-}" == "--source-only" ]]; then
  return 0 2>/dev/null || exit 0
fi
main "$@"
