# Git hooks

仓库内的 hook 脚本，通过 `core.hooksPath` 启用。**每个 clone 都要执行一次**：

```bash
git config core.hooksPath .githooks
```

Windows 上用 Git Bash 执行同一条命令即可。

## pre-push

阻止直接推送 `master`。master 只允许通过 Pull Request 合并进入。

- 删除分支（local sha 全 0）不拦，那是清理不是往 master 加东西
- 确实需要直接推送时用 `git push --no-verify <remote> master` —— 但 GitHub 侧开了
  `enforce_admins`，仍会被远端拒绝；Gitee 侧则会被放行，所以要靠这道本地检查

背景：2026-11-20 出过一次，用户说「部署到线上验证」，代理把两个分支 merge 进
master 并推了 gitee + github，绕过了 PR 审批。GitHub 的分支保护能挡住这种情况，
Gitee 的没有公开 API、只能网页配置且对仓库所有者不强制，所以本地 hook 是两边共用
的兜底。
