/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.deploy.DeploySecurity;
import io.github.sander_i.i18n.I18nService;
import io.github.sander_i.i18n.LocaleMarketClient;
import io.github.sander_i.repo.SettingRepository;
import io.github.sander_i.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 语言包安装接口：四种请求体形态与令牌闸门。
 *
 * <p>市场下载走 {@link LocaleMarketClient}，这里用 mock 替掉网络，
 * 只验证「下载结果被正确接进导入链路」与错误码映射。
 */
class AdminI18nControllerTest {

    @TempDir
    Path root;

    private I18nService i18n;
    private LocaleMarketClient market;
    private AdminI18nController controller;
    private MockHttpServletRequest withToken;

    @BeforeEach
    void setUp() throws Exception {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        when(repo.findAll()).thenReturn(java.util.List.of());
        i18n = new I18nService(new PathConfig(root.toString()), repo);
        // init() 是 @PostConstruct 的包内方法，跨包测试用反射触发
        java.lang.reflect.Method init = I18nService.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(i18n);

        SettingsService settings = mock(SettingsService.class);
        when(settings.get(anyString())).thenReturn("");
        market = mock(LocaleMarketClient.class);

        DeploySecurity security = new DeploySecurity(new PathConfig(root.toString()),
                "test-token", "127.0.0.1/32");
        controller = new AdminI18nController(i18n, settings, security, market);

        withToken = new MockHttpServletRequest();
        withToken.setRemoteAddr("127.0.0.1");
        withToken.addHeader(DeploySecurity.HEADER, "test-token");
    }

    private Map<String, Object> body(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return out;
    }

    // ---------------- 令牌 ----------------

    @Test
    void 无令牌返回403() {
        var req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        var res = controller.install(body("url", "https://x/ko.po"), req);
        assertEquals(403, res.getStatusCode().value());
        verifyNoInteractions(market);
    }

    // ---------------- 上传文件 ----------------

    @Test
    void 上传po文件按源文键导入() {
        var res = controller.install(body(
                "file", "msgid \"\"\nmsgstr \"\"\n\"Language: ko\\n\"\n\n"
                        + "msgid \"保存\"\nmsgstr \"저장\"\n",
                "fileName", "ko.po"), withToken);
        assertEquals(200, res.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) res.getBody();
        assertEquals("po", out.get("format"));
        assertEquals(1, out.get("imported"));
        assertEquals("저장", i18n.messages("ko").get("保存"));
    }

    @Test
    void 空请求体返回400() {
        var res = controller.install(null, withToken);
        assertEquals(400, res.getStatusCode().value());
    }

    @Test
    void 三种字段都缺时提示含url() {
        var res = controller.install(body("foo", "bar"), withToken);
        assertEquals(400, res.getStatusCode().value());
        assertTrue(String.valueOf(res.getBody()).contains("url"));
    }

    // ---------------- 从地址安装 ----------------

    @Test
    void 从地址下载后走同一导入链路() throws IOException {
        when(market.fetch("https://packs.example.com/ko.po")).thenReturn(
                new LocaleMarketClient.Fetched("ko.po",
                        "msgid \"\"\nmsgstr \"\"\n\"Language: ko\\n\"\n\n"
                                + "msgid \"保存\"\nmsgstr \"저장\"\n",
                        "https://packs.example.com/ko.po"));
        var res = controller.install(body("url", "https://packs.example.com/ko.po"), withToken);
        assertEquals(200, res.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) res.getBody();
        assertEquals("po", out.get("format"));
        assertEquals("ko.po", out.get("fileName"));
        assertEquals("https://packs.example.com/ko.po", out.get("sourceUrl"));
        assertEquals("저장", i18n.messages("ko").get("保存"));
    }

    @Test
    void 请求体里的id优先于文件名() throws IOException {
        when(market.fetch(anyString())).thenReturn(new LocaleMarketClient.Fetched(
                "messages.po",
                "msgid \"\"\nmsgstr \"\"\n\"Language: ko\\n\"\n\nmsgid \"保存\"\nmsgstr \"저장\"\n",
                "https://x/messages.po"));
        // 包内头部的 Language 也写 ko，这里主要验证不因文件名 messages 报非法 id
        var res = controller.install(body("url", "https://x/messages.po", "id", "ko"), withToken);
        assertEquals(200, res.getStatusCode().value());
        assertNotNull(i18n.messages("ko").get("保存"));
    }

    @Test
    void 地址拉取失败返回400() throws IOException {
        when(market.fetch(anyString())).thenThrow(new IOException("拒绝访问内网地址: 127.0.0.1"));
        var res = controller.install(body("url", "http://127.0.0.1/ko.po"), withToken);
        assertEquals(400, res.getStatusCode().value());
        assertTrue(String.valueOf(res.getBody()).contains("内网"));
    }

    @Test
    void 下载内容与源文完全对不上时返回400() throws IOException {
        when(market.fetch(anyString())).thenReturn(new LocaleMarketClient.Fetched(
                "xx.csv", "source,target\nalpha,beta\n", "https://x/xx.csv"));
        var res = controller.install(body("url", "https://x/xx.csv"), withToken);
        assertEquals(400, res.getStatusCode().value());
        assertTrue(String.valueOf(res.getBody()).contains("没有可用的条目"),
                String.valueOf(res.getBody()));
    }
}
