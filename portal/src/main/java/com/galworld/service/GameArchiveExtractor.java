/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import org.apache.commons.compress.PasswordRequiredException;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 压缩包解压与目录整理：zip / 7z，带 zip-slip、链接、压缩炸弹与磁盘余量防护。
 *
 * <p>只碰文件系统，不依赖 Spring 上下文，便于在 Windows 开发机单测。
 */
@Component
public class GameArchiveExtractor {

    private static final Logger log = LoggerFactory.getLogger(GameArchiveExtractor.class);

    public static final String KIND_ZIP = "ZIP";
    public static final String KIND_7Z = "7Z";

    /** 解压时跳过的垃圾项（macOS / Windows 资源分支） */
    private static final Set<String> GARBAGE_NAMES = Set.of(
            ".DS_Store", "Thumbs.db", "desktop.ini", "__MACOSX");
    public static final String MACOSX_DIR = "__MACOSX";

    private static final int BUF = 64 * 1024;
    private static final long DISK_CHECK_STEP = 500L * 1024 * 1024;

    /** Unix 文件类型掩码与取值 */
    private static final int S_IFMT = 0170000;
    private static final int S_IFREG = 0100000;
    private static final int S_IFDIR = 0040000;

    /** Windows FILE_ATTRIBUTE_REPARSE_POINT，7z 用它标记符号链接 */
    private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x400;

    // ---------------- 文件头识别 ----------------

