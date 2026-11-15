/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 写接口保护。
 *
 * 关键约定：**未配置令牌时一律放行**——升级上来的实例行为必须保持不变，
 * 只有显式配置 galworld.write.token 的部署才进入受保护模式。
 */
class WriteSecurityInterceptorTest {

    private MockHttpServletRequest req(String method, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRemoteAddr("127.0.0.1");
        return r;
    }

    private MockHttpServletResponse prehandle(WriteSecurityInterceptor it,
                                              MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        it.preHandle(req, res, new Object());
        return res;
    }

    @Test
    void disabledByDefaultAllowsWrites() throws Exception {
        var it = new WriteSecurityInterceptor(new WriteSecurity(""));
        assertFalse(new WriteSecurity("").enabled());
        MockHttpServletRequest r = req("POST", "/api/games/x/start");
        assertTrue(it.preHandle(r, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void blankTokenCountsAsDisabled() {
        assertFalse(new WriteSecurity("   ").enabled());
        assertFalse(new WriteSecurity(null).enabled());
    }

    @Test
    void rejectsWriteWithoutTokenWhenEnabled() throws Exception {
        var it = new WriteSecurityInterceptor(new WriteSecurity("secret"));
        var res = prehandle(it, req("POST", "/api/games/x/start"));
        assertEquals(403, res.getStatus());
        assertTrue(res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("写接口令牌"));
    }

    @Test
    void acceptsWriteWithCorrectToken() throws Exception {
        var it = new WriteSecurityInterceptor(new WriteSecurity("secret"));
        MockHttpServletRequest r = req("POST", "/api/games/x/start");
        r.addHeader(WriteSecurity.HEADER, "secret");
        assertTrue(it.preHandle(r, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void rejectsWrongToken() throws Exception {
        var it = new WriteSecurityInterceptor(new WriteSecurity("secret"));
        MockHttpServletRequest r = req("DELETE", "/api/games/x");
        r.addHeader(WriteSecurity.HEADER, "wrong");
        assertEquals(403, prehandle(it, r).getStatus());
    }

    @Test
    void allowsReadsWithoutToken() throws Exception {
        var it = new WriteSecurityInterceptor(new WriteSecurity("secret"));
        assertTrue(it.preHandle(req("GET", "/api/games"), new MockHttpServletResponse(), new Object()));
        assertTrue(it.preHandle(req("HEAD", "/api/games"), new MockHttpServletResponse(), new Object()));
        assertTrue(it.preHandle(req("OPTIONS", "/api/games"), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void skipsDeployAndAdminPaths() throws Exception {
        var it = new WriteSecurityInterceptor(new WriteSecurity("secret"));
        // 这两组自带部署令牌，重复校验会让页面拿不到 SSE 日志
        assertTrue(it.preHandle(req("POST", "/api/deploy/steps/x/run"),
                new MockHttpServletResponse(), new Object()));
        assertTrue(it.preHandle(req("POST", "/api/admin/settings"),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void ignoresNonApiPaths() throws Exception {
        var it = new WriteSecurityInterceptor(new WriteSecurity("secret"));
        assertTrue(it.preHandle(req("POST", "/somewhere/else"),
                new MockHttpServletResponse(), new Object()));
    }
}
