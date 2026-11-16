/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.service.GameDownloadService;
import io.github.sander_i.service.SessionService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 游戏打包下载。接口分两步：
 *  1. POST 创建打包任务，轮询 GET 看进度；
 *  2. READY 后 GET .../file 下载 zip。
 */
@RestController
@RequestMapping("/api/games/{id}/download/packages")
public class GameDownloadController {

    private final GameDownloadService downloads;
    private final GameRepository repo;
    private final SessionService sessions;

    public GameDownloadController(GameDownloadService downloads, GameRepository repo,
                                  SessionService sessions) {
        this.downloads = downloads;
        this.repo = repo;
        this.sessions = sessions;
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable String id) {
        var game = requireGame(id);
        var st = sessions.status(id, game.getPort());
        if (st.state() == SessionService.State.RUNNING) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "SESSION_RUNNING",
                            "message", "游戏正在运行，请先关闭游戏再打包下载"));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(downloads.start(id));
    }

    @GetMapping("/estimate")
    public GameDownloadService.Estimate estimate(@PathVariable String id) throws IOException {
        requireGame(id);
        return downloads.estimate(id);
    }

    @GetMapping("/current")
    public ResponseEntity<GameDownloadService.JobView> current(@PathVariable String id) {
        requireGame(id);
        var job = downloads.current(id);
        return job == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(job);
    }

    @GetMapping("/{jobId}")
    public GameDownloadService.JobView status(@PathVariable String id, @PathVariable String jobId) {
        return downloads.view(id, jobId);
    }

    @GetMapping("/{jobId}/file")
    public ResponseEntity<Resource> file(@PathVariable String id, @PathVariable String jobId)
            throws IOException {
        Path archive = downloads.file(id, jobId);
        String name = downloads.view(id, jobId).fileName();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(name, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(archive))
                .body(new FileSystemResource(archive));
    }

    @DeleteMapping("/{jobId}")
    public Map<String, Object> delete(@PathVariable String id, @PathVariable String jobId) {
        downloads.delete(id, jobId);
        return Map.of("deleted", jobId);
    }

    private io.github.sander_i.domain.Game requireGame(String id) {
        return repo.findById(id)
                .orElseThrow(() -> new GameDownloadService.NotFound("没有这个游戏: " + id));
    }

    @ExceptionHandler(GameDownloadService.NotFound.class)
    public ResponseEntity<?> notFound(GameDownloadService.NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(GameDownloadService.Conflict.class)
    public ResponseEntity<?> conflict(GameDownloadService.Conflict e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<?> ioError(IOException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", String.valueOf(e.getMessage())));
    }
}
