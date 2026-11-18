/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.i18n.I18n;
import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.service.SaveService;
import io.github.sander_i.service.SessionService;
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
import java.util.LinkedHashMap;
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
        return state(id, Map.of());
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
        return state(id, Map.of("created", archive.getFileName().toString()));
    }

    /** 修改备份的标签 / 描述 */
    @PutMapping("/{name}")
    public Map<String, Object> update(@PathVariable String id, @PathVariable String name,
                                      @RequestBody(required = false) BackupRequest req) throws IOException {
        requireGame(id);
        String label = req == null ? "" : req.label();
        String description = req == null ? "" : req.description();
        SaveService.SaveArchive updated = saves.updateMeta(id, name, label, description);
        return state(id, Map.of("updated", updated.name()));
    }

    /** 切换存档：把指定备份解压回游戏目录 */
    @PostMapping("/{name}/switch")
    public ResponseEntity<?> switchTo(@PathVariable String id, @PathVariable String name,
                                      @RequestParam(defaultValue = "true") boolean snapshot)
            throws IOException {
        return doRestore(id, name, snapshot);
    }

    @PostMapping("/{name}/restore")
    public ResponseEntity<?> restore(@PathVariable String id, @PathVariable String name,
                                     @RequestParam(defaultValue = "true") boolean snapshot)
            throws IOException {
        return doRestore(id, name, snapshot);
    }

    private ResponseEntity<?> doRestore(String id, String name, boolean snapshot) throws IOException {
        requireGame(id);
        // 游戏在跑时恢复会与写盘冲突，直接拒绝
        var st = sessions.status(id, portOf(id));
        if (st.state() == SessionService.State.RUNNING) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "SESSION_RUNNING",
                            "message", I18n.t("游戏正在运行，请先在门户停止会话再切换存档")));
        }
        SaveService.SaveArchive restored = saves.restore(id, name, snapshot);
        return ResponseEntity.ok(state(id, Map.of("restored", restored.name())));
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String id, @PathVariable String name) throws IOException {
        requireGame(id);
        saves.delete(id, name);
        return state(id, Map.of());
    }

    /**
     * 存档面板的完整状态。
     *
     * 所有写操作都返回它：前端拿到响应会整体替换本地状态，只回 {@code backups}
     * 会让 {@code saveDirs} 变成 undefined，模板读 {@code .length} 时抛错、整页白屏。
     */
    private Map<String, Object> state(String id, Map<String, Object> extra) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>(extra);
        out.put("saveDirs", saveDirLabels(id));
        out.put("saveDirNames", saves.saveDirNames(id));
        out.put("current", saves.currentArchive(id));
        SaveService.SaveArchive original = saves.originalArchive(id);
        out.put("original", original == null ? "" : original.name());
        out.put("backups", saves.list(id));
        return out;
    }

    /** 从原始存档（导入时的初始状态）恢复；存档列表为空时页面用它兜底 */
    @PostMapping("/restore-original")
    public ResponseEntity<?> restoreOriginal(@PathVariable String id) throws IOException {
        requireGame(id);
        SaveService.SaveArchive original = saves.originalArchive(id);
        if (original == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", "NO_ORIGINAL",
                            "message", I18n.t("没有原始存档备份，无法恢复初始状态")));
        }
        return doRestore(id, original.name(), false);
    }

    /** 下载某份备份（tar.gz）。文件名用备份名，浏览器另存为 */
    @GetMapping("/{name}/file")
    public ResponseEntity<FileSystemResource> file(@PathVariable String id,
                                                   @PathVariable String name) throws IOException {
        requireGame(id);
        Path archive = saves.archiveOf(id, name);
        if (!Files.isRegularFile(archive)) {
            throw new IllegalArgumentException(I18n.t("备份不存在: {0}", name));
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
            throw new IllegalArgumentException(I18n.t("没有这个游戏: {0}", id));
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
