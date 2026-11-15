/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.config.PathConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 游戏文件打包下载。
 *
 * 分两步：先异步打包成 zip（可轮询进度），完成后浏览器再发起真正的下载。
 * 直接边打包边流式下载虽然少一步，但打包中途失败时浏览器已经收到 200，
 * 用户只能拿到一个坏包；先落盘再下载可以保证下载到的包是完整的。
 *
 * 打包内容 = 游戏目录里用户上传的文件。门户自己生成的运行时文件
 * （{@code game.env}、{@code wineprefix/}、恢复残留）不纳入，
 * 否则一个 20 GB 的游戏会因为 wine prefix 变成 40 GB，且换机器后无意义。
 */
@Service
public class GameDownloadService {

    private static final Logger log = LoggerFactory.getLogger(GameDownloadService.class);

    public static final String STATE_QUEUED = "QUEUED";
    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_READY = "READY";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_CANCELLED = "CANCELLED";

    /** 打包产物保留时长；下载完可以手动删，过期自动清理 */
    static final Duration PACKAGE_TTL = Duration.ofHours(24);
    /** 打包前要求的最小剩余空间（除 zip 本身外），防止把系统盘写满 */
    private static final long DISK_RESERVE_BYTES = 1024L * 1024 * 1024;

    public record JobView(String jobId, String gameId, String state, String fileName,
                          long totalBytes, long doneBytes, int totalFiles, int doneFiles,
                          String error, String createdAt) {
    }

    /** 游戏目录不存在或 job 不属于该游戏 */
    public static class NotFound extends IllegalArgumentException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** 状态不允许当前操作（例如还没打包完就下载） */
    public static class Conflict extends IllegalStateException {
        public Conflict(String message) {
            super(message);
        }
    }

    private static final class Job {
        final String jobId;
        final String gameId;
        final Instant createdAt = Instant.now();
        final AtomicBoolean cancel = new AtomicBoolean();
        volatile String state = STATE_QUEUED;
        volatile String error = "";
        volatile long totalBytes;
        volatile long doneBytes;
        final AtomicLong written = new AtomicLong();
        volatile int totalFiles;
        volatile int doneFiles;
        volatile Path archive;

        Job(String jobId, String gameId) {
            this.jobId = jobId;
            this.gameId = gameId;
        }

        boolean terminal() {
            return STATE_READY.equals(state) || STATE_FAILED.equals(state)
                    || STATE_CANCELLED.equals(state);
        }

        JobView view() {
            return new JobView(jobId, gameId, state, gameId + ".zip",
                    totalBytes, written.get(), totalFiles, doneFiles, error,
                    createdAt.toString());
        }
    }

    private final PathConfig paths;
    private final ExecutorService queue;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    /** gameId -> jobId，保证同一游戏同时只有一个打包任务 */
    private final Map<String, String> active = new ConcurrentHashMap<>();

