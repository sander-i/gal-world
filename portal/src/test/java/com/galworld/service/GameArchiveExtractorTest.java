/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameArchiveExtractorTest {

    @TempDir
    Path tmp;

    private final GameArchiveExtractor extractor = new GameArchiveExtractor();

    private static GameUploadLimits limits(long maxFile, long maxTotal, int maxFiles) {
        return new GameUploadLimits(21474836480L, maxTotal, maxFile, maxFiles, 12, 200,
                8388608, 16777216, "24h", 0L, "GBK");
    }

    private static GameUploadLimits limits() {
        return limits(100_000_000L, 1_000_000_000L, 1000);
    }

    // ---------------- 构造压缩包 ----------------

    interface ZipWriter {
        void write(ZipArchiveOutputStream out) throws IOException;
    }

    private Path zip(String name, ZipWriter writer) throws IOException {
        Path out = tmp.resolve(name);
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(out)) {
            writer.write(zos);
        }
        return out;
    }

    private static void entry(ZipArchiveOutputStream zos, String name, byte[] data) throws IOException {
        ZipArchiveEntry e = new ZipArchiveEntry(name);
        zos.putArchiveEntry(e);
        zos.write(data);
        zos.closeArchiveEntry();
    }

    private Path sevenZ(String name, boolean encrypted, String entryName, byte[] data)
            throws IOException {
        Path out = tmp.resolve(name);
        try (SevenZOutputFile zof = new SevenZOutputFile(out.toFile(),
                encrypted ? "secret".toCharArray() : null)) {
            SevenZArchiveEntry e = new SevenZArchiveEntry();
            e.setName(entryName);
            e.setSize(data.length);
            zof.putArchiveEntry(e);
            zof.write(data);
            zof.closeArchiveEntry();
        }
        return out;
    }

    private Path extractDir(String name) {
        return tmp.resolve("extract-" + name);
    }

    // ---------------- 正常路径 ----------------

    @Test
    void extractsZipAndStripsSingleWrapper() throws IOException {
        Path archive = zip("game.zip", zos -> {
            entry(zos, "wrapper/game/setup.exe", new byte[]{1, 2, 3});
            entry(zos, "wrapper/game/data.txt", "hello".getBytes());
        });
        Path target = extractDir("normal");
        extractor.extract(archive, target, limits(), s -> { }, () -> false);
        assertEquals(GameArchiveExtractor.KIND_ZIP, GameArchiveExtractor.detectKind(archive));

        Path root = GameArchiveExtractor.stripWrapper(target, 12);
        assertEquals("wrapper/game", target.relativize(root).toString().replace('\\', '/'));
        assertTrue(Files.isRegularFile(root.resolve("setup.exe")));
    }

    @Test
    void decodesGbkFilenames() throws IOException {
        Path archive;
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(
                Files.newOutputStream(tmp.resolve("gbk.zip")))) {
            zos.setEncoding("GBK");
            ZipArchiveEntry e = new ZipArchiveEntry("游戏/启动.exe");
            zos.putArchiveEntry(e);
            zos.write(new byte[]{1});
            zos.closeArchiveEntry();
        }
        archive = tmp.resolve("gbk.zip");
        Path target = extractDir("gbk");
        extractor.extract(archive, target, limits(), s -> { }, () -> false);
        assertTrue(Files.isRegularFile(target.resolve("游戏/启动.exe")),
                "GBK 文件名应正确解码，实际: " + listAll(target));
    }

    @Test
    void skipsGarbageEntries() throws IOException {
        Path archive = zip("garbage.zip", zos -> {
            entry(zos, "__MACOSX/._game.exe", new byte[]{9});
            entry(zos, "game/.DS_Store", new byte[]{9});
            entry(zos, "game/Thumbs.db", new byte[]{9});
            entry(zos, "game/real.exe", new byte[]{1});
        });
        Path target = extractDir("garbage");
        extractor.extract(archive, target, limits(), s -> { }, () -> false);
        assertTrue(Files.isRegularFile(target.resolve("game/real.exe")));
        assertFalse(Files.exists(target.resolve("game/.DS_Store")));
        assertFalse(Files.exists(target.resolve("__MACOSX")));
    }

    @Test
    void stripsMultipleWrapperLevelsButNotMultipleChildren() throws IOException {
        Path archive = zip("strip.zip", zos -> {
            entry(zos, "a/b/game.exe", new byte[]{1});
            entry(zos, "a/b/readme.txt", new byte[]{2});
        });
        Path target = extractDir("strip");
        extractor.extract(archive, target, limits(), s -> { }, () -> false);
        Path root = GameArchiveExtractor.stripWrapper(target, 12);
        assertEquals("a/b", target.relativize(root).toString().replace('\\', '/'));

        Path archive2 = zip("nostrip.zip", zos -> {
            entry(zos, "a/x.txt", new byte[]{1});
            entry(zos, "b/y.txt", new byte[]{1});
        });
        Path target2 = extractDir("nostrip");
        extractor.extract(archive2, target2, limits(), s -> { }, () -> false);
        assertEquals(target2, GameArchiveExtractor.stripWrapper(target2, 12));
    }

    @Test
    void extractsSevenZip() throws IOException {
        Path archive = sevenZ("game.7z", false, "game/setup.exe", new byte[]{7, 8, 9});
        Path target = extractDir("7z");
        extractor.extract(archive, target, limits(), s -> { }, () -> false);
        assertArrayEquals(new byte[]{7, 8, 9},
                Files.readAllBytes(target.resolve("game/setup.exe")));
    }

    @Test
    void rejectsEncryptedSevenZip() throws IOException {
        Path archive = sevenZ("secret.7z", true, "game.exe", new byte[]{1});
        Path target = extractDir("secret");
        assertThrows(GameUploadService.UnsupportedArchive.class,
                () -> extractor.extract(archive, target, limits(), s -> { }, () -> false));
    }

    // ---------------- 安全校验 ----------------

    @Test
    void rejectsTraversalAbsoluteAndDrivePaths() throws IOException {
        for (String name : List.of("../evil.txt", "/etc/passwd", "C:/windows/x.txt")) {
            Path archive = zip("bad-" + Math.abs(name.hashCode()) + ".zip", zos ->
                    entry(zos, name, new byte[]{1}));
            Path target = extractDir("bad-" + Math.abs(name.hashCode()));
            assertThrows(IllegalArgumentException.class,
                    () -> extractor.extract(archive, target, limits(), s -> { }, () -> false),
                    "应拒绝条目: " + name);
        }
    }

    @Test
    void rejectsSymlinkEntry() throws IOException {
        Path archive = zip("link.zip", zos -> {
            ZipArchiveEntry e = new ZipArchiveEntry("game/link");
            e.setUnixMode(0120777);
            zos.putArchiveEntry(e);
            zos.write("target".getBytes());
            zos.closeArchiveEntry();
        });
        Path target = extractDir("link");
        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(archive, target, limits(), s -> { }, () -> false));
    }

    @Test
    void rejectsLimits() throws IOException {
        Path many = zip("many.zip", zos -> {
            entry(zos, "game/a.txt", new byte[]{1});
            entry(zos, "game/b.txt", new byte[]{1});
        });
        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(many, extractDir("many"),
                        limits(1000, 1000, 1), s -> { }, () -> false));

        Path big = zip("big.zip", zos -> entry(zos, "game/a.bin", new byte[10]));
        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(big, extractDir("big"),
                        limits(4, 1000, 10), s -> { }, () -> false));

        Path total = zip("total.zip", zos -> {
            entry(zos, "game/a.bin", new byte[6]);
            entry(zos, "game/b.bin", new byte[6]);
        });
        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(total, extractDir("total"),
                        limits(1000, 8, 10), s -> { }, () -> false));
    }

    // ---------------- 移入正式目录 ----------------

    @Test
    void moveIntoRefusesToOverwrite() throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Files.writeString(src.resolve("a.txt"), "a");
        Path target = Files.createDirectories(tmp.resolve("target"));
        assertThrows(IOException.class, () -> GameArchiveExtractor.moveInto(src, target));
        assertTrue(Files.isDirectory(target));
    }

    @Test
    void moveIntoWorksWhenTargetMissing() throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src2"));
        Files.writeString(src.resolve("a.txt"), "a");
        Path target = tmp.resolve("games/mygame");
        GameArchiveExtractor.moveInto(src, target);
        assertEquals("a", Files.readString(target.resolve("a.txt")));
        assertFalse(Files.exists(src));
    }

    // ---------------- 工具 ----------------

    private static List<String> listAll(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        try (var s = Files.walk(root)) {
            s.forEach(p -> out.add(root.relativize(p).toString()));
        }
        return out;
    }

    @Test
    void validateRelativeRules() {
        assertEquals("a/b", GameArchiveExtractor.validateRelative("a/./b", 12));
        assertThrows(IllegalArgumentException.class,
                () -> GameArchiveExtractor.validateRelative("a/../b", 12));
        assertThrows(IllegalArgumentException.class,
                () -> GameArchiveExtractor.validateRelative("", 12));
        assertThrows(IllegalArgumentException.class,
                () -> GameArchiveExtractor.validateRelative("a/b/c/d", 3));
        assertEquals("a/b", GameArchiveExtractor.validateRelative("a\\b", 12));
    }

    @Test
    void charsetIsConfigurable() {
        GameUploadLimits l = new GameUploadLimits(1, 1, 1, 1, 1, 1, 1, 1, "24h", 0L, "UTF-8");
        assertEquals(Charset.forName("UTF-8"), l.zipCharset());
    }
}
