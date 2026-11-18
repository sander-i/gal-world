/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.i18n;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 按请求决定语言，写进 {@link LocaleContext}，请求结束清理。
 *
 * 取值顺序：
 * <ol>
 *   <li>{@code X-GalWorld-Locale} 头——前端所有请求都带，用户选的界面语言</li>
 *   <li>{@code ?lang=} 查询参数——EventSource / 浏览器直连下载带不了自定义头</li>
 *   <li>{@code Accept-Language}——直接访问 API 的脚本与浏览器默认</li>
 *   <li>站点默认语言</li>
 * </ol>
 *
 * 头里只接受已加载的语言 id，其它值一律忽略，防止任意字符串进入日志与翻译链。
 */
@Component
public class LocaleFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-GalWorld-Locale";
    public static final String PARAM = "lang";

    private final I18nService i18n;

    public LocaleFilter(I18nService i18n) {
        this.i18n = i18n;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String chosen = resolve(req);
        LocaleContext.set(chosen);
        try {
            chain.doFilter(req, res);
        } finally {
            LocaleContext.clear();
        }
    }

    private String resolve(HttpServletRequest req) {
        String fromHeader = LocalePack.normalize(req.getHeader(HEADER));
        if (!fromHeader.isEmpty() && i18n.exists(fromHeader)) {
            return fromHeader;
        }
        String fromParam = LocalePack.normalize(req.getParameter(PARAM));
        if (!fromParam.isEmpty() && i18n.exists(fromParam)) {
            return fromParam;
        }
        String accept = req.getHeader("Accept-Language");
        if (accept != null && !accept.isBlank()) {
            // 只取最靠前的一个；权重排序交给浏览器，这里不做完整解析
            for (String part : accept.split(",")) {
                String tag = part.split(";")[0].trim();
                String norm = LocalePack.normalize(tag);
                if (!norm.isEmpty() && i18n.exists(norm)) {
                    return norm;
                }
            }
        }
        return i18n.defaultLocale();
    }
}