    /** 按魔数判断压缩格式；不认识返回 null。扩展名由调用方另行校验。 */
    public static String detectKind(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        byte[] head = new byte[6];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(head, 0, head.length);
        }
        return detectKind(head, n);
    }

    /** 用已读取的文件头判断格式，供分片上传时即时校验魔数 */
    public static String detectKind(byte[] head, int n) {
        if (head == null) {
            return null;
        }
        if (n >= 4 && head[0] == 'P' && head[1] == 'K'
                && ((head[2] == 0x03 && head[3] == 0x04) || (head[2] == 0x05 && head[3] == 0x06))) {
            return KIND_ZIP;
        }
        if (n >= 6 && (head[0] & 0xff) == 0x37 && (head[1] & 0xff) == 0x7a
                && (head[2] & 0xff) == 0xbc && (head[3] & 0xff) == 0xaf
                && (head[4] & 0xff) == 0x27 && (head[5] & 0xff) == 0x1c) {
            return KIND_7Z;
        }
        return null;
    }

    public static boolean isArchiveName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") || n.endsWith(".7z");
    }

    // ---------------- 解压 ----------------

    public void extract(Path archive, Path target, GameUploadLimits limits,
                        Consumer<String> log, BooleanSupplier cancelled) throws IOException {
        String kind = detectKind(archive);
        if (KIND_ZIP.equals(kind)) {
            extractZip(archive, target, limits, log, cancelled);
        } else if (KIND_7Z.equals(kind)) {
            extract7z(archive, target, limits, log, cancelled);
        } else {
            throw new GameUploadService.UnsupportedArchive("不是可识别的 zip / 7z 文件");
        }
    }

    /** 统计上下文：条目数、累计解压量、最近一次磁盘检查点 */
    private static final class Ctx {
        final GameUploadLimits limits;
        final Consumer<String> log;
        final BooleanSupplier cancelled;
        final Path root;
        int files;
        long total;

        Ctx(GameUploadLimits limits, Consumer<String> log, BooleanSupplier cancelled, Path root) {
            this.limits = limits;
            this.log = log;
            this.cancelled = cancelled;
            this.root = root;
        }

        void checkCancelled() {
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new GameUploadService.Cancelled("已取消");
            }
        }

        void countEntry(long size) {
            checkCancelled();
            if (++files > limits.maxFiles()) {
                throw new IllegalArgumentException("压缩包条目数超过上限 " + limits.maxFiles());
            }
            if (size >= 0) {
                if (size > limits.maxFileBytes()) {
                    throw new IllegalArgumentException("压缩包内单文件超过 " + limits.maxFileBytes() + " 字节");
                }
                total += size;
                if (total > limits.maxExtractedBytes()) {
                    throw new IllegalArgumentException("解压后总体积超过上限 " + limits.maxExtractedBytes() + " 字节");
                }
            }
        }
    }

    private void extractZip(Path archive, Path target, GameUploadLimits limits,
                            Consumer<String> log, BooleanSupplier cancelled) throws IOException {
        Files.createDirectories(target);
        Ctx ctx = new Ctx(limits, log, cancelled, target);
        try (ZipFile zf = ZipFile.builder()
                .setPath(archive)
                .setCharset(limits.zipCharset())
                .get()) {
            var entries = zf.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry e = entries.nextElement();
                if (e.getGeneralPurposeBit() != null && e.getGeneralPurposeBit().usesEncryption()) {
                    throw new GameUploadService.UnsupportedArchive("不支持带密码的压缩包");
                }
                String name = e.getName();
                if (isGarbage(name)) {
                    continue;
                }
                String rel = validateRelative(name, limits.maxDepth());
                ctx.countEntry(e.getSize());

                int mode = e.getUnixMode();
                if (e.isUnixSymlink() || (mode & S_IFMT) == 0120000) {
                    throw new IllegalArgumentException("压缩包含符号链接，已拒绝: " + name);
                }
                int type = mode & S_IFMT;
                if (type != 0 && type != S_IFREG && type != S_IFDIR) {
                    throw new IllegalArgumentException("压缩包含非普通文件条目，已拒绝: " + name);
                }
                long compressed = e.getCompressedSize();
                checkRatio(e.getSize(), compressed, limits, name);

                Path out = target.resolve(rel).normalize();
                if (!out.startsWith(target)) {
                    throw new IllegalArgumentException("压缩包条目越界: " + name);
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                long written = copyLimited(zf.getInputStream(e), out, ctx);
                maybeCheckDisk(ctx, written);
            }
        } catch (PasswordRequiredException e) {
            throw new GameUploadService.UnsupportedArchive("不支持带密码的压缩包");
        } catch (GameUploadService.Cancelled | IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            // 加密 / 分卷 / 损坏都落到这里，给出可读原因
            throw new IllegalArgumentException("解压失败: " + e.getMessage(), e);
        }
        log.accept("已解压 " + ctx.files + " 个文件，共 " + ctx.total + " 字节");
    }

    private void extract7z(Path archive, Path target, GameUploadLimits limits,
                           Consumer<String> log, BooleanSupplier cancelled) throws IOException {
        Files.createDirectories(target);
        Ctx ctx = new Ctx(limits, log, cancelled, target);
        try (SevenZFile zf = SevenZFile.builder().setPath(archive).get()) {
            SevenZArchiveEntry e;
            while ((e = zf.getNextEntry()) != null) {
                if (e.isAntiItem()) {
                    throw new IllegalArgumentException("压缩包含反条目，已拒绝: " + e.getName());
                }
                String name = e.getName();
                if (isGarbage(name)) {
                    continue;
                }
                String rel = validateRelative(name, limits.maxDepth());
                ctx.countEntry(e.getSize());
                if ((e.getWindowsAttributes() & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                    throw new IllegalArgumentException("压缩包含符号链接，已拒绝: " + name);
                }
                Path out = target.resolve(rel).normalize();
                if (!out.startsWith(target)) {
                    throw new IllegalArgumentException("压缩包条目越界: " + name);
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                long written = copyLimited(zf.getInputStream(e), out, ctx);
                maybeCheckDisk(ctx, written);
            }
        } catch (GameUploadService.Cancelled | IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            if (e instanceof PasswordRequiredException) {
                throw new GameUploadService.UnsupportedArchive("不支持带密码的压缩包");
            }
            throw new IllegalArgumentException("解压失败: " + e.getMessage(), e);
        }
        log.accept("已解压 " + ctx.files + " 个文件，共 " + ctx.total + " 字节");
    }

    /**
     * 解压一个条目写入磁盘，返回实际写入字节数。
     * 用 CREATE_NEW：重复条目直接报错，避免后者覆盖前者。
     */
    private static long copyLimited(InputStream in, Path out, Ctx ctx) throws IOException {
        long written = 0;
        byte[] buf = new byte[BUF];
        try (InputStream src = in;
             OutputStream dst = Files.newOutputStream(out,
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int n;
            while ((n = src.read(buf)) > 0) {
                ctx.checkCancelled();
                written += n;
                if (written > ctx.limits.maxFileBytes()) {
                    throw new IllegalArgumentException("解压后单文件超过上限: " + out.getFileName());
                }
                ctx.total += n;
                if (ctx.total > ctx.limits.maxExtractedBytes()) {
                    throw new IllegalArgumentException("解压后总体积超过上限");
                }
                dst.write(buf, 0, n);
            }
        }
        return written;
    }

    private static void maybeCheckDisk(Ctx ctx, long writtenBytes) throws IOException {
        long mark = DISK_CHECK_STEP;
        if (writtenBytes < mark) {
            return;
        }
        FileStore store = Files.getFileStore(ctx.root);
        if (store.getUsableSpace() < ctx.limits.diskReserveBytes()) {
            throw new IllegalArgumentException("磁盘剩余空间不足，解压已中止");
        }
    }

    private static void checkRatio(long size, long compressed, GameUploadLimits limits, String name) {
        if (size <= 0 || compressed <= 0 || limits.maxRatio() <= 0) {
            return;
        }
        if (size > 100L * 1024 * 1024 && size / compressed > limits.maxRatio()) {
            throw new IllegalArgumentException("压缩比异常，疑似压缩炸弹: " + name);
        }
    }

    // ---------------- 路径与垃圾项 ----------------

    public static boolean isGarbage(String name) {
        if (name == null) {
            return true;
        }
        String normalized = name.replace('\\', '/');
        for (String seg : normalized.split("/")) {
            if (GARBAGE_NAMES.contains(seg)) {
                return true;
            }
        }
        String base = normalized.substring(normalized.lastIndexOf('/') + 1);
        return GARBAGE_NAMES.contains(base);
    }

    /**
     * 校验并归一化相对路径。拒绝绝对路径、盘符、NUL、空路径、`.`/`..` 段与超深路径。
     */
    public static String validateRelative(String name, int maxDepth) {
        String raw = name == null ? "" : name.replace('\\', '/');
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("压缩包条目名为空");
        }
        if (raw.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("压缩包条目名包含非法字符");
        }
        if (raw.startsWith("/")) {
            throw new IllegalArgumentException("压缩包包含绝对路径: " + name);
        }
        if (raw.length() >= 2 && Character.isLetter(raw.charAt(0)) && raw.charAt(1) == ':') {
            throw new IllegalArgumentException("压缩包包含盘符路径: " + name);
        }
        List<String> parts = new ArrayList<>();
        for (String seg : raw.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                throw new IllegalArgumentException("压缩包包含上级目录引用: " + name);
            }
            parts.add(seg);
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("压缩包条目名为空");
        }
        if (parts.size() > maxDepth) {
            throw new IllegalArgumentException("压缩包路径层数超过 " + maxDepth + ": " + name);
        }
        return String.join("/", parts);
    }

    // ---------------- 剥壳 ----------------

    /**
     * 连续剥离「只有一个可见子目录」的包裹层。
     *
     * @return 剥壳后应整体移入 games/ 的根目录
     */
    public static Path stripWrapper(Path root, int maxDepth) throws IOException {
        Path cur = root;
        for (int i = 0; i < maxDepth; i++) {
            List<Path> children = visibleChildren(cur);
            if (children.size() != 1) {
                break;
            }
            Path only = children.get(0);
            if (!Files.isDirectory(only, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
            cur = only;
        }
        return cur;
    }

    private static List<Path> visibleChildren(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> !isGarbage(p.getFileName().toString()))
                    .forEach(out::add);
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    // ---------------- 移入正式目录 ----------------

    /** 原子移动，不支持时退化为递归复制 + 删除。失败时尽量保证目标目录不残留半成品。 */
    public static void moveInto(Path source, Path target) throws IOException {
        // 平台差异：Windows 上移动到已存在的目录会失败，Linux 的 rename(2) 却会把
        // 空目标目录直接替换掉。显式拒绝，保证两端行为一致且不会静默覆盖。
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("目标已存在，拒绝覆盖: " + target);
        }
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (AtomicMoveNotSupportedException e) {
            log.info("文件系统不支持原子移动，退化为复制: {} -> {}", source, target);
        }
        try {
            copyRecursively(source, target);
        } catch (IOException e) {
            deleteRecursively(target);
            throw e;
        }
        deleteRecursively(source);
    }

    public static void copyRecursively(Path source, Path target) throws IOException {
        List<Path> paths;
        try (Stream<Path> s = Files.walk(source)) {
            paths = s.sorted().toList();
        }
        for (Path p : paths) {
            if (Files.isSymbolicLink(p)) {
                throw new IllegalArgumentException("目录含符号链接，已拒绝: " + p);
            }
            Path rel = source.relativize(p);
            Path out = target.resolve(rel.toString());
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(out);
            } else {
                Files.createDirectories(out.getParent());
                Files.copy(p, out, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    /** 递归删除，符号链接只删链接本身；不存在时静默。 */
    public static void deleteRecursively(Path p) throws IOException {
        if (p == null || !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(p);
            return;
        }
        List<Path> paths;
        try (Stream<Path> s = Files.walk(p)) {
            paths = s.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path x : paths) {
            Files.deleteIfExists(x);
        }
    }
}
