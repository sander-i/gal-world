/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.service.MediaService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 托管上传的封面 / 图标。 */
@RestController
public class MediaController {

    private final MediaService media;
    private final PathConfig paths;

    public MediaController(MediaService media, PathConfig paths) {
        this.media = media;
        this.paths = paths;
    }

    @GetMapping("/media/{gameId}/{name}")
    public ResponseEntity<Resource> get(@PathVariable String gameId, @PathVariable String name,
                                        @org.springframework.web.bind.annotation.RequestParam(
                                                name = "v", required = false) String version,
                                        jakarta.servlet.http.HttpServletRequest request) throws IOException {
        // 只允许简单文件名，挡住路径穿越
        if (!name.equals(Path.of(name).getFileName().toString()) || name.contains("..")) {
            return ResponseEntity.badRequest().build();
        }
        Path base = media.mediaDir(gameId).toAbsolutePath().normalize();
        // 媒体现在放在 games/<id>.meta/media，校验基准改为 games 根目录
        if (!base.startsWith(paths.gamesDir().toAbsolutePath().normalize())) {
            return ResponseEntity.badRequest().build();
        }
        Path file = base.resolve(name).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        MediaType mt = mediaTypeOf(file);
        boolean hasVersionParam = version != null && !version.isBlank();
        String etag = "\"" + Long.toHexString(file.toFile().lastModified()) + "-" + file.toFile().length() + "\"";
        // 带 ?v= 的请求可直接长缓存（版本号随内容变）；
        // 不带版本号的必须回源校验，否则覆盖上传后浏览器仍显示旧图
        String cacheControl = hasVersionParam
                ? "public, max-age=31536000, immutable"
                : "no-cache";
        return ResponseEntity.ok()
                .contentType(mt)
                .header("Cache-Control", cacheControl)
                .header("ETag", etag)
                .body(new FileSystemResource(file));
    }

    /**
     * 按文件头嗅探 Content-Type。
     *
     * 派生图命名为 cover-1.jpg.s640，没有可识别的扩展名，Files.probeContentType 会返回 null。
     * 原图与派生图共用同一套嗅探规则，不依赖文件名。
     */
    private MediaType mediaTypeOf(Path file) throws IOException {
        byte[] head = new byte[12];
        try (var in = Files.newInputStream(file)) {
            int n = in.readNBytes(head, 0, head.length);
            if (n >= 4 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) {
                return MediaType.IMAGE_JPEG;
            }
            if (n >= 4 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
                return MediaType.IMAGE_PNG;
            }
            if (n >= 4 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F') {
                return MediaType.IMAGE_GIF;
            }
            if (n >= 12 && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
                return MediaType.parseMediaType("image/webp");
            }
            if (n >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
                return MediaType.parseMediaType("video/mp4");
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
