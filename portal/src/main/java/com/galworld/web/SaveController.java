/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.web;

import com.galworld.repo.GameRepository;
import com.galworld.service.SaveService;
import com.galworld.service.SessionService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/games/{id}/saves")
public class SaveController {

    private final SaveService saves;
    private final SessionService sessions;
    private final GameRepository repo;

    public SaveController(SaveService saves, SessionService sessions, GameRepository repo) {
        this.saves = saves;
        this.sessions = sessions;
        this.repo = repo;
    }

    public record BackupRequest(String label, String description) {
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable String id) throws IOException {
        requireGame(id);
        List<String> dirs = saveDirLabels(id);
        return Map.of(
                "saveDirs", dirs,
                "saveDirNames", saves.saveDirNames(id),
                "backups", saves.list(id));
    }

    /** 浏览游戏目录内容，供「手动选择存档目录」用 */
    @GetMapping("/files")
    public Map<String, Object> files(@PathVariable String id,
                                     @RequestParam(defaultValue = "") String path) throws IOException {
        requireGame(id);
        return saves.listFiles(id, path);
    }

    /** 手动指定存档目录（相对游戏目录）；传空数组则回到默认约定 */
    @PutMapping("/dirs")
    public Map<String, Object> setDirs(@PathVariable String id,
                                       @RequestBody(required = false) DirsRequest req) throws IOException {
        requireGame(id);
        List<String> dirs = req == null || req.dirs() == null ? List.of() : req.dirs();
        return Map.of("saveDirNames", saves.setSaveDirs(id, dirs),
                "saveDirs", saveDirLabels(id));
    }

    public record DirsRequest(List<String> dirs) {
    }

    @PostMapping("/backup")
    public Map<String, Object> backup(@PathVariable String id,
                                      @RequestBody(required = false) BackupRequest req) throws IOException {
        requireGame(id);
        String label = req == null ? "" : req.label();
        String description = req == null ? "" : req.description();
        Path archive = saves.backup(id, label, description, SaveService.KIND_MANUAL);
        return Map.of(
                "created", archive.getFileName().toString(),
                "backups", saves.list(id));
    }

    /** 修改备份的标签 / 描述 */
    @PutMapping("/{name}")
    public Map<String, Object> update(@PathVariable String id, @PathVariable String name,
                                      @RequestBody(required = false) BackupRequest req) throws IOException {
        requireGame(id);
        String label = req == null ? "" : req.label();
        String description = req == null ? "" : req.description();
        SaveService.SaveArchive updated = saves.updateMeta(id, name, label, description);
        return Map.of("updated", updated.name(), "backups", saves.list(id));
    }

    /** 切换存档：把指定备份解压回游戏目录 */
    @PostMapping("/{name}/switch")
    public ResponseEntity<?> switchTo(@PathVariable String id, @PathVariable String name) throws IOException {
        return restore(id, name);
    }

    @PostMapping("/{name}/restore")
    public ResponseEntity<?> restore(@PathVariable String id, @PathVariable String name) throws IOException {
        requireGame(id);
        // 游戏在跑时恢复会与写盘冲突，直接拒绝
        var st = sessions.status(id, portOf(id));
        if (st.state() == SessionService.State.RUNNING) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "SESSION_RUNNING",
                            "message", "游戏正在运行，请先在门户停止会话再切换存档"));
        }
        SaveService.SaveArchive restored = saves.restore(id, name);
        return ResponseEntity.ok(Map.of("restored", restored.name(), "backups", saves.list(id)));
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String id, @PathVariable String name) throws IOException {
        requireGame(id);
        saves.delete(id, name);
        return Map.of("backups", saves.list(id));
    }

    /** 下载某份备份（tar.gz）。文件名用备份名，浏览器另存为 */
    @GetMapping("/{name}/file")
    public ResponseEntity<FileSystemResource> file(@PathVariable String id,
                                                   @PathVariable String name) throws IOException {
        requireGame(id);
        Path archive = saves.archiveOf(id, name);
        if (!Files.isRegularFile(archive)) {
            throw new IllegalArgumentException("备份不存在: " + name);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(archive.getFileName().toString(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(archive))
                .body(new FileSystemResource(archive));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> ioError(IOException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "IO_ERROR", "message", String.valueOf(e.getMessage())));
    }

    /** 兜底：把异常信息带回前端，避免只有空白 500 */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> unexpected(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "ERROR",
                        "message", e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    private void requireGame(String id) {
        if (!repo.existsById(id)) {
            throw new IllegalArgumentException("没有这个游戏: " + id);
        }
    }

    /**
     * 存档目录的展示名：相对游戏目录的路径。
     * 嵌套目录（如 {@code SteamEmu/Saves}）只取末段会丢失区分度，这里保留完整相对路径。
     */
    private List<String> saveDirLabels(String id) throws IOException {
        Path gameDir = saves.gameDirOf(id).toAbsolutePath().normalize();
        return saves.saveDirs(id).stream()
                .map(p -> gameDir.relativize(p.toAbsolutePath().normalize())
                        .toString().replace('\\', '/'))
                .toList();
    }

    private int portOf(String id) {
        return repo.findById(id).map(g -> g.getPort()).orElse(0);
    }
}
