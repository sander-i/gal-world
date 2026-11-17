/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.i18n;

import io.github.sander_i.domain.Company;
import io.github.sander_i.domain.LocalizationGroup;
import io.github.sander_i.domain.Tag;

/**
 * 把公司 / 汉化组 / 标签按当前请求语言解析成展示值。
 *
 * 主列存默认文字，{@code i18n_text} 存按语言的覆盖；缺失回退主列。
 * 解析出的文字同时用于展示与搜索，所以搜索「Yuzusoft」在英文界面能命中，
 * 搜索「ゆずソフト」在日文界面也能命中。
 */
public final class LocalizedText {

    private LocalizedText() {
    }

    /** 公司 / 汉化组共用的展示项 */
    public record OrgText(String name, String alias, String website, String description) {
    }

    public static OrgText of(Company c, String locale) {
        if (c == null) {
            return new OrgText("", "", "", "");
        }
        String json = c.getI18nText();
        return new OrgText(
                I18nText.pick(json, locale, "name", nz(c.getName())),
                I18nText.pick(json, locale, "alias", nz(c.getAlias())),
                nz(c.getWebsite()),
                I18nText.pick(json, locale, "description", nz(c.getDescription())));
    }

    public static OrgText of(LocalizationGroup g, String locale) {
        if (g == null) {
            return new OrgText("", "", "", "");
        }
        String json = g.getI18nText();
        return new OrgText(
                I18nText.pick(json, locale, "name", nz(g.getName())),
                I18nText.pick(json, locale, "alias", nz(g.getAlias())),
                nz(g.getWebsite()),
                I18nText.pick(json, locale, "description", nz(g.getDescription())));
    }

    /** 标签展示名 */
    public static String of(Tag t, String locale) {
        if (t == null) {
            return "";
        }
        return I18nText.pick(t.getI18nText(), locale, "name", nz(t.getName()));
    }

    /** 标签别名（编辑页搜索用） */
    public static String aliasOf(Tag t, String locale) {
        if (t == null) {
            return "";
        }
        return I18nText.pick(t.getI18nText(), locale, "alias", nz(t.getAlias()));
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
