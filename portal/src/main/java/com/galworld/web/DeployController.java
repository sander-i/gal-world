/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.web;

import com.galworld.deploy.DeployRun;
import com.galworld.deploy.DeploySecurity;
import com.galworld.deploy.DeployService;
import com.galworld.platform.OsKind;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 部署接口（docs/ONE-CLICK-DEPLOY.md §7.1）。
 *
 * 所有接口都要过 {@link DeploySecurity}：令牌 + 网段。
 * 前端只能传步骤 id，命令文本永远来自后端的步骤表。
 */
@RestController
@RequestMapping("/api/deploy")
public class DeployController {

    private final DeployService deploy;
    private final DeploySecurity security;

    public DeployController(DeployService deploy, DeploySecurity security) {
        this.deploy = deploy;
        this.security = security;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(HttpServletRequest req) {
        security.check(req);
        return ResponseEntity.ok(Map.of(
                "os", OsKind.current().name(),
                "allowSudoPassword", deploy.allowSudoPassword(),
                "elevation", deploy.elevation(),
                "steps", deploy.status()));
    }

    @PostMapping("/steps/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable String id, HttpServletRequest req) {
        security.check(req);
        try {
            return ResponseEntity.ok(Map.of("stepId", id, "confirmToken", deploy.confirm(id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/steps/{id}/run")
    public ResponseEntity<?> run(@PathVariable String id,
                                 @RequestBody(required = false) Map<String, String> body,
                                 HttpServletRequest req) {
        security.check(req);
        String token = body == null ? null : body.get("confirmToken");
        String sudo = body == null ? null : body.get("sudoPassword");
        // 密码只以 char[] 形式交给执行线程，用完清零；不落盘、不进日志
        char[] sudoPassword = (sudo == null || sudo.isEmpty()) ? null : sudo.toCharArray();
        try {
            return ResponseEntity.ok(Map.of("runId", deploy.run(id, token, sudoPassword)));
        } catch (SecurityException e) {
            wipe(sudoPassword);
            return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            wipe(sudoPassword);
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            wipe(sudoPassword);
            String msg = String.valueOf(e.getMessage());
            int code = msg.contains("确认令牌") ? 403 : msg.contains("已有部署任务") ? 429 : 409;
            return ResponseEntity.status(code).body(Map.of("message", msg));
        }
    }

    /** 异常分支里立刻清零密码；成功分支由执行线程负责清零 */
    private static void wipe(char[] p) {
        if (p != null) {
            java.util.Arrays.fill(p, '\0');
        }
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String runId, HttpServletRequest req) {
        security.check(req);
        DeployRun run = deploy.find(runId);
        if (run == null) {
            throw new IllegalArgumentException("没有这个 run: " + runId);
        }
        // 必须直接返回 SseEmitter（不能包 ResponseEntity，否则 Spring 会当普通 body 处理）
        SseEmitter emitter = new SseEmitter(0L);
        run.addListener(emitter);
        return emitter;
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String runId, HttpServletRequest req) {
        security.check(req);
        try {
            deploy.cancel(runId);
            return ResponseEntity.ok(Map.of("runId", runId, "state", "CANCELLED"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(SecurityException.class)
    public ResponseEntity<?> denied(SecurityException e) {
        return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> notFound(IllegalArgumentException e) {
        return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
    }
}
