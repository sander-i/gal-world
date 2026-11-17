/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.i18n.I18n;
import io.github.sander_i.platform.OsKind;
import io.github.sander_i.update.UpdateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/update")
public class UpdateController {

    public record ApplyRequest(String version, String confirm, boolean override) {
        public ApplyRequest(String version, String confirm) {
            this(version, confirm, false);
        }
    }

    private final UpdateService service;
    private final OsKind os;

    /**
     * Spring 要注入这个构造器。类里同时还有一个包可见的测试构造器，
     * 不显式标注 @Autowired 时 Spring 6.1 会按「无默认构造器」处理并启动失败。
     */
    @Autowired
    public UpdateController(UpdateService service) {
        this(service, OsKind.current());
    }

    UpdateController(UpdateService service, OsKind os) {
        this.service = service;
        this.os = os;
    }

    @GetMapping("/check")
    public ResponseEntity<?> check(
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        try {
            var r = service.check(refresh);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("currentVersion", r.currentVersion());
            out.put("latestVersion", r.latestVersion());
            out.put("releaseName", r.releaseName());
            out.put("hasUpdate", r.hasUpdate());
            out.put("message", r.message());
            out.put("releaseUrl", r.releaseUrl());
            out.put("publishedAt", r.publishedAt());
            out.put("overrideEnabled", service.overrideEnabled());
            return ResponseEntity.ok(out);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502)
                    .body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestBody ApplyRequest body) {
        if (os == OsKind.OTHER) {
            throw new UnsupportedOperationException(I18n.t("当前平台不支持远程更新: {0}", os));
        }
        String jobId = service.apply(body.version(), body.confirm(), body.override());
        return ResponseEntity.status(202).body(Map.of(
                "jobId", jobId, "phase", "queued"));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return service.status();
    }

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
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
