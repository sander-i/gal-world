/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.galworld.config.PathConfig;
import com.galworld.deploy.DeployRun;
import com.galworld.repo.GameRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 浏览器上传游戏：会话、分片落盘、状态查询、解压编排、取消与过期清理。
 *
 * <p>协议见 {@code GameUploadController}，上限见 {@link GameUploadLimits}。
 * 上传状态不落数据库：进程重启后按 upload.json + 磁盘上的分片长度恢复。
 */
@Service
public class GameUploadService {

    private static final Logger log = LoggerFactory.getLogger(GameUploadService.class);

    public static final String KIND_ARCHIVE = "ARCHIVE";
    public static final String KIND_FOLDER = "FOLDER";

    public static final String STATE_UPLOADING = "UPLOADING";
    public static final String STATE_COMPLETING = "COMPLETING";
    public static final String STATE_EXTRACTING = "EXTRACTING";
    public static final String STATE_READY = "READY";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_CANCELLED = "CANCELLED";

    static final String MANIFEST = "upload.json";
    private static final Pattern UPLOAD_ID = Pattern.compile("^up-\\d{8}-\\d{6}-[0-9a-f]{4}$");

    // ---------------- DTO ----------------

    public record FileSpec(String path, long size) {
    }

    public record CreateRequest(String kind, String name, String fileName, Long totalBytes,
                                List<FileSpec> files) {
    }

    public record CreatedFile(String fileId, String path, long size, long uploadedBytes) {
    }

    public record CreateResult(String uploadId, int chunkSize, String gameId,
                               List<CreatedFile> files) {
    }

    public record FileStatus(String fileId, String path, long size, long uploadedBytes,
                             String state) {
    }

    public record Status(String uploadId, String kind, String state, String gameId, String name,
                         long totalBytes, long uploadedBytes, List<FileStatus> files,
                         GameImportService.Candidate result, String error) {
    }

    public record ChunkResult(String fileId, long uploadedBytes, boolean done) {
    }

    public record CompleteResult(String uploadId, String runId, String state,
                                 GameImportService.Candidate result) {
    }

    public record CancelResult(String state, String message) {
    }

    public record Limits(long maxUploadBytes, long maxFileBytes, int maxFiles, int chunkSize,
                         int maxDepth) {
    }

    // ---------------- 异常 ----------------

    /** uploadId 不存在或已过期，controller 映射 404 */
    public static class NotFound extends IllegalArgumentException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** 超过体积/数量上限，controller 映射 413 */
    public static class TooLarge extends IllegalArgumentException {
        public TooLarge(String message) {
            super(message);
        }
    }

    /** 非 zip/7z 或加密包，controller 映射 415 */
    public static class UnsupportedArchive extends IllegalArgumentException {
        public UnsupportedArchive(String message) {
            super(message);
        }
    }

    /** 状态冲突，controller 映射 409；带实际偏移供前端重对齐 */
    public static class ChunkConflict extends IllegalStateException {
        private final long actualOffset;

        public ChunkConflict(String message) {
            this(message, -1);
        }

        public ChunkConflict(String message, long actualOffset) {
            super(message);
            this.actualOffset = actualOffset;
        }

        public long actualOffset() {
            return actualOffset;
        }
    }

    /** 任务被取消，解压循环用它中断 */
    public static class Cancelled extends RuntimeException {
        public Cancelled(String message) {
            super(message);
        }
    }

    // ---------------- 清单模型 ----------------

    /** 一个待上传文件；JSON 字段直接落 upload.json */
    public static class FileEntry {
        public String fileId;
        public String path;
        public long size;
        public String state = STATE_UPLOADING;

        public FileEntry() {
        }

        public FileEntry(String fileId, String path, long size) {
            this.fileId = fileId;
            this.path = path;
            this.size = size;
        }
    }

    /** 一次上传会话的完整清单 */
    public static class Session {
        public String uploadId;
        public String kind;
        public String name;
        public String gameId;
        public String fileName;
        public long totalBytes;
        public int chunkSize;
        public String createdAt = "";
        public String updatedAt = "";
        public String state = STATE_UPLOADING;
        public String error = "";
        public String runId = "";
        public List<FileEntry> files = new ArrayList<>();
        public GameImportService.Candidate result;
    }

    // ---------------- 依赖 ----------------

