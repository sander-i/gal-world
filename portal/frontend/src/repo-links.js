/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

/**
 * 项目仓库的「发布页」与「版本 tag 页」链接。
 *
 * 仓库地址是可配置的（设置 → 关于的 repoUrl，默认取 GALWORLD_REPO_URL），
 * 不同托管平台的路径规则不一样，不能写死某一家：
 *
 *   GitHub / Gitee / Gitea / Codeberg   {base}/releases   {base}/releases/tag/v1.2.3
 *   GitLab（含 gitlab.com 与自建）      {base}/-/releases  {base}/-/tags/v1.2.3
 *
 * 判断依据只有 host 里有没有 `gitlab.`。自建 GitLab 若域名里不含 gitlab
 * （例如 `git.example.com`），链接会退化成 GitHub 风格而 404——此时把
 * repoUrl 直接配成完整的 `.../-/releases` 页面地址即可绕开，
 * 因为这里只做「根地址 + 固定后缀」的拼接。
 */

/** 去掉尾部斜杠；空值统一成空串，让调用方用 falsy 判断是否隐藏链接 */
export function normalizeRepoBase(repoBase) {
  return String(repoBase || '').trim().replace(/\/+$/, '')
}

/** host 里含 `gitlab.`（含自建子域）就按 GitLab 路径规则走 */
function isGitlab(base) {
  const url = /^https?:\/\//i.test(base) ? base : `https://${base}`
  try {
    return /(^|\.)gitlab\./i.test(new URL(url).hostname)
  } catch {
    // 连不成合法 URL 时按字符串判断，宁可命中也不拼错
    return /gitlab/i.test(base)
  }
}

/** 发布页；仓库地址为空时返回空串，调用方据此不渲染链接 */
export function releasesUrl(repoBase) {
  const base = normalizeRepoBase(repoBase)
  if (!base) return ''
  return isGitlab(base) ? `${base}/-/releases` : `${base}/releases`
}

/**
 * 版本号规整：去掉 `v` 前缀与 `-SNAPSHOT` 后缀。
 * 不是数字开头（如 `dev`、`unknown`）时返回空串，表示没有对应 tag。
 */
export function normalizeTagVersion(version) {
  const v = String(version || '').trim().replace(/^v/i, '').replace(/-SNAPSHOT$/i, '')
  return /^\d/.test(v) ? v : ''
}

/** tag 页；版本号不可用时退回发布页，与「当前版本」入口的兜底一致 */
export function tagUrl(repoBase, version) {
  const base = normalizeRepoBase(repoBase)
  if (!base) return ''
  const v = normalizeTagVersion(version)
  if (!v) return releasesUrl(base)
  return isGitlab(base) ? `${base}/-/tags/v${v}` : `${base}/releases/tag/v${v}`
}
