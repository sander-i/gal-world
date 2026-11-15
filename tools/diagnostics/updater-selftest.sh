#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
UPD="$HERE/../../deploy/bin/updater.sh"

# 所有路径都指到临时 GALWORLD_ROOT，绝不碰真实 ~/galworld
TMPROOT="$(mktemp -d)"
cleanup() { [[ -n "${TMPROOT:-}" && -d "$TMPROOT" ]] && rm -rf "$TMPROOT"; }
trap cleanup EXIT
GALWORLD_ROOT="$TMPROOT"
export GALWORLD_ROOT

# 只加载函数，不执行主流程
source "$UPD" --source-only

# --- 版本比较 ---
[[ "$(ver_norm v0.1.1)" = "0.1.1" ]]
ver_gt 0.1.1 0.1.0-SNAPSHOT
! ver_gt 0.1.0 0.1.0-SNAPSHOT

# --- 停服判定：只有 inactive/failed 算已停 ---
is_stopped_state inactive
is_stopped_state failed
! is_stopped_state active
! is_stopped_state ""
! is_stopped_state unknown

# jq 不可用时用最小替身，只实现 phase() 的 `jq -c -n --arg ...`。
# 默认输出必须像真实 jq 一样 pretty-print；只有 -c 才紧凑输出，
# 这样 phase() 一旦丢掉 -c，下面的断言就会失败。
if ! command -v jq >/dev/null 2>&1; then
  json_escape() {
    local s="$1"
    s="${s//\\/\\\\}"; s="${s//\"/\\\"}"; s="${s//$'\n'/\\n}"
    printf '%s' "$s"
  }
  jq() {
    local compact=0 sep
    local -a names=() vals=()
    while [[ $# -gt 0 ]]; do
      case "$1" in
        -c|--compact-output) compact=1; shift ;;
        -n|--null-input) shift ;;
        --arg) names+=("$2"); vals+=("$3"); shift 3 ;;
        *) shift ;;
      esac
    done
    local -a keys=() escaped=()
    local i key
    for i in "${!names[@]}"; do
      case "${names[$i]}" in
        from) key=fromVersion ;;
        to) key=toVersion ;;
        fin)
          # 非终态时 phase() 传空 fin，真实 jq 不会输出 finishedAt。
          [[ -z "${vals[$i]}" ]] && continue
          key=finishedAt ;;
        *) key="${names[$i]}" ;;
      esac
      keys+=("$key"); escaped+=("$(json_escape "${vals[$i]}")")
    done
    if [[ "$compact" -eq 1 ]]; then
      local out=""
      for i in "${!keys[@]}"; do
        out+="\"${keys[$i]}\":\"${escaped[$i]}\","
      done
      printf '{%s}\n' "${out%,}"
    else
      printf '{\n'
      for i in "${!keys[@]}"; do
        if [[ "$i" -lt $(( ${#keys[@]} - 1 )) ]]; then sep=","; else sep=""; fi
        printf '  "%s": "%s"%s\n' "${keys[$i]}" "${escaped[$i]}" "$sep"
      done
      printf '}\n'
    fi
  }
  echo "note: 未安装 jq，phase 测试使用最小替身（默认 pretty，-c 紧凑）"
fi

# --- phase 写入临时 GALWORLD_ROOT，且原子落盘、终态可识别 ---
JOB_ID="upd-selftest"
JOB_STARTED_AT="2026-11-07T00:00:00Z"
TARGET="0.1.1"
CUR="0.1.0-SNAPSHOT"
phase precheck "当前 $CUR，目标 $TARGET"
[[ -f "$STATUS" ]]
[[ ! -e "$STATUS.tmp" ]]
grep -q '"phase":"precheck"' "$STATUS"
grep -q '"version":"0.1.1"' "$STATUS"
grep -q '"toVersion":"0.1.1"' "$STATUS"
grep -q '"fromVersion":"0.1.0-SNAPSHOT"' "$STATUS"
grep -q '"jobId":"upd-selftest"' "$STATUS"
# startedAt 是任务开始时间，不随阶段变化
grep -q '"startedAt":"2026-11-07T00:00:00Z"' "$STATUS"
grep -q '"phaseStartedAt":"' "$STATUS"
! grep -q '"finishedAt"' "$STATUS"
[[ "$PHASE" == "precheck" && "$TERMINAL" -eq 0 ]]
phase success "done"
grep -q '"phase":"success"' "$STATUS"
grep -q '"startedAt":"2026-11-07T00:00:00Z"' "$STATUS"
grep -q '"finishedAt":"' "$STATUS"
[[ "$TERMINAL" -eq 1 ]]

# --- jar_version / has_static_entry ---
JAR_TOOL=""
if command -v jar >/dev/null 2>&1; then
  JAR_TOOL="$(command -v jar)"
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jar" ]]; then
  JAR_TOOL="$JAVA_HOME/bin/jar"
fi

if [[ -n "$JAR_TOOL" ]]; then
  mkdir -p "$TMPROOT/approot/BOOT-INF/classes"
  {
    printf 'galworld:\n  version: 9.9.9\n'
    i=0
    while [[ $i -lt 2000 ]]; do
      printf '# filler line %04d aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n' "$i"
      i=$((i + 1))
    done
  } >"$TMPROOT/approot/BOOT-INF/classes/application.yml"
  "$JAR_TOOL" cf "$TMPROOT/app.jar" -C "$TMPROOT/approot" BOOT-INF/classes/application.yml
  [[ "$(jar_version "$TMPROOT/app.jar")" == "9.9.9" ]]

  # 静态入口放在列表前部 + 500 个长文件名填充，列表 > 64 KiB；
  # 旧写法 `unzip -l | grep -q` 会因 unzip SIGPIPE 在 pipefail 下判失败。
  mkdir -p "$TMPROOT/staticroot/BOOT-INF/classes/static" "$TMPROOT/staticroot/BOOT-INF/classes/zzz"
  printf '<!doctype html>\n' >"$TMPROOT/staticroot/BOOT-INF/classes/static/index.html"
  LONG="$(printf '%140s' '' | tr ' ' 'a')"
  i=0
  while [[ $i -lt 500 ]]; do
    printf 'x\n' >"$TMPROOT/staticroot/BOOT-INF/classes/zzz/${LONG}-$(printf '%03d' "$i").txt"
    i=$((i + 1))
  done
  "$JAR_TOOL" cf "$TMPROOT/static.jar" \
    -C "$TMPROOT/staticroot" BOOT-INF/classes/static/index.html \
    -C "$TMPROOT/staticroot" BOOT-INF/classes/zzz
  [[ "$(unzip -l "$TMPROOT/static.jar" | wc -c)" -gt 65536 ]]
  has_static_entry "$TMPROOT/static.jar"
  ! has_static_entry "$TMPROOT/app.jar"
else
  echo "SKIP: 找不到 jar 工具，跳过 jar_version/has_static_entry 用例"
fi

# --- trap：异常退出补写 failed，并在门户已停时尝试拉起 ---
SYSCALLS="$TMPROOT/systemctl.calls"
systemctl() { printf '%s\n' "$*" >>"$SYSCALLS"; return 0; }

# 1) 已停服、未成功启动、非终态 -> 写 failed 且调用 start
phase stopping "trap-test"
: >"$SYSCALLS"
set +e
(
  LOCK_HELD=1 PHASE=stopping TERMINAL=0
  PORTAL_STOPPED=1 PORTAL_STARTED=0 NO_RESTART=0
  install_traps
  false
)
rc=$?
set -e
[[ "$rc" -eq 1 ]]
grep -q '"phase":"failed"' "$STATUS"
grep -q 'start galworld-portal.service' "$SYSCALLS"

# 2) 终态不被覆盖，但仍尝试拉起门户
phase success "trap-test"
: >"$SYSCALLS"
set +e
(
  LOCK_HELD=1 PHASE=success TERMINAL=1
  PORTAL_STOPPED=1 PORTAL_STARTED=0 NO_RESTART=0
  install_traps
  false
)
rc=$?
set -e
[[ "$rc" -eq 1 ]]
grep -q '"phase":"success"' "$STATUS"
! grep -q '"phase":"failed"' "$STATUS"
grep -q 'start galworld-portal.service' "$SYSCALLS"

# 3) 未停服 -> 写 failed，但不调用 start
phase downloading "trap-test"
: >"$SYSCALLS"
set +e
(
  LOCK_HELD=1 PHASE=downloading TERMINAL=0
  PORTAL_STOPPED=0 PORTAL_STARTED=0 NO_RESTART=0
  install_traps
  false
)
rc=$?
set -e
[[ "$rc" -eq 1 ]]
grep -q '"phase":"failed"' "$STATUS"
[[ ! -s "$SYSCALLS" ]]

# 4) 没拿到锁的并发实例不得改写状态
phase success "trap-test"
set +e
(
  LOCK_HELD=0 PHASE="" TERMINAL=0
  PORTAL_STOPPED=1 PORTAL_STARTED=0
  install_traps
  false
)
rc=$?
set -e
[[ "$rc" -eq 1 ]]
grep -q '"phase":"success"' "$STATUS"

# --- 启动日志判定：无害 WARN 不触发，致命启动错误才触发 ---
START_TS="2026-11-07T00:00:00Z"
JOURNAL_SAMPLE=""
JOURNAL_ARGS_FILE="$TMPROOT/journal.args"
journalctl() { printf '%s\n' "$*" >"$JOURNAL_ARGS_FILE"; printf '%s\n' "$JOURNAL_SAMPLE"; return 0; }

# CompanySchemaMigration 在列迁移后每次启动都会打 BadSqlGrammarException WARN，
# 这是无害的，不能作为冒烟失败依据。
JOURNAL_SAMPLE="2026-11-07 10:00:01.000  WARN 1 --- [main] c.g.s.CompanySchemaMigration : BadSqlGrammarException: Column already exists"
! journal_has_errors
# 门户 unit 没设 StandardOutput，stdout 在 journal 里是 LOG_INFO；不能用 -p err 过滤，
# 否则连 APPLICATION FAILED TO START 都会被丢掉。
grep -Eq '(^| )-p( |$)' "$JOURNAL_ARGS_FILE" \
  && { echo "journal check 不能依赖 -p（会过滤掉 INFO 级 stdout）" >&2; exit 1; }

# 即使日志级别是 INFO（stdout 映射），APPLICATION FAILED TO START 也必须触发。
JOURNAL_SAMPLE="2026-11-07 10:00:02.000  INFO 1 --- [main] o.s.b.d.LoggingFailureAnalysisReporter : APPLICATION FAILED TO START"
journal_has_errors

JOURNAL_SAMPLE="2026-11-07 10:00:03.000  INFO 1 --- [main] o.s.boot.SpringApplication : Application run failed"
! journal_has_errors

echo "updater selftest OK"
