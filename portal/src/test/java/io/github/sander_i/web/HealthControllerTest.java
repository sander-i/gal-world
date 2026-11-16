/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.deploy.DeploySecurity;
import io.github.sander_i.security.WriteSecurity;
import io.github.sander_i.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    /** 未配置令牌时 DeploySecurity 会生成并落盘，测试必须写到临时目录 */
    @TempDir
    static Path tempDir;

    private static HealthController controller(String deployToken, String writeToken) {
        return controller(deployToken, writeToken, true);
    }

    private static HealthController controller(String deployToken, String writeToken,
                                               boolean repoUrlPublic) {
        SettingsService settings = mock(SettingsService.class);
        when(settings.repoUrl()).thenReturn("https://example.com/owner/gal-world");
        when(settings.repoUrlPublic()).thenReturn(repoUrlPublic);
        return new HealthController("0.1.4", settings,
                new DeploySecurity(new PathConfig(tempDir.toString()), deployToken, "127.0.0.1/32"),
                new WriteSecurity(writeToken));
    }

    @Test
    void reportsConfiguredTokens() {
        Map<String, Object> body = controller("fixed", "write").health();
        assertEquals(Boolean.TRUE, body.get("deployTokenConfigured"));
        assertEquals(Boolean.TRUE, body.get("writeTokenConfigured"));
    }

    @Test
    void randomDeployTokenCountsAsUnconfigured() {
        // 未配置时 DeploySecurity 会生成随机令牌，但对外必须报「未配置」，
        // 否则页面会提示“向部署者索取”，而实际只能从启动日志里找
        Map<String, Object> body = controller("", "").health();
        assertEquals(Boolean.FALSE, body.get("deployTokenConfigured"));
        assertEquals(Boolean.FALSE, body.get("writeTokenConfigured"));
    }

    @Test
    void keepsExistingHealthFields() {
        Map<String, Object> body = controller("", "").health();
        assertEquals("UP", body.get("status"));
        assertEquals("gal-world-portal", body.get("app"));
        assertEquals("0.1.4", body.get("version"));
    }

    @Test
    void exposesRepoUrlWhenPublic() {
        Map<String, Object> body = controller("", "", true).health();
        assertEquals("https://example.com/owner/gal-world", body.get("repoUrl"));
    }

    @Test
    void hidesRepoUrlWhenSwitchIsOff() {
        Map<String, Object> body = controller("", "", false).health();
        assertEquals(false, body.containsKey("repoUrl"));
    }
}
