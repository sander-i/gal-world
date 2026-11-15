/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.web;

import com.galworld.platform.OsKind;
import com.galworld.security.WriteSecurity;
import com.galworld.service.GameImportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 游戏接入。所有方法（含 GET）都显式校验写令牌：
 * 候选接口会暴露服务器目录结构，不能因为「读请求」就放行。
 */
@RestController
@RequestMapping("/api/games/import")
public class GameImportController {

    public record ImportRequest(String id, String name, String exe, String exeAlt,
                                Integer width, Integer height, String framerate,
                                String gameLang) {
    }

    private final GameImportService importService;
    private final WriteSecurity security;
    private final OsKind os;

    @Autowired
    public GameImportController(GameImportService importService, WriteSecurity security) {
        this(importService, security, OsKind.current());
    }

    /** 测试注入平台，避免 Windows 开发机上无法覆盖 202 分支 */
    GameImportController(GameImportService importService, WriteSecurity security, OsKind os) {
        this.importService = importService;
        this.security = security;
        this.os = os;
    }

    @GetMapping("/candidates")
    public GameImportService.Candidates candidates(HttpServletRequest req) {
        security.check(req);
        return importService.candidates();
    }

    @GetMapping("/slots")
    public GameImportService.Slots slots(HttpServletRequest req) {
        security.check(req);
        return importService.slots();
    }

    @PostMapping
    public ResponseEntity<?> start(@RequestBody ImportRequest body, HttpServletRequest req) {
        security.check(req);
        if (os != OsKind.LINUX) {
            // 平台守卫放在 controller：service 的单测可以在 Windows 开发机上跑
            throw new UnsupportedOperationException("当前平台不支持游戏接入（仅 Linux）");
        }
        GameImportService.StartResult result = importService.start(body.id(), body.name(),
                body.exe(), body.exeAlt(), body.width(), body.height(), body.framerate(),
                body.gameLang());
        // 后端是游戏 id 的唯一权威：前端成功页必须用这里返回的实际 id。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", result.runId());
        payload.put("state", "QUEUED");
        payload.put("gameId", result.gameId());
        payload.put("renamedFrom", result.renamedFrom());
        return ResponseEntity.status(202).body(payload);
    }

    @GetMapping("/runs/{runId}")
    public GameImportService.RunSnapshot snapshot(@PathVariable String runId,
                                                  HttpServletRequest req) {
        security.check(req);
        return importService.snapshot(runId);
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String runId, HttpServletRequest req) {
        security.check(req);
        var run = importService.find(runId);
        // 必须直接返回 SseEmitter（包 ResponseEntity 会被当成普通 body）
        SseEmitter emitter = new SseEmitter(0L);
        run.addListener(emitter);
        return emitter;
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String runId, HttpServletRequest req) {
        security.check(req);
        importService.cancel(runId);
        return ResponseEntity.ok(Map.of("runId", runId, "state", "CANCELLED"));
    }

    @PostMapping("/runs/{runId}/retry")
    public ResponseEntity<?> retry(@PathVariable String runId, HttpServletRequest req) {
        security.check(req);
        String next = importService.retry(runId);
        return ResponseEntity.status(202).body(Map.of("runId", next, "state", "QUEUED"));
    }

    @PostMapping("/runs/{runId}/rollback")
    public ResponseEntity<?> rollback(@PathVariable String runId, HttpServletRequest req) {
        security.check(req);
        importService.rollback(runId);
        return ResponseEntity.ok(Map.of("runId", runId, "state", "ROLLED_BACK"));
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

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        // run 不存在也走这里：用 message 区分，避免再定义异常类
        if (e.getMessage() != null && e.getMessage().startsWith("没有这个 run")) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        }
        return ResponseEntity.status(400).body(Map.of("message", e.getMessage()));
    }
}