    @Autowired
    public GameDownloadService(PathConfig paths) {
        this(paths, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "game-download");
            t.setDaemon(true);
            return t;
        }));
    }

    /** 测试注入同步/单线程执行器 */
    GameDownloadService(PathConfig paths, ExecutorService queue) {
        this.paths = paths;
        this.queue = queue;
    }

    @PostConstruct
    void cleanupStaleOnBoot() {
        cleanupExpired();
    }

    public JobView start(String gameId) {
        Path dir = paths.gameDir(gameId);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new NotFound("游戏目录不存在: " + gameId);
        }
        synchronized (this) {
            String runningId = active.get(gameId);
            if (runningId != null) {
                Job running = jobs.get(runningId);
                if (running != null && !running.terminal()) {
                    return running.view();
                }
                active.remove(gameId, runningId);
            }
            String jobId = "dl-" + Instant.now().toString()
                    .replaceAll("[^0-9]", "").substring(0, 14) + "-"
                    + UUID.randomUUID().toString().substring(0, 4);
            Job job = new Job(jobId, gameId);
            jobs.put(jobId, job);
            active.put(gameId, jobId);
            queue.submit(() -> execute(job));
            return job.view();
        }
    }

    public JobView view(String gameId, String jobId) {
        return require(gameId, jobId).view();
    }

    /** 只有打包完成的包才允许下载 */
    public Path file(String gameId, String jobId) {
        Job job = require(gameId, jobId);
        if (!STATE_READY.equals(job.state)) {
            throw new Conflict("打包尚未完成，当前状态: " + job.state);
        }
        Path archive = job.archive;
        if (archive == null || !Files.isRegularFile(archive)) {
            throw new NotFound("打包文件已不存在，请重新打包");
        }
        return archive;
    }

    public void delete(String gameId, String jobId) {
        Job job = require(gameId, jobId);
        job.cancel.set(true);
        Path archive = job.archive;
        if (archive != null) {
            deleteRecursively(archive);
        }
        if (job.terminal()) {
            jobs.remove(jobId);
            active.remove(gameId, jobId);
        }
    }

    private Job require(String gameId, String jobId) {
        Job job = jobs.get(jobId);
        if (job == null || !job.gameId.equals(gameId)) {
            throw new NotFound("没有这个打包任务: " + jobId);
        }
        return job;
    }

    // ---------------- 打包 ----------------

    private void execute(Job job) {
        job.state = STATE_RUNNING;
        Path dir = packageDir(job.gameId);
        Path part = dir.resolve(job.jobId + ".zip.part");
        Path target = dir.resolve(job.jobId + ".zip");
        try {
            Files.createDirectories(dir);
            removeOtherPackages(job.gameId, target);
            List<Path> entries = collectEntries(paths.gameDir(job.gameId));
            job.totalFiles = (int) entries.stream()
                    .filter(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).count();
            job.totalBytes = entries.stream()
                    .filter(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(GameDownloadService::sizeOf).sum();
            ensureDiskSpace(dir, job.totalBytes);

            try (OutputStream out = Files.newOutputStream(part);
                 ZipOutputStream zip = new ZipOutputStream(out)) {
                Path root = paths.gameDir(job.gameId).toAbsolutePath().normalize();
                for (Path p : entries) {
                    if (job.cancel.get()) {
                        throw new Cancelled();
                    }
                    Path abs = p.toAbsolutePath().normalize();
                    if (!abs.startsWith(root)) {
                        continue;
                    }
                    String name = root.relativize(abs).toString().replace('\\', '/');
                    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                        zip.putNextEntry(new ZipEntry(name + "/"));
                        zip.closeEntry();
                        continue;
                    }
                    ZipEntry entry = new ZipEntry(name);
                    try {
                        entry.setTime(Files.getLastModifiedTime(p).toMillis());
                    } catch (IOException ignored) {
                        // 时间戳读不到不影响打包
                    }
                    zip.putNextEntry(entry);
                    try (InputStream in = Files.newInputStream(p)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            zip.write(buf, 0, n);
                            job.written.addAndGet(n);
                        }
                    }
                    zip.closeEntry();
                    job.doneFiles++;
                }
            }

            try {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
            job.archive = target;
            job.state = STATE_READY;
            log.info("游戏已打包 {} -> {} ({} 字节)", job.gameId, target, job.written.get());
        } catch (Cancelled e) {
            job.state = STATE_CANCELLED;
            job.error = "已取消";
            deleteRecursively(part);
        } catch (Exception e) {
            job.state = STATE_FAILED;
            job.error = e.getMessage() == null ? e.toString() : e.getMessage();
            deleteRecursively(part);
            log.warn("游戏打包失败 {}: {}", job.gameId, job.error, e);
        } finally {
            active.remove(job.gameId, job.jobId);
        }
    }

    /**
     * 只收集需要打包的路径：跳过门户生成物与符号链接。
     * 目录本身也收进来，保证空目录能在 zip 里保留。
     */
    private List<Path> collectEntries(Path root) throws IOException {
        Path absRoot = root.toAbsolutePath().normalize();
        try (var walk = Files.walk(root)) {
            return walk.filter(p -> !p.equals(root))
                    .filter(p -> included(absRoot, p))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private boolean included(Path root, Path p) {
        if (Files.isSymbolicLink(p)) {
            return false;
        }
        Path abs = p.toAbsolutePath().normalize();
        if (!abs.startsWith(root)) {
            return false;
        }
        Path rel = root.relativize(abs);
        String first = rel.getName(0).toString();
        if ("wineprefix".equals(first)) {
            return false;
        }
        if (rel.getNameCount() == 1 && "game.env".equals(first)) {
            return false;
        }
        // 恢复残留与临时目录必须整棵跳过，否则子文件会漏进压缩包
        if (first.startsWith(".galworld-") || first.endsWith(".galworld-old")) {
            return false;
        }
        String name = p.getFileName().toString();
        return !name.startsWith(".galworld-") && !name.endsWith(".galworld-old");
    }

    private void removeOtherPackages(String gameId, Path keep) {
        Path dir = packageDir(gameId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var list = Files.list(dir)) {
            for (Path p : list.toList()) {
                if (!p.equals(keep)) {
                    deleteRecursively(p);
                }
            }
        } catch (IOException e) {
            log.warn("清理旧打包文件失败 {}: {}", dir, e.toString());
        }
    }

    private void ensureDiskSpace(Path dir, long needBytes) throws IOException {
        FileStore store = Files.getFileStore(dir);
        long usable = store.getUsableSpace();
        if (usable < needBytes + DISK_RESERVE_BYTES) {
            throw new IOException("磁盘剩余空间不足：需要约 " + needBytes + " 字节，剩余 " + usable + " 字节");
        }
    }

    private Path packageDir(String gameId) {
        return paths.downloadDir().resolve(gameId);
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    // ---------------- 清理 ----------------

    /** 每小时清理过期打包产物 */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    public void cleanupExpired() {
        Path base = paths.downloadDir();
        if (!Files.isDirectory(base)) {
            return;
        }
        Instant cutoff = Instant.now().minus(PACKAGE_TTL);
        try (var dirs = Files.list(base)) {
            for (Path dir : dirs.toList()) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (var files = Files.list(dir)) {
                    for (Path p : files.toList()) {
                        try {
                            if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                                deleteRecursively(p);
                            }
                        } catch (IOException ignored) {
                            // 单个文件删不掉不影响其它清理
                        }
                    }
                }
                try (var left = Files.list(dir)) {
                    if (left.findAny().isEmpty()) {
                        Files.deleteIfExists(dir);
                    }
                } catch (IOException ignored) {
                    // 目录非空或不可读：留到下次
                }
            }
        } catch (IOException e) {
            log.warn("清理过期打包文件失败: {}", e.toString());
        }
    }

    private void deleteRecursively(Path p) {
        if (p == null || !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            for (Path x : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(x);
            }
        } catch (IOException e) {
            log.warn("删除文件失败 {}: {}", p, e.toString());
        }
    }

    /** 打包循环内部的中断信号 */
    private static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("已取消", null, false, false);
        }
    }
}
