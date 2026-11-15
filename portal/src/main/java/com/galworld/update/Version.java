/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.update;

import java.util.Locale;

public final class Version {
    private Version() {}

    public static int compare(String a, String b) {
        int[] pa = parts(a);
        int[] pb = parts(b);
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(pa[i], pb[i]);
            if (c != 0) return c;
        }
        return 0;
    }

    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    /** 正式 tag（vX.Y.Z）才算普通升级；带 - 后缀的是分支/预发布包，只能在覆盖更新里用 */
    public static boolean isStableTag(String tag) {
        String s = tag == null ? "" : tag.strip();
        return !s.contains("-");
    }

    public static String normalize(String v) {
        if (v == null) return "";
        String s = v.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith("v")) s = s.substring(1);
        int dash = s.indexOf('-');
        return dash >= 0 ? s.substring(0, dash) : s;
    }

    private static int[] parts(String v) {
        int[] out = new int[3];
        String s = v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith("v")) s = s.substring(1);
        String[] segments = s.split("[^0-9]+");
        int idx = 0;
        for (String p : segments) {
            if (p.isEmpty() || idx >= 3) continue;
            try {
                out[idx++] = Integer.parseInt(p);
            } catch (NumberFormatException ignored) {
                // 非数字段跳过
            }
        }
        return out;
    }
}
