@AGENTS.md

# Claude Code 备注

上面的 `AGENTS.md` 是通用规则，以下只针对 Claude Code：

- 改动前先读 `README.md` 的「架构」与 `docs/ENVIRONMENT.md`，本项目有大量平台差异（Linux / Windows）。
- 版本号只改 `portal/pom.xml` 的 `<version>`。
- `portal/src/main/resources/static/` 是 gitignore 的构建产物，不要提交；打包前由
  `deploy/release.sh` 自动构建。
- 涉及系统状态的脚本（`deploy.sh`、`deploy/win/*.ps1`、`uninstall.sh`）执行前必须问用户。