    private final PathConfig paths;
    private final GameRepository repo;
    private final GameExeScanner exeScanner;
    private final GameArchiveExtractor extractor;
    private final GameUploadLimits limits;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService queue;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    /** gameId -> uploadId，创建时预留，避免两个会话抢同一个目录名 */
    private final Map<String, String> reserved = new ConcurrentHashMap<>();
    private final Map<String, DeployRun> runs = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancels = new ConcurrentHashMap<>();
    private final Map<String, Object> gameLocks = new ConcurrentHashMap<>();

    @Autowired
    public GameUploadService(PathConfig paths, GameRepository repo, GameExeScanner exeScanner,
                             GameArchiveExtractor extractor, GameUploadLimits limits) {
        this(paths, repo, exeScanner, extractor, limits, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "game-upload");
            t.setDaemon(true);
            return t;
        }));
    }

    /** 测试注入同步执行器，避免后台任务与断言竞态 */
    GameUploadService(PathConfig paths, GameRepository repo, GameExeScanner exeScanner,
                      GameArchiveExtractor extractor, GameUploadLimits limits,
                      ExecutorService queue) {
        this.paths = paths;
        this.repo = repo;
        this.exeScanner = exeScanner;
        this.extractor = extractor;
        this.limits = limits;
        this.queue = queue;
    }

    @PostConstruct
    void init() {
        try {
            recover();
        } catch (IOException e) {
            log.warn("恢复上传会话失败: {}", e.toString());
        }
    }

    // ---------------- 创建 ----------------

    public CreateResult create(CreateRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体为空");
        }
        String kind = req.kind() == null ? "" : req.kind().trim().toUpperCase(Locale.ROOT);
        if (!KIND_ARCHIVE.equals(kind) && !KIND_FOLDER.equals(kind)) {
            throw new IllegalArgumentException("kind 只能是 ARCHIVE 或 FOLDER");
        }
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty() || name.length() > 120) {
            throw new IllegalArgumentException("显示名必填，且不超过 120 字");
        }
        long total = req.totalBytes() == null ? 0L : req.totalBytes();
        if (total <= 0) {
            throw new IllegalArgumentException("总大小必须大于 0");
        }
        if (total > limits.maxUploadBytes()) {
            throw new TooLarge("上传体积超过上限 " + limits.maxUploadBytes() + " 字节");
        }

        List<FileEntry> files = new ArrayList<>();
        String fileName = "";
        if (KIND_ARCHIVE.equals(kind)) {
            fileName = req.fileName() == null ? "" : req.fileName().trim();
            if (!GameArchiveExtractor.isArchiveName(fileName)) {
                throw new UnsupportedArchive("只支持 .zip / .7z 压缩包");
            }
            if (total > limits.maxFileBytes()) {
                throw new TooLarge("单文件超过 " + limits.maxFileBytes() + " 字节");
            }
            files.add(new FileEntry("f000001", fileName, total));
        } else {
            List<FileSpec> specs = req.files();
            if (specs == null || specs.isEmpty()) {
                throw new IllegalArgumentException("文件夹上传缺少文件清单");
            }
            if (specs.size() > limits.maxFiles()) {
                throw new TooLarge("文件数超过上限 " + limits.maxFiles());
            }
            Set<String> seen = new HashSet<>();
            long sum = 0;
            int i = 1;
            for (FileSpec spec : specs) {
                if (spec == null) {
                    throw new IllegalArgumentException("文件清单包含空项");
                }
                String rel = GameArchiveExtractor.validateRelative(spec.path(), limits.maxDepth());
                if (spec.size() < 0) {
                    throw new IllegalArgumentException("文件大小非法: " + rel);
                }
                if (spec.size() > limits.maxFileBytes()) {
                    throw new TooLarge("单文件超过 " + limits.maxFileBytes() + " 字节: " + rel);
                }
                if (!seen.add(rel)) {
                    throw new IllegalArgumentException("清单包含重复路径: " + rel);
                }
                sum += spec.size();
                files.add(new FileEntry(String.format("f%06d", i++), rel, spec.size()));
            }
            if (sum != total) {
                throw new IllegalArgumentException("文件总大小与 totalBytes 不一致");
            }
        }

        String uploadId = newUploadId();
        String gameId = GameIdNormalizer.normalizeUnique(name, p ->
                reserved.containsKey(p) || repo.existsById(p)
                        || Files.exists(paths.gameDir(p), LinkOption.NOFOLLOW_LINKS));
        if (reserved.putIfAbsent(gameId, uploadId) != null) {
            // 归一化已经避开占用，这里只是并发兜底
            throw new ChunkConflict("目录名已被占用: " + gameId);
        }

        Session s = new Session();
        s.uploadId = uploadId;
        s.kind = kind;
        s.name = name;
        s.gameId = gameId;
        s.fileName = fileName;
        s.totalBytes = total;
        s.chunkSize = limits.chunkSize();
        s.createdAt = now();
        s.updatedAt = s.createdAt;
        s.files = files;

        try {
            Files.createDirectories(tmpDir(uploadId).resolve("files"));
            save(s);
        } catch (IOException e) {
            reserved.remove(gameId, uploadId);
            throw new IllegalStateException("创建临时目录失败: " + e.getMessage(), e);
        }
        sessions.put(uploadId, s);

        List<CreatedFile> out = new ArrayList<>();
        for (FileEntry f : files) {
            out.add(new CreatedFile(f.fileId, f.path, f.size, 0));
        }
        return new CreateResult(uploadId, s.chunkSize, gameId, out);
    }

    // ---------------- 分片 ----------------

    public ChunkResult putChunk(String uploadId, String fileId, long offset, String sha256Hex,
                                byte[] body) {
        Session s = require(uploadId);
        synchronized (s) {
            if (!STATE_UPLOADING.equals(s.state)) {
                throw new ChunkConflict("会话不在上传状态: " + s.state);
            }
            if (fileId == null || fileId.isBlank()) {
                throw new IllegalArgumentException("缺少 fileId");
            }
            FileEntry f = findFile(s, fileId);
            if (body == null || body.length == 0) {
                throw new IllegalArgumentException("分片为空");
            }
            if (body.length > limits.maxChunkBytes()) {
                throw new TooLarge("分片超过 " + limits.maxChunkBytes() + " 字节");
            }
            Path part = partPath(s.uploadId, f.fileId);
            long actual = sizeOf(part);
            if (offset != actual) {
                throw new ChunkConflict("偏移不连续", actual);
            }
            if (offset + body.length > f.size) {
                throw new IllegalArgumentException("分片超出文件声明大小");
            }
            String digest = sha256(body);
            if (sha256Hex == null || !digest.equalsIgnoreCase(sha256Hex.trim())) {
                throw new IllegalArgumentException("分片 SHA-256 校验失败");
            }
            if (offset == 0 && KIND_ARCHIVE.equals(s.kind)) {
                String magic = GameArchiveExtractor.detectKind(body, Math.min(body.length, 6));
                boolean ext7z = f.path.toLowerCase(Locale.ROOT).endsWith(".7z");
                if (magic == null || ext7z != GameArchiveExtractor.KIND_7Z.equals(magic)) {
                    throw new UnsupportedArchive("文件内容与扩展名不匹配，只支持 zip / 7z");
                }
            }
            try {
                ensureDiskSpace(tmpDir(s.uploadId));
                Path chunkTmp = part.resolveSibling(f.fileId + ".chunk.tmp");
                Files.write(chunkTmp, body, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                try (OutputStream out = Files.newOutputStream(part,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    out.write(body);
                }
                Files.deleteIfExists(chunkTmp);
            } catch (IOException e) {
                throw new IllegalStateException("写入分片失败: " + e.getMessage(), e);
            }
            long written = actual + body.length;
            boolean done = written >= f.size;
            if (done) {
                f.state = "DONE";
                touch(s);
                saveQuietly(s);
            }
            return new ChunkResult(f.fileId, written, done);
        }
    }

    // ---------------- 查询 ----------------

    public Status status(String uploadId) {
        Session s = require(uploadId);
        synchronized (s) {
            long uploaded = 0;
            List<FileStatus> files = new ArrayList<>();
            Path assembled = assembledArchive(s);
            for (FileEntry f : s.files) {
                long got;
                if (STATE_READY.equals(s.state)) {
                    got = f.size;
                } else if (assembled != null && sizeOf(assembled) == f.size) {
                    // 上次 complete 已合并：分片不存在但数据仍在，续传不必重传
                    got = f.size;
                } else {
                    got = sizeOf(partPath(uploadId, f.fileId));
                }
                uploaded += got;
                files.add(new FileStatus(f.fileId, f.path, f.size, got, f.state));
            }
            return new Status(s.uploadId, s.kind, s.state, s.gameId, s.name, s.totalBytes,
                    uploaded, files, s.result, s.error);
        }
    }

    public Limits limits() {
        return new Limits(limits.maxUploadBytes(), limits.maxFileBytes(), limits.maxFiles(),
                limits.chunkSize(), limits.maxDepth());
    }

    /** SSE 入口；READY / CANCELLED 之后已经没有 run */
    public DeployRun run(String uploadId) {
        require(uploadId);
        DeployRun run = runs.get(uploadId);
        if (run == null) {
            throw new NotFound("该上传会话没有进行中的任务");
        }
        return run;
    }

    // ---------------- 完成 ----------------

    public CompleteResult complete(String uploadId) {
        Session s = require(uploadId);
        synchronized (s) {
            if (STATE_READY.equals(s.state)) {
                return new CompleteResult(uploadId, s.runId, STATE_READY, s.result);
            }
            if (STATE_COMPLETING.equals(s.state) || STATE_EXTRACTING.equals(s.state)) {
                return new CompleteResult(uploadId, s.runId, s.state, null);
            }
            if (STATE_CANCELLED.equals(s.state)) {
                throw new ChunkConflict("会话已取消");
            }
            for (FileEntry f : s.files) {
                Path assembled = assembledArchive(s);
                boolean ready = assembled != null && sizeOf(assembled) == f.size;
                if (!ready && sizeOf(partPath(uploadId, f.fileId)) != f.size) {
                    throw new ChunkConflict("上传未完成: " + f.path);
                }
            }
            s.state = STATE_COMPLETING;
            s.error = "";
            s.runId = "ug-" + OffsetDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-"
                    + UUID.randomUUID().toString().substring(0, 4);
            s.updatedAt = now();
            saveQuietly(s);

            DeployRun run = new DeployRun(s.runId, "game-upload");
            runs.put(uploadId, run);
            cancels.put(uploadId, new AtomicBoolean());
            queue.submit(() -> execute(s, run));
            return new CompleteResult(uploadId, s.runId, STATE_COMPLETING, null);
        }
    }

    private void execute(Session s, DeployRun run) {
        Path tmp = tmpDir(s.uploadId);
        AtomicBoolean cancelled = cancels.getOrDefault(s.uploadId, new AtomicBoolean());
        try {
            run.state(DeployRun.State.RUNNING);
            step(run, "assemble", "合并上传文件", "RUNNING");
            Path archive = null;
            if (KIND_ARCHIVE.equals(s.kind)) {
                FileEntry only = s.files.get(0);
                Path part = partPath(s.uploadId, only.fileId);
                String ext = only.path.toLowerCase(Locale.ROOT).endsWith(".7z") ? ".7z" : ".zip";
                archive = tmp.resolve("archive" + ext);
                if (Files.isRegularFile(part)) {
                    Files.move(part, archive, StandardCopyOption.REPLACE_EXISTING);
                    run.log("已合并上传文件: " + archive.getFileName());
                } else if (Files.isRegularFile(archive)) {
                    // 上次 complete 已合并、后续步骤失败：允许直接重试
                    run.log("沿用已合并的上传文件: " + archive.getFileName());
                } else {
                    throw new ChunkConflict("上传分片缺失，请重新上传");
                }
                String magic = GameArchiveExtractor.detectKind(archive);
                boolean ext7z = ".7z".equals(ext);
                if (magic == null || ext7z != GameArchiveExtractor.KIND_7Z.equals(magic)) {
                    throw new UnsupportedArchive("文件内容与扩展名不匹配");
                }
            } else {
                run.log("文件夹上传，跳过合并");
            }
            step(run, "assemble", "合并上传文件", "SUCCESS");

            step(run, "extract", "解压并整理目录", "RUNNING");
            setState(s, STATE_EXTRACTING, run);
            Path staged;
            if (KIND_ARCHIVE.equals(s.kind)) {
                Path extractDir = tmp.resolve("extract");
                GameArchiveExtractor.deleteRecursively(extractDir);
                extractor.extract(archive, extractDir, limits, run::log, cancelled::get);
                staged = GameArchiveExtractor.stripWrapper(extractDir, limits.maxDepth());
            } else {
                Path tree = tmp.resolve("tree");
                GameArchiveExtractor.deleteRecursively(tree);
                materializeTree(s, tree);
                validateTree(s, tree);
                staged = GameArchiveExtractor.stripWrapper(tree, limits.maxDepth());
            }
            if (cancelled.get()) {
                throw new Cancelled("已取消");
            }
            step(run, "extract", "解压并整理目录", "SUCCESS");

            step(run, "scan", "扫描可执行文件", "RUNNING");
            Path target = paths.gameDir(s.gameId);
            synchronized (lockFor(s.gameId)) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ChunkConflict("目标目录已存在: " + s.gameId);
                }
                if (repo.existsById(s.gameId)) {
                    throw new ChunkConflict("游戏已注册: " + s.gameId);
                }
                GameArchiveExtractor.moveInto(staged, target);
            }
            List<GameExeScanner.Exe> exes = exeScanner.scan(target);
            GameImportService.Candidate candidate = new GameImportService.Candidate(
                    s.gameId, s.gameId, dirSize(target),
                    Files.getLastModifiedTime(target).toInstant().toString(), exes);

            synchronized (s) {
                s.result = candidate;
                s.state = STATE_READY;
                s.error = "";
                touch(s);
                save(s);
            }
            cleanupUploadedFiles(s);
            step(run, "scan", "扫描可执行文件", "SUCCESS");
            run.log("上传完成: games/" + s.gameId + "，共 " + exes.size() + " 个 exe");
            run.state(DeployRun.State.SUCCESS);
        } catch (Cancelled e) {
            run.log("已取消");
            synchronized (s) {
                s.state = STATE_CANCELLED;
                s.error = "已取消";
                touch(s);
            }
            cleanupTempQuietly(s.uploadId);
            reserved.remove(s.gameId, s.uploadId);
            sessions.remove(s.uploadId);
            run.state(DeployRun.State.CANCELLED);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            run.log("ERROR: " + message);
            run.error(message);
            synchronized (s) {
                // 解压失败保留已上传分片，允许重新 complete；临时目录里的半成品由下次重试清理
                s.state = STATE_FAILED;
                s.error = message;
                touch(s);
                saveQuietly(s);
            }
            if (e instanceof ChunkConflict) {
                // 目标冲突不是数据问题，临时目录可保留给用户改名后重试
                log.warn("上传完成失败 {}: {}", s.uploadId, message);
            } else {
                // 非预期失败：服务端日志留完整堆栈，方便对照前端 run.log
                log.warn("上传完成失败 {}: {}", s.uploadId, message, e);
            }
            run.state(DeployRun.State.FAILED);
        } finally {
            cancels.remove(s.uploadId);
        }
    }

    // ---------------- 取消 ----------------

    public CancelResult cancel(String uploadId) {
        Session s = require(uploadId);
        synchronized (s) {
            if (STATE_CANCELLED.equals(s.state)) {
                return new CancelResult(STATE_CANCELLED, "已取消");
            }
            if (STATE_READY.equals(s.state)) {
                // 已整理进 games/ 的目录保留，只清临时残留
                cleanupTempQuietly(uploadId);
                sessions.remove(uploadId);
                return new CancelResult(STATE_READY, "会话已关闭，已上传目录保留");
            }
            AtomicBoolean flag = cancels.get(uploadId);
            if (flag != null && (STATE_COMPLETING.equals(s.state) || STATE_EXTRACTING.equals(s.state))) {
                flag.set(true);
                return new CancelResult(s.state, "正在终止任务");
            }
            cleanupTempQuietly(uploadId);
            reserved.remove(s.gameId, uploadId);
            sessions.remove(uploadId);
            return new CancelResult(STATE_CANCELLED, "已取消并清理临时文件");
        }
    }

    // ---------------- 文件夹 ----------------

    /** 把各分片按清单路径复制到 tree/ 下；复制而非移动，失败后仍可重试 */
    private void materializeTree(Session s, Path tree) throws IOException {
        for (FileEntry f : s.files) {
            Path out = tree.resolve(f.path).normalize();
            if (!out.startsWith(tree)) {
                throw new IllegalArgumentException("路径越界: " + f.path);
            }
            Files.createDirectories(out.getParent());
            try {
                if (f.size == 0) {
                    // 空文件浏览器不会发分片，服务端也就没有 .part：按清单补空文件
                    Files.write(out, new byte[0], StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                } else {
                    Files.copy(partPath(s.uploadId, f.fileId), out,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new IOException("整理上传文件失败：" + f.path + "（" + e + "）", e);
            }
        }
    }

    /** 清单与实际目录必须一致；不跟随符号链接 */
    private void validateTree(Session s, Path tree) throws IOException {
        if (!Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("上传目录不存在");
        }
        long[] total = {0};
        int[] count = {0};
        try (Stream<Path> walk = Files.walk(tree)) {
            for (Path p : walk.toList()) {
                if (Files.isSymbolicLink(p)) {
                    throw new IllegalArgumentException("上传目录含符号链接，已拒绝");
                }
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    int depth = tree.relativize(p).getNameCount();
                    if (depth > limits.maxDepth()) {
                        throw new IllegalArgumentException("目录层级超过 " + limits.maxDepth());
                    }
                    continue;
                }
                count[0]++;
                total[0] += Files.size(p);
                if (count[0] > limits.maxFiles()) {
                    throw new TooLarge("文件数超过上限 " + limits.maxFiles());
                }
                if (total[0] > limits.maxExtractedBytes()) {
                    throw new TooLarge("总体积超过上限 " + limits.maxExtractedBytes() + " 字节");
                }
            }
        }
        if (count[0] != s.files.size() || total[0] != s.totalBytes) {
            throw new IllegalArgumentException("上传目录与清单不一致：清单 " + s.files.size()
                    + " 个文件 / " + s.totalBytes + " 字节，实际 " + count[0] + " 个 / " + total[0] + " 字节");
        }
    }

    // ---------------- 清理 ----------------

    private void cleanupUploadedFiles(Session s) {
        try {
            Path tmp = tmpDir(s.uploadId);
            GameArchiveExtractor.deleteRecursively(tmp.resolve("extract"));
            GameArchiveExtractor.deleteRecursively(tmp.resolve("tree"));
            GameArchiveExtractor.deleteRecursively(tmp.resolve("files"));
            try (Stream<Path> list = Files.list(tmp)) {
                for (Path p : list.toList()) {
                    if (!MANIFEST.equals(p.getFileName().toString())) {
                        GameArchiveExtractor.deleteRecursively(p);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("清理上传临时文件失败 {}: {}", s.uploadId, e.toString());
        }
    }

    private void cleanupTempQuietly(String uploadId) {
        try {
            GameArchiveExtractor.deleteRecursively(tmpDir(uploadId));
        } catch (IOException e) {
            log.warn("删除上传临时目录失败 {}: {}", uploadId, e.toString());
        }
    }

    /** 每小时清理过期会话（TTL 默认 24h） */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    public void purgeExpired() {
        Instant cutoff = Instant.now().minusSeconds(limits.tempTtlSeconds());
        for (Session s : new ArrayList<>(sessions.values())) {
            if (!expired(s, cutoff)) {
                continue;
            }
            if (STATE_COMPLETING.equals(s.state) || STATE_EXTRACTING.equals(s.state)) {
                continue;
            }
            cleanupTempQuietly(s.uploadId);
            sessions.remove(s.uploadId);
            reserved.remove(s.gameId, s.uploadId);
            runs.remove(s.uploadId);
        }
    }

    private static boolean expired(Session s, Instant cutoff) {
        try {
            return OffsetDateTime.parse(s.updatedAt).toInstant().isBefore(cutoff);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 启动恢复：加载未过期会话；中断的完成任务标记失败 */
    void recover() throws IOException {
        Path base = paths.importTmpDir();
        if (!Files.isDirectory(base)) {
            return;
        }
        List<Path> dirs;
        try (Stream<Path> list = Files.list(base)) {
            dirs = list.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList();
        }
        Instant cutoff = Instant.now().minusSeconds(limits.tempTtlSeconds());
        for (Path dir : dirs) {
            Path manifest = dir.resolve(MANIFEST);
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.getLastModifiedTime(dir).toInstant().isBefore(cutoff)) {
                    GameArchiveExtractor.deleteRecursively(dir);
                }
                continue;
            }
            Session s;
            try {
                s = mapper.readValue(manifest.toFile(), Session.class);
            } catch (IOException e) {
                log.warn("上传清单损坏，删除 {}: {}", dir, e.toString());
                GameArchiveExtractor.deleteRecursively(dir);
                continue;
            }
            if (s.uploadId == null || s.gameId == null || expired(s, cutoff)) {
                GameArchiveExtractor.deleteRecursively(dir);
                continue;
            }
            if (STATE_COMPLETING.equals(s.state) || STATE_EXTRACTING.equals(s.state)) {
                s.state = STATE_FAILED;
                s.error = "服务重启，任务已中断，请重新完成上传";
                touch(s);
                save(s);
            }
            if (STATE_CANCELLED.equals(s.state)) {
                continue;
            }
            sessions.put(s.uploadId, s);
            reserved.putIfAbsent(s.gameId, s.uploadId);
        }
    }

    // ---------------- 工具 ----------------

    private Session require(String uploadId) {
        if (uploadId == null || !UPLOAD_ID.matcher(uploadId).matches()) {
            throw new NotFound("没有这个上传会话");
        }
        Session cached = sessions.get(uploadId);
        if (cached != null) {
            return cached;
        }
        Path manifest = tmpDir(uploadId).resolve(MANIFEST);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new NotFound("没有这个上传会话: " + uploadId);
        }
        try {
            Session s = mapper.readValue(manifest.toFile(), Session.class);
            sessions.put(uploadId, s);
            if (!STATE_CANCELLED.equals(s.state)) {
                reserved.putIfAbsent(s.gameId, uploadId);
            }
            return s;
        } catch (IOException e) {
            throw new NotFound("没有这个上传会话: " + uploadId);
        }
    }

    private static FileEntry findFile(Session s, String fileId) {
        for (FileEntry f : s.files) {
            if (f.fileId.equals(fileId)) {
                return f;
            }
        }
        throw new NotFound("没有这个文件分片: " + fileId);
    }

    private Path tmpDir(String uploadId) {
        return paths.importTmpDir().resolve(uploadId);
    }

    private Path partPath(String uploadId, String fileId) {
        return tmpDir(uploadId).resolve("files").resolve(fileId + ".part");
    }

    /** 压缩包会话在 assemble 步骤合并出的正式文件；尚未合并时返回 null */
    private Path assembledArchive(Session s) {
        if (!KIND_ARCHIVE.equals(s.kind) || s.files.isEmpty()) {
            return null;
        }
        String source = s.files.get(0).path.toLowerCase(Locale.ROOT);
        Path p = tmpDir(s.uploadId).resolve(source.endsWith(".7z") ? "archive.7z" : "archive.zip");
        return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) ? p : null;
    }

    private void setState(Session s, String state, DeployRun run) {
        synchronized (s) {
            s.state = state;
            touch(s);
            saveQuietly(s);
        }
    }

    private static void touch(Session s) {
        s.updatedAt = now();
    }

    private static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String newUploadId() {
        return "up-" + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 4);
    }

    private void save(Session s) throws IOException {
        Path dir = tmpDir(s.uploadId);
        Files.createDirectories(dir);
        Path tmp = dir.resolve(MANIFEST + ".tmp");
        mapper.writeValue(tmp.toFile(), s);
        try {
            Files.move(tmp, dir.resolve(MANIFEST), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, dir.resolve(MANIFEST), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void saveQuietly(Session s) {
        try {
            save(s);
        } catch (IOException e) {
            log.warn("写入上传清单失败 {}: {}", s.uploadId, e.toString());
        }
    }

    private void ensureDiskSpace(Path dir) throws IOException {
        FileStore store = Files.getFileStore(dir);
        if (store.getUsableSpace() < limits.diskReserveBytes()) {
            throw new TooLarge("服务器磁盘剩余空间不足");
        }
    }

    private Object lockFor(String gameId) {
        return gameLocks.computeIfAbsent(gameId, k -> new Object());
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder b = new StringBuilder(d.length * 2);
            for (byte x : d) {
                b.append(String.format("%02x", x));
            }
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 SHA-256", e);
        }
    }

    /** 有界目录体积，与 GameImportService 的候选列表口径一致 */
    private static long dirSize(Path dir) {
        long total = 0;
        try (Stream<Path> walk = Files.walk(dir, 8)) {
            List<Path> files = walk.filter(Files::isRegularFile).limit(50_001).toList();
            for (Path p : files) {
                total += sizeOf(p);
            }
        } catch (IOException ignore) {
            // 目录不可读：返回已统计部分
        }
        return total;
    }

    private static void step(DeployRun run, String id, String title, String state) {
        run.event("step", stepJson(id, title, state));
    }

    private static String stepJson(String id, String title, String state) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("state", state);
        m.put("title", title);
        try {
            return new ObjectMapper().writeValueAsString(m);
        } catch (IOException e) {
            return "{\"id\":\"" + id + "\",\"state\":\"" + state + "\",\"title\":\"" + title + "\"}";
        }
    }
}
