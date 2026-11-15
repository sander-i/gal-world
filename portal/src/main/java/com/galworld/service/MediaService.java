/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import com.galworld.config.PathConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 封面 / 图标上传。
 *
 * 只接受图片扩展名，按文件头校验真实类型（不看客户端声明的 Content-Type），
 * 存到 {@code ~/galworld/data/media/<gameId>/}，DB 里记录相对 URL。
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private static final Set<String> ALLOWED_EXT = Set.of("png", "jpg", "jpeg", "webp", "gif");
    /** 宣传 CG 额外允许视频，仅用于展示，不进核心链路 */
    private static final Set<String> ALLOWED_VIDEO_EXT = Set.of("mp4", "webm");
    /** 支持的媒体种类：封面（多图）、图标（单图）、宣传 CG（多份，图片或视频） */
    private static final java.util.Set<String> ALLOWED_KINDS = java.util.Set.of(
            "cover", "icon", "cg");

    /** 派生图文件名后缀（cover-1.jpg.s640 / cover-1.jpg.s1280），列表时必须排除；临时写盘残留一并排除 */
    private static final java.util.regex.Pattern VARIANT =
            java.util.regex.Pattern.compile("\\.s\\d+$|\\.tmp-\\d+$");

    private final PathConfig paths;
    private final SettingsService settings;
    private final ImageVariantService variants;

    public MediaService(PathConfig paths, SettingsService settings, ImageVariantService variants) {
        this.paths = paths;
        this.settings = settings;
        this.variants = variants;
    }

    /** 媒体放在游戏目录**同级**的 <id>.meta/media/，不污染游戏本体 */
    public Path mediaDir(String gameId) {
        return paths.gameMediaDir(gameId);
    }

    public int coverMaxCount() {
        return settings.coverMaxCount();
    }

    public int cgMaxCount() {
        return settings.cgMaxCount();
    }

    public long imageMaxBytes() {
        return settings.imageMaxBytes();
    }

    public long videoMaxBytes() {
        return settings.videoMaxBytes();
    }

    public record Saved(String url, String kind, long bytes) {
    }

    /**
     * 保存媒体。
     *
     * kind: cover / icon / cg
     * cover 与 cg 支持多份，文件名带序号（cover-1.png、cg-2.mp4），
     * 序号由调用方给出（新增时取当前最大 +1）。icon 是单张，固定名 icon.<ext>。
     *
     * @param seq 序号；icon 忽略此参数
     */
    public Saved save(String gameId, String kind, int seq, String originalName, InputStream in)
            throws IOException {
        if (kind == null || !ALLOWED_KINDS.contains(kind)) {
            throw new IllegalArgumentException("kind 必须是 " + String.join(" / ", ALLOWED_KINDS));
        }
        boolean isCg = kind.equals("cg");
        String ext = extension(originalName);
        boolean video = isCg && ALLOWED_VIDEO_EXT.contains(ext);
        if (!video && !ALLOWED_EXT.contains(ext)) {
            String hint = isCg
                    ? String.join("/", ALLOWED_EXT) + "/" + String.join("/", ALLOWED_VIDEO_EXT)
                    : String.join("/", ALLOWED_EXT);
            throw new IllegalArgumentException("只支持 " + hint + "，收到: " + originalName);
        }

        long limit = video ? settings.videoMaxBytes() : settings.imageMaxBytes();
        // 先读头部做类型嗅探，再流式落盘：宣传视频可达几百 MB，
        // 一次性 readAllBytes 会让堆里同时存在整份文件
        byte[] head = in.readNBytes(16);
        if (head.length == 0) {
            throw new IllegalArgumentException("文件为空");
        }
        String detected = video ? sniffVideo(head) : sniff(head);
        if (detected == null) {
            throw new IllegalArgumentException(video
                    ? "不是有效的视频文件（需要 MP4 或 WebM）"
                    : "不是有效的图片文件");
        }

        // icon 固定名；cover / cg 带序号
        String base = kind.equals("icon") ? "icon" : kind + "-" + seq;
        String filename = base + "." + detected;
        Path dir = mediaDir(gameId);
        Files.createDirectories(dir);
        Path target = dir.resolve(filename);
        // 同一序号换扩展名时清掉旧文件（cover-1.png → cover-1.jpg）
        try (var old = Files.list(dir)) {
            for (Path p : old.toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith(base + ".") && !n.equals(filename)) {
                    Files.deleteIfExists(p);
                }
            }
        }
        // 流式写盘：先写已读的头部，再拷剩余，边拷边计数以便超限立即中止
        long total = head.length;
        java.security.MessageDigest md = digest();
        try (var out = Files.newOutputStream(target)) {
            out.write(head);
            md.update(head);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > limit) {
                    out.close();
                    Files.deleteIfExists(target);
                    throw new IllegalArgumentException("文件超过 " + (limit / 1024 / 1024) + " MB");
                }
                out.write(buf, 0, n);
                md.update(buf, 0, n);
            }
        } catch (IllegalArgumentException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        log.info("媒体已保存 {} {}/{} ({} bytes)", gameId, kind, filename, total);
        // 派生图必须在旧文件清理之后生成，否则会被上面的删除逻辑连带删掉
        // icon 显示尺寸 16px，派生无收益
        if (!video && !kind.equals("icon") && settings.imageVariantEnabled()) {
            variants.generate(target);
        }
        // URL 带内容版本：文件名固定（便于覆盖），但每次上传都换 URL，
        // 否则浏览器会继续用 max-age 内的旧缓存，表现为"传了封面但没变化"
        return new Saved("/media/" + gameId + "/" + filename + "?v=" + versionOf(md), kind, total);
    }

    private java.security.MessageDigest digest() {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 由内容摘要生成 8 位版本号，同一内容版本不变 */
    private String versionOf(java.security.MessageDigest md) {
        byte[] h = md.digest();
        return String.format("%08x", java.nio.ByteBuffer.wrap(h, 0, 4).getInt() & 0xffffffffL);
    }

    /** 删除指定序号的媒体文件 */
    public boolean deleteFile(String gameId, String kind, int seq) throws IOException {
        Path dir = mediaDir(gameId);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        String base = kind.equals("icon") ? "icon" : kind + "-" + seq;
        boolean removed = false;
        try (var s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith(base + ".")) {
                    Files.deleteIfExists(p);
                    removed = true;
                }
            }
        }
        return removed;
    }

    /**
     * 把文件从旧序号重命名为新序号（删除后重排用）。
     *
     * 注意：不能直接 move 到目标名 —— 重排时会出现 0→1、1→2 这样的链式改名，
     * 直接改名会把后面的文件覆盖掉（曾把三张封面变成只剩一张）。
     * 正确做法是先全部改成唯一临时名，再改成目标名。
     */
    public void renumberBatch(String gameId, String kind, Map<Integer, Integer> seqMap)
            throws IOException {
        if (seqMap.isEmpty()) {
            return;
        }
        Path dir = mediaDir(gameId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        Map<Path, Path> tmpToFinal = new java.util.LinkedHashMap<>();

        // 第一轮：旧名 → 临时名
        for (Map.Entry<Integer, Integer> e : seqMap.entrySet()) {
            if (e.getKey().equals(e.getValue())) {
                continue;
            }
            String fromBase = kind + "-" + e.getKey();
            try (var s = Files.list(dir)) {
                for (Path p : s.toList()) {
                    String n = p.getFileName().toString();
                    if (n.startsWith(fromBase + ".")) {
                        String ext = n.substring(fromBase.length());
                        Path tmp = dir.resolve(".tmp-" + fromBase + "-" + System.nanoTime() + ext);
                        Files.move(p, tmp);
                        tmpToFinal.put(tmp, dir.resolve(kind + "-" + e.getValue() + ext));
                    }
                }
            }
        }

        // 第二轮：临时名 → 目标名
        for (Map.Entry<Path, Path> e : tmpToFinal.entrySet()) {
            Files.move(e.getKey(), e.getValue(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 列出某类媒体在磁盘上的文件名（用于编辑页展示服务器路径） */
    public List<Path> listFiles(String gameId, String kind) throws IOException {
        Path dir = mediaDir(gameId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var s = Files.list(dir)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                if (VARIANT.matcher(n).find()) {
                    return false;
                }
                return kind.equals("icon") ? n.startsWith("icon.") : n.startsWith(kind + "-");
            }).sorted().toList();
        }
    }

    /** 内容哈希做版本号，同一文件重复上传版本不变 */
    public List<Path> list(String gameId) throws IOException {
        Path dir = mediaDir(gameId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var s = Files.list(dir)) {
            return s.toList();
        }
    }

    private String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 按文件头判断真实图片类型；不依赖客户端声明 */
    private String sniff(byte[] b) {        if (b.length < 12) {
            return null;
        }
        // PNG: 89 50 4E 47
        if ((b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) {
            return "png";
        }
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        // GIF: GIF8
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "gif";
        }
        // WEBP: RIFF....WEBP
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    /**
     * 视频容器嗅探。
     * MP4/MOV：第 4~7 字节是 "ftyp"；WebM/Matroska：开头是 EBML 头 1A 45 DF A3。
     * 声明扩展名与真实容器不一致时返回真实容器对应的扩展名。
     */
    private String sniffVideo(byte[] b) {
        if (b.length < 12) {
            return null;
        }
        boolean mp4 = b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
        boolean webm = (b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45
                && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3;
        if (mp4) {
            return "mp4";
        }
        if (webm) {
            // WebM 与 Matroska 共用 EBML 头，浏览器都按 webm 处理
            return "webm";
        }
        return null;
    }
}
