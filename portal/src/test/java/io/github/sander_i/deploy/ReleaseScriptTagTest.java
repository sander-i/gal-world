/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseScriptTagTest {

    private static String script() throws Exception {
        Path p = Path.of("..", "deploy", "release.sh");
        assertTrue(Files.isRegularFile(p), "找不到 " + p.toAbsolutePath());
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void tagCanBeOverriddenForBranchBuilds() throws Exception {
        String s = script();
        assertTrue(s.contains("GALWORLD_RELEASE_TAG"),
                "分支验证需要自定义 tag，避免与正式版本 tag 冲突");
        assertTrue(s.contains("TAG=\"${GALWORLD_RELEASE_TAG:-v$VERSION}\""),
                "默认仍应是 v<版本>");
    }

    @Test
    void tagRejectsPathTraversalCharacters() throws Exception {
        assertTrue(script().contains("GALWORLD_RELEASE_TAG 只能包含"));
    }

    @Test
    void customTagIsPublishedAsPrerelease() throws Exception {
        // 分支包不能占住 /releases/latest，否则未开启覆盖更新的实例会提示升级到分支包
        assertTrue(script().contains("prerelease="));
    }

    /**
     * 两个渠道的正文语言不同：GitHub 用英文、Gitee 用中文。
     *
     * <p>这条约定原先只写在 {@code .github/workflows/release.yml} 里，本地跑
     * {@code release.sh} 不经过 CI，2026-11-21 发 v0.1.0-rc.1 时 GitHub Release
     * 误用了中文说明。把规则搬进脚本，并用测试锁住。
     */
    @Test
    void githubUsesEnglishNotesAndGiteeUsesChinese() throws Exception {
        String s = script();
        assertTrue(s.contains("docs/release-notes/$VERSION.en.md"),
                "GitHub Release 正文必须读 .en.md");
        assertTrue(s.contains("docs/release-notes/$VERSION.zh.md"),
                "Gitee Release 正文必须读 .zh.md");
        // 不能把两者写反
        assertFalse(s.contains("NOTES_EN=\"$REPO_ROOT/docs/release-notes/$VERSION.zh.md\""),
                "GitHub 的正文变量不能指向中文说明");
    }

    /**
     * GitHub 资产上传必须走 uploads.github.com，且 URL 取自 Release 响应的
     * upload_url。用 api.github.com/repos/.../releases/{id}/assets 会 404
     * （2026-11-21 实测，五个资产全部 404）。
     */
    @Test
    void githubUploadsAssetsViaUploadUrl() throws Exception {
        String s = script();
        assertTrue(s.contains(".upload_url"), "必须从 Release 响应里取 upload_url");
        assertTrue(s.contains("sed 's/{.*}$//'"),
                "upload_url 带 {?name,label} 模板，上传前要剥掉");
        assertFalse(s.contains("api.github.com/repos/$GH_OWNER/$GH_REPO/releases/$GH_REL_ID/assets"),
                "api.github.com 的 assets 端点不接受上传，必须用 uploads.github.com");
    }

    @Test
    void githubPayloadIsBuiltWithJq() throws Exception {
        // 正文含换行与引号，拼 shell 字符串会坏；用 jq 组 JSON 才安全
        assertTrue(script().contains("jq -n --arg tag"),
                "GitHub Release payload 应由 jq 生成");
    }

    @Test
    void channelSelectionFlagsAreHonoured() throws Exception {
        String s = script();
        assertTrue(s.contains("--gitee-only"), "需要只发 Gitee 的开关");
        assertTrue(s.contains("--github-only"), "需要只发 GitHub 的开关");
        // 两个开关都要真正控制发布段落
        assertTrue(s.contains("if [[ \"$PUBLISH_GITEE\" == \"1\" ]]"));
        assertTrue(s.contains("if [[ \"$PUBLISH_GITHUB\" == \"1\" ]]"));
    }
}
