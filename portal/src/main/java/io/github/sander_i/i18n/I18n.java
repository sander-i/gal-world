/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.i18n;

/**
 * 翻译入口。
 *
 * 用静态门面而不是到处注入 {@link I18nService}：异常消息在 service / deploy /
 * platform 等几十个类里抛出，构造器注入会把依赖链铺满全项目，且静态方法在
 * lambda 与异步线程里也能直接用。
 *
 * 当前语言取自 {@link LocaleContext}（由 {@link LocaleFilter} 按请求设置），
 * 没有请求上下文时用站点默认语言。
 */
public final class I18n {

    private static volatile I18nService instance;

    private I18n() {
    }

    /** 由 Spring 在 {@link I18nService} 初始化时绑定 */
    public static void bind(I18nService service) {
        instance = service;
    }

    /** 当前请求（或当前线程）的语言，如 {@code en} / {@code zh-CN} */
    public static String locale() {
        return LocaleContext.current();
    }

    /** 在指定语言下执行，结束后恢复原语言；异步任务用它固定提交时的语言 */
    public static void runAs(String locale, Runnable body) {
        String prev = LocaleContext.current();
        LocaleContext.set(locale);
        try {
            body.run();
        } finally {
            LocaleContext.set(prev);
        }
    }

    /**
     * 翻译一个键。
     *
     * 参数用 {@code {0}} / {@code {1}} 占位，缺失时保留占位符本身，
     * 这样漏传参数在页面上能直接看出来，而不是静默变成空串。
     */
    public static String t(String key, Object... args) {
        I18nService s = instance;
        String locale = LocaleContext.current();
        // 没有请求语言（纯单元测试、启动期、未走 LocaleFilter 的线程）时不翻译，
        // 直接按原文插值。键就是中文原文，因此断言原文的测试保持稳定；
        // HTTP 请求与后台任务都会显式设置语言，生产路径不受影响。
        if (s == null || locale == null) {
            return I18nService.format(key, args);
        }
        return s.translate(locale, key, args);
    }

    /**
     * 指定语言的翻译，供语言包预览与测试使用。
     *
     * 刻意不叫 t：与 {@link #t(String, Object...)} 重载后，
     * `t(key, someString)` 这种两参调用会被解析成「语言 + 键」，
     * 静默返回错误的字符串（2026-11-17 在 UpdateService 上踩到）。
     */
    public static String translate(String locale, String key, Object... args) {
        I18nService s = instance;
        if (s == null) {
            // 没有 Spring 上下文：按原文插值，与生产路径的兜底一致
            return I18nService.format(key, args);
        }
        return s.translate(locale, key, args);
    }
}
