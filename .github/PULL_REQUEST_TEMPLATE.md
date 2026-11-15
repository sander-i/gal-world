## 这个 PR 做了什么

<!-- 一句话说明；如果是修 bug，写清「原来怎样 → 现在怎样」 -->

## 关联 issue

<!-- Closes #123 / Refs #456；没有 issue 的话，说明为什么这个小改动不需要单独开 -->

## 变更类型

- [ ] 缺陷修复
- [ ] 新功能
- [ ] 重构（不改变外部行为）
- [ ] 文档 / 注释
- [ ] 构建、部署脚本或 CI
- [ ] 依赖升级

## 怎么验证的

<!--
  贴出实际跑过的命令和结果，不要只写「测试通过」。
  本地等价于 CI 的一套命令：

    前端：cd portal/frontend && npm ci && npm test && npm run check:theme && npm run build
    后端：./mvnw -B -f portal/pom.xml test
    全部：bash tools/ci.sh

  Windows 侧的部署脚本改动，请说明在哪个系统上验证过。
-->

```
（命令与输出摘要）
```

**手工验证步骤（如涉及界面或部署）**：

1.
2.

## 影响面与风险

- [ ] 数据库结构有变更（需要迁移脚本）
- [ ] `game.env` 或配置项格式有变更（需同步更新文档与模板）
- [ ] 部署脚本有变更（Linux 与 Windows 两侧都要考虑）
- [ ] 有破坏性变更（需要在 CHANGELOG 中注明）
- [ ] 只是内部实现调整，对外无感知

## 自查

- [ ] 新增文件已带 SPDX 许可证头（`SPDX-License-Identifier: MIT`）
- [ ] 没有提交任何凭据、内网地址、本机绝对路径、游戏本体或存档
- [ ] 相关文档已同步（README / docs / CHANGELOG）
- [ ] 若改动涉及安全模型（写操作鉴权、部署 token、CIDR 白名单），已更新 SECURITY.md
