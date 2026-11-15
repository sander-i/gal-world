/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.platform.OsKind;
import io.github.sander_i.security.WriteSecurity;
import io.github.sander_i.service.GameUploadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器上传游戏。所有方法（含 GET、DELETE）都显式校验写令牌：
 * 状态接口会暴露服务器临时目录与游戏目录名，不能因为「读请求」就放行。
 */
@RestController
@RequestMapping("/api/games/import/uploads")
public class GameUploadController {

    private final GameUploadService service;
    private final WriteSecurity security;
    private final OsKind os;

    @Autowired
    public GameUploadController(GameUploadService service, WriteSecurity security) {
        this(service, security, OsKind.current());
    }

    /** 测试注入平台，避免 Windows 开发机无法覆盖 501 分支 */
    GameUploadController(GameUploadService service, WriteSecurity security, OsKind os) {
        this.service = service;
        this.security = security;
        this.os = os;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody GameUploadService.CreateRequest body,
                                    HttpServletRequest req) {
        security.check(req);
        requireLinux();
        return ResponseEntity.status(201).body(service.create(body));
    }

    @GetMapping("/limits")
    public GameUploadService.Limits limits(HttpServletRequest req) {
        security.check(req);
        return service.limits();
    }

    @GetMapping("/{uploadId}")
    public GameUploadService.Status status(@PathVariable String uploadId,
                                           HttpServletRequest req) {
        security.check(req);
        return service.status(uploadId);
    }

    @PutMapping(value = "/{uploadId}/files/{fileId}",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public GameUploadService.ChunkResult chunk(@PathVariable String uploadId,
                                               @PathVariable String fileId,
                                               @RequestParam long offset,
                                               @RequestHeader(value = "X-Chunk-SHA256", required = false)
                                               String sha256,
                                               @RequestBody(required = false) byte[] body,
                                               HttpServletRequest req) {
        security.check(req);
        requireLinux();
        return service.putChunk(uploadId, fileId, offset, sha256, body);
    }

    @PostMapping("/{uploadId}/complete")
    public ResponseEntity<?> complete(@PathVariable String uploadId, HttpServletRequest req) {
        security.check(req);
        requireLinux();
        return ResponseEntity.status(202).body(service.complete(uploadId));
    }

    @DeleteMapping("/{uploadId}")
    public GameUploadService.CancelResult cancel(@PathVariable String uploadId,
                                                 HttpServletRequest req) {
        security.check(req);
        requireLinux();
        return service.cancel(uploadId);
    }

    @GetMapping(value = "/{uploadId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String uploadId, HttpServletRequest req) {
        security.check(req);
        var run = service.run(uploadId);
        // 必须直接返回 SseEmitter（包 ResponseEntity 会被当成普通 body）
        SseEmitter emitter = new SseEmitter(0L);
        run.addListener(emitter);
        return emitter;
    }

    private void requireLinux() {
        if (os != OsKind.LINUX) {
            throw new UnsupportedOperationException("当前平台不支持上传接入（仅 Linux）");
        }
    }

    // ---------------- 异常映射 ----------------

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<?> denied(SecurityException e) {
        return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<?> unsupported(UnsupportedOperationException e) {
        return ResponseEntity.status(501).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(GameUploadService.NotFound.class)
    public ResponseEntity<?> notFound(GameUploadService.NotFound e) {
        return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(GameUploadService.UnsupportedArchive.class)
    public ResponseEntity<?> unsupportedArchive(GameUploadService.UnsupportedArchive e) {
        return ResponseEntity.status(415).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(GameUploadService.TooLarge.class)
    public ResponseEntity<?> tooLarge(GameUploadService.TooLarge e) {
        return ResponseEntity.status(413).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(GameUploadService.ChunkConflict.class)
    public ResponseEntity<?> conflict(GameUploadService.ChunkConflict e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", e.getMessage());
        if (e.actualOffset() >= 0) {
            body.put("actualOffset", e.actualOffset());
        }
        return ResponseEntity.status(409).body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(400).body(Map.of("message", e.getMessage()));
    }
}
