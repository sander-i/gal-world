/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.i18n;

/**
 * 当前线程的语言。
 *
 * 请求线程由 {@link LocaleFilter} 设置；部署 / 接入 / 上传这类后台任务线程
 * 在提交任务时用 {@link I18n#runAs} 固定语言，否则会落到站点默认语言，
 * 用户在页面上看到的日志语言和界面语言就会不一致。
 */
public final class LocaleContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private LocaleContext() {
    }

    public static String current() {
        return CURRENT.get();
    }

    public static void set(String locale) {
        if (locale == null || locale.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(locale);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}
