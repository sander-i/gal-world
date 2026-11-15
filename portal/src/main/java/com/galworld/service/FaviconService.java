/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从公司官网抓取站点图标。
 *
 * 只做一件事：GET 页面 → 解析 &lt;link rel="icon"&gt; → 回退 /favicon.ico →
 * 校验确实是图片 → 返回字节。网络失败、超时、非图片一律返回 empty，
 * 由调用方决定是否落盘。
 */
@Service
public class FaviconService {

    private static final Logger log = LoggerFactory.getLogger(FaviconService.class);

    private static final int MAX_HTML_BYTES = 512 * 1024;
    private static final int MAX_ICON_BYTES = 2 * 1024 * 1024;

    private static final Pattern LINK_TAG = Pattern.compile("(?is)<link\\b[^>]*>");
    private static final Pattern ATTR = Pattern.compile(
            "(?is)([a-z-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern SCHEME = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://.*$");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 抓取官网图标；任何失败返回 empty */
    public Optional<byte[]> fetch(String website) {
        URI page = normalize(website);
        if (page == null) return Optional.empty();
        try {
            String html = getText(page);
            List<URI> candidates = new ArrayList<>(iconCandidates(html, page));
            candidates.add(origin(page).resolve("/favicon.ico"));
            for (URI uri : candidates) {
                Optional<byte[]> icon = getImage(uri);
                if (icon.isPresent()) {
                    log.info("已获取公司站点图标: {}", uri);
                    return icon;
                }
            }
        } catch (Exception e) {
            log.info("获取公司站点图标失败 {}: {}", website, e.toString());
        }
        return Optional.empty();
    }

    /** 从 HTML 提取 rel 含 icon 的 link，按文档顺序解析成绝对 URI */
    static List<URI> iconCandidates(String html, URI base) {
        List<URI> out = new ArrayList<>();
        if (html == null || html.isBlank()) return out;
        Matcher tags = LINK_TAG.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            String rel = attr(tag, "rel");
            String href = attr(tag, "href");
            if (rel == null || href == null || href.isBlank()) continue;
            if (!rel.toLowerCase(java.util.Locale.ROOT).contains("icon")) continue;
            try {
                URI uri = base.resolve(href.strip());
                if (uri.getScheme() != null
                        && (uri.getScheme().equalsIgnoreCase("http")
                        || uri.getScheme().equalsIgnoreCase("https"))) {
                    out.add(uri);
                }
            } catch (RuntimeException ignored) {
                // 非法 href 跳过
            }
        }
        return out;
    }

    private static String attr(String tag, String name) {
        Matcher m = ATTR.matcher(tag);
        while (m.find()) {
            if (!m.group(1).equalsIgnoreCase(name)) continue;
            String v = m.group(2) != null ? m.group(2)
                    : m.group(3) != null ? m.group(3) : m.group(4);
            return v == null ? null : v.strip();
        }
        return null;
    }

    private static URI normalize(String website) {
        String w = website == null ? "" : website.strip();
        if (w.isEmpty()) return null;
        if (!SCHEME.matcher(w).matches()) w = "https://" + w;
        try {
            URI uri = URI.create(w);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            return uri;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static URI origin(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/", null, null);
        } catch (Exception e) {
            return uri;
        }
    }

    private String getText(URI uri) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(4))
                .header("User-Agent", "GalWorld-Portal/1.0 favicon")
                .GET().build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2) return "";
        byte[] body = res.body();
        int len = Math.min(body.length, MAX_HTML_BYTES);
        return new String(body, 0, len, StandardCharsets.UTF_8);
    }

    private Optional<byte[]> getImage(URI uri) {
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(4))
                    .header("User-Agent", "GalWorld-Portal/1.0 favicon")
                    .GET().build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 != 2) return Optional.empty();
            byte[] body = res.body();
            if (body.length == 0 || body.length > MAX_ICON_BYTES) return Optional.empty();
            return isImage(body) ? Optional.of(body) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 用 ImageIO 判断内容是否真的是图片 */
    static boolean isImage(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) return false;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            return readers.hasNext();
        } catch (Exception e) {
            return false;
        }
    }
}
