/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

/**
 * 给低频读接口算 ETag。
 *
 * <p>不用 {@code ShallowEtagHeaderFilter}：它会把响应缓冲起来并显式设置
 * Content-Length，Tomcat 侧就跳过 gzip 压缩了（实测 /api/i18n 从 28 KB
 * 回到 114 KB）。这里只按内容算标签，是否 304 由控制器自己判断。
 *
 * <p><b>必须是弱 ETag（{@code W/"..."}）</b>：Tomcat 的 CompressionConfig
 * 遇到强 ETag 会直接跳过压缩（强标签按未压缩字节算，不能代表压缩后的表示）。
 */
final class ETags {

    private ETags() {
    }

    /** 弱标签：语义等价即可，浏览器回传时原样带回 */
    static String of(Object body) {
        int h = body == null ? 0 : body.hashCode();
        return "W/\"" + Integer.toHexString(h) + "\"";
    }

    static boolean matches(String etag, String ifNoneMatch) {
        if (etag == null || ifNoneMatch == null) {
            return false;
        }
        String want = strong(etag);
        for (String part : ifNoneMatch.split(",")) {
            // 有的代理会剥掉 W/ 前缀，比较时两边都归一化
            if (want.equals(strong(part.strip()))) {
                return true;
            }
        }
        return false;
    }

    private static String strong(String tag) {
        return tag.startsWith("W/") ? tag.substring(2) : tag;
    }
}
