/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;

/**
 * 游戏上传/解压的各项上限。服务端是唯一权威，前端只通过
 * {@code GET /api/games/import/uploads/limits} 读取展示值。
 *
 * <p>默认值见 {@code application.yml} 的 {@code galworld.import.upload} 段，
 * 也可用同名环境变量覆盖。
 */
@Component
public class GameUploadLimits {

    private final long maxUploadBytes;
    private final long maxExtractedBytes;
    private final long maxFileBytes;
    private final int maxFiles;
    private final int maxDepth;
    private final int maxRatio;
    private final int chunkSize;
    private final int maxChunkBytes;
    private final long tempTtlSeconds;
    private final long diskReserveBytes;
    private final Charset zipCharset;

    public GameUploadLimits(
            @Value("${galworld.import.upload.max-upload-bytes:21474836480}") long maxUploadBytes,
            @Value("${galworld.import.upload.max-extracted-bytes:64424509440}") long maxExtractedBytes,
            @Value("${galworld.import.upload.max-file-bytes:8589934592}") long maxFileBytes,
            @Value("${galworld.import.upload.max-files:100000}") int maxFiles,
            @Value("${galworld.import.upload.max-depth:12}") int maxDepth,
            @Value("${galworld.import.upload.max-ratio:200}") int maxRatio,
            @Value("${galworld.import.upload.chunk-size:8388608}") int chunkSize,
            @Value("${galworld.import.upload.max-chunk-bytes:16777216}") int maxChunkBytes,
            @Value("${galworld.import.upload.temp-ttl:24h}") String tempTtl,
            @Value("${galworld.import.upload.disk-reserve-bytes:5368709120}") long diskReserveBytes,
            @Value("${galworld.import.upload.zip-charset:GBK}") String zipCharset) {
        this.maxUploadBytes = maxUploadBytes;
        this.maxExtractedBytes = maxExtractedBytes;
        this.maxFileBytes = maxFileBytes;
        this.maxFiles = maxFiles;
        this.maxDepth = maxDepth;
        this.maxRatio = maxRatio;
        this.chunkSize = chunkSize;
        this.maxChunkBytes = maxChunkBytes;
        this.tempTtlSeconds = parseTtl(tempTtl);
        this.diskReserveBytes = diskReserveBytes;
        this.zipCharset = Charset.forName(zipCharset == null || zipCharset.isBlank() ? "GBK" : zipCharset.trim());
    }

    /** 测试与默认装配用的标准取值 */
    public static GameUploadLimits defaults() {
        return new GameUploadLimits(21474836480L, 64424509440L, 8589934592L,
                100000, 12, 200, 8388608, 16777216, "24h", 5368709120L, "GBK");
    }

    /** Spring Boot 的 Duration 风格：24h / 30m / 3600s / 纯数字按秒 */
    static long parseTtl(String raw) {
        if (raw == null || raw.isBlank()) {
            return 24 * 3600;
        }
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (s.endsWith("ms")) return Math.max(1, Long.parseLong(s.substring(0, s.length() - 2)) / 1000);
            if (s.endsWith("s")) return Long.parseLong(s.substring(0, s.length() - 1));
            if (s.endsWith("m")) return Long.parseLong(s.substring(0, s.length() - 1)) * 60;
            if (s.endsWith("h")) return Long.parseLong(s.substring(0, s.length() - 1)) * 3600;
            if (s.endsWith("d")) return Long.parseLong(s.substring(0, s.length() - 1)) * 86400;
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 24 * 3600;
        }
    }

    public long maxUploadBytes() { return maxUploadBytes; }

    public long maxExtractedBytes() { return maxExtractedBytes; }

    public long maxFileBytes() { return maxFileBytes; }

    public int maxFiles() { return maxFiles; }

    public int maxDepth() { return maxDepth; }

    public int maxRatio() { return maxRatio; }

    public int chunkSize() { return chunkSize; }

    public int maxChunkBytes() { return maxChunkBytes; }

    public long tempTtlSeconds() { return tempTtlSeconds; }

    public long diskReserveBytes() { return diskReserveBytes; }

    public Charset zipCharset() { return zipCharset; }
}
