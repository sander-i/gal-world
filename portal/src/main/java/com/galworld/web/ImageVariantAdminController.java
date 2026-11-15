/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.web;

import com.galworld.config.PathConfig;
import com.galworld.service.ImageVariantService;
import com.galworld.service.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 派生图清理接口：只在「图片预加载优化」关闭后可用。
 *
 * 关闭开关后不再生成新派生图，但历史文件仍占磁盘；这里列出已有文件路径并支持一次性清理。
 * 删除范围严格限定为 {@code games/<id>.meta/media/} 下文件名匹配 {@code .s640/.s1280} 的文件，
 * 原图永远不动。
 */
@RestController
@RequestMapping("/api/settings/image-variants")
public class ImageVariantAdminController {

    private static final String HINT = "请先关闭「图片预加载优化」再清理派生图";

    private final ImageVariantService variants;
    private final SettingsService settings;
    private final PathConfig paths;

    public ImageVariantAdminController(ImageVariantService variants, SettingsService settings,
                                       PathConfig paths) {
        this.variants = variants;
        this.settings = settings;
        this.paths = paths;
    }

    /** 列出已生成的派生图 */
    @GetMapping
    public ResponseEntity<?> list() {
        if (settings.imageVariantEnabled()) {
            return ResponseEntity.badRequest().body(Map.of("message", HINT));
        }
        return ResponseEntity.ok(variants.scan(paths.gamesDir()));
    }

    /** 清理全部派生图，原图不受影响 */
    @DeleteMapping
    public ResponseEntity<?> clear() {
        if (settings.imageVariantEnabled()) {
            return ResponseEntity.badRequest().body(Map.of("message", HINT));
        }
        return ResponseEntity.ok(variants.clear(paths.gamesDir()));
    }
}
