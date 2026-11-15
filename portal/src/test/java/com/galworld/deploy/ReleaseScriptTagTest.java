/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.deploy;

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
}
