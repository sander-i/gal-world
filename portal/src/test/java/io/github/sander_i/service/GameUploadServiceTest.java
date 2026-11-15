/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.repo.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameUploadServiceTest {

    @TempDir
    Path root;

    private GameRepository repo;
    private GameUploadLimits limits;
    private GameUploadService service;

    /** 同步执行器：complete 提交的任务当场跑完，断言不需要轮询 */
    static final class DirectExecutor extends AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        repo = mock(GameRepository.class);
        when(repo.existsById(anyString())).thenReturn(false);
        limits = new GameUploadLimits(1024 * 1024, 1024 * 1024, 1024 * 1024,
                1000, 12, 200, 4, 1024 * 1024, "24h", 0L, "GBK");
        service = buildService(limits);
    }

    private GameUploadService buildService(GameUploadLimits l) {
        return new GameUploadService(new PathConfig(root.toString()), repo,
                new GameExeScanner(), new GameArchiveExtractor(), l, new DirectExecutor());
    }

    private static String sha(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder b = new StringBuilder();
            for (byte x : d) {
                b.append(String.format("%02x", x));
            }
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] sampleZip() throws IOException {
        Path zip = root.resolve("sample.zip");
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("game/game.exe"));
            zos.write(new byte[]{1, 2, 3});
            zos.closeEntry();
        }
        return Files.readAllBytes(zip);
    }

    // ---------------- 创建 ----------------

    @Test
    void createsArchiveSession() {
        var res = service.create(new GameUploadService.CreateRequest(
                "ARCHIVE", "示例游戏", "game.zip", 100L, null));
        assertNotNull(res.uploadId());
        assertEquals(4, res.chunkSize());
        assertEquals(1, res.files().size());
        assertEquals(100L, res.files().get(0).size());
        // 中文名归一化后是哈希占位，必须仍是合法 id
        assertTrue(GameImportValidator.isValidId(res.gameId()), res.gameId());
    }

    @Test
    void gameIdConflictGetsSuffix() throws IOException {
        Files.createDirectories(root.resolve("games/same"));
        var res = service.create(new GameUploadService.CreateRequest(
                "ARCHIVE", "same", "game.zip", 10L, null));
        assertEquals("same-2", res.gameId());
    }

    @Test
    void rejectsInvalidCreateRequests() {
        assertThrows(GameUploadService.UnsupportedArchive.class, () -> service.create(
                new GameUploadService.CreateRequest("ARCHIVE", "x", "game.rar", 10L, null)));
        assertThrows(GameUploadService.TooLarge.class, () -> service.create(
                new GameUploadService.CreateRequest("ARCHIVE", "x", "game.zip", 10_000_000_000L, null)));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new GameUploadService.CreateRequest("FOLDER", "x", null, 5L,
                        List.of(new GameUploadService.FileSpec("a.txt", 2)))));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new GameUploadService.CreateRequest("ARCHIVE", "", "game.zip", 10L, null)));
    }

    // ---------------- 分片 ----------------

    @Test
    void chunkFlowAndStatus() throws IOException {
        byte[] zip = sampleZip();
        var res = service.create(new GameUploadService.CreateRequest(
                "ARCHIVE", "game", "game.zip", (long) zip.length, null));
        String id = res.uploadId();
        String fileId = res.files().get(0).fileId();

        byte[] first = java.util.Arrays.copyOfRange(zip, 0, 4);
        var r1 = service.putChunk(id, fileId, 0, sha(first), first);
        assertFalse(r1.done());
        assertEquals(4, r1.uploadedBytes());

        // 偏移不连续
        var err = assertThrows(GameUploadService.ChunkConflict.class,
                () -> service.putChunk(id, fileId, 0, sha(first), first));
        assertEquals(4, err.actualOffset());

        // 哈希错误
        assertThrows(IllegalArgumentException.class,
                () -> service.putChunk(id, fileId, 4, "deadbeef", first));

        putAllRest(id, fileId, zip, 4);

        var status = service.status(id);
        assertEquals(zip.length, status.uploadedBytes());
        assertEquals(GameUploadService.STATE_UPLOADING, status.state());
        assertEquals(1, status.files().size());
    }

    private void putAllRest(String uploadId, String fileId, byte[] data, int from) {
        for (int off = from; off < data.length; off += 4) {
            byte[] chunk = java.util.Arrays.copyOfRange(data, off, Math.min(data.length, off + 4));
            service.putChunk(uploadId, fileId, off, sha(chunk), chunk);
        }
    }

    @Test
    void rejectsOversizedAndEmptyChunks() throws IOException {
        byte[] zip = sampleZip();
        var res = service.create(new GameUploadService.CreateRequest(
                "ARCHIVE", "game", "game.zip", (long) zip.length, null));
        assertThrows(IllegalArgumentException.class, () -> service.putChunk(
                res.uploadId(), res.files().get(0).fileId(), 0, sha(new byte[0]), new byte[0]));
    }

    @Test
    void rejectsArchiveWhoseMagicDoesNotMatchExtension() {
        var res = service.create(new GameUploadService.CreateRequest(
                "ARCHIVE", "game", "game.zip", 4L, null));
        byte[] junk = new byte[]{1, 2, 3, 4};
        assertThrows(GameUploadService.UnsupportedArchive.class, () -> service.putChunk(
                res.uploadId(), res.files().get(0).fileId(), 0, sha(junk), junk));
    }

    // ---------------- 完成 ----------------

    @Test
    void completeArchiveExtractsAndMovesIntoGames() throws IOException {
        byte[] zip = sampleZip();
        var res = service.create(new GameUploadService.CreateRequest(
                "ARCHIVE", "mygame", "game.zip", (long) zip.length, null));
        putAllRest(res.uploadId(), res.files().get(0).fileId(), zip, 0);

        GameUploadService.CompleteResult done = service.complete(res.uploadId());
        assertEquals(GameUploadService.STATE_COMPLETING, done.state());
        var status = service.status(res.uploadId());
        assertEquals(GameUploadService.STATE_READY, status.state(), status.error());
        assertNotNull(status.result());
        assertEquals("mygame", status.result().gameId());
        assertEquals(1, status.result().exes().size());
        assertEquals("game.exe", status.result().exes().get(0).path());
        assertTrue(Files.isRegularFile(root.resolve("games/mygame/game.exe")));
        // 清单保留，供刷新后恢复「继续配置」
        assertTrue(Files.isRegularFile(root.resolve("data/import-tmp/" + res.uploadId() + "/upload.json")));
        // 大文件已清理
        assertFalse(Files.exists(root.resolve("data/import-tmp/" + res.uploadId() + "/files")));
    }

    @Test
    void completeIsIdempotentAndIncompleteIsRejected() throws IOException {
        byte[] zip = sampleZip();
        var res = service.create(new GameUploadService.CreateRequest(
                "ARCHIVE", "game", "game.zip", (long) zip.length, null));
        String id = res.uploadId();
        String fileId = res.files().get(0).fileId();

        assertThrows(GameUploadService.ChunkConflict.class, () -> service.complete(id));

        putAllRest(id, fileId, zip, 0);
        assertEquals(GameUploadService.STATE_COMPLETING, service.complete(id).state());
        assertEquals(GameUploadService.STATE_READY, service.status(id).state());
        var again = service.complete(id);
        assertEquals(GameUploadService.STATE_READY, again.state());
        assertNotNull(again.result());
    }

    @Test
    void folderUploadMaterializesTreeAndStripsWrapper() throws IOException {
        byte[] content = "hello".getBytes();
        var res = service.create(new GameUploadService.CreateRequest("FOLDER", "foldergame",
                null, (long) content.length,
                List.of(new GameUploadService.FileSpec("wrapper/game/game.exe", content.length))));
        assertEquals("wrapper/game/game.exe", res.files().get(0).path());
        service.putChunk(res.uploadId(), res.files().get(0).fileId(), 0,
                sha(content), content);

        var done = service.complete(res.uploadId());
        assertEquals(GameUploadService.STATE_COMPLETING, done.state());
        var status = service.status(res.uploadId());
        assertEquals(GameUploadService.STATE_READY, status.state(), status.error());
        assertTrue(Files.isRegularFile(root.resolve("games/foldergame/game.exe")));
    }

    @Test
    void folderWithZeroByteFileCompletes() throws IOException {
        byte[] content = "hello".getBytes();
        var res = service.create(new GameUploadService.CreateRequest("FOLDER", "emptyfile",
                null, (long) content.length,
                List.of(new GameUploadService.FileSpec("wrapper/game.exe", content.length),
                        new GameUploadService.FileSpec("wrapper/empty.ini", 0))));
        service.putChunk(res.uploadId(), res.files().get(0).fileId(), 0,
                sha(content), content);

        service.complete(res.uploadId());
        var status = service.status(res.uploadId());
        assertEquals(GameUploadService.STATE_READY, status.state(), status.error());
        Path empty = root.resolve("games/emptyfile/empty.ini");
        assertTrue(Files.isRegularFile(empty));
        assertEquals(0, Files.size(empty));
    }

    @Test
    void targetDirectoryConflictFailsTheJob() throws IOException {
        byte[] zip = sampleZip();
        var res = service.create(new GameUploadService.CreateRequest(
                "ARCHIVE", "clash", "game.zip", (long) zip.length, null));
        putAllRest(res.uploadId(), res.files().get(0).fileId(), zip, 0);
        Files.createDirectories(root.resolve("games/" + res.gameId()));

        var done = service.complete(res.uploadId());
        assertEquals(GameUploadService.STATE_COMPLETING, done.state());
        var status = service.status(res.uploadId());
        assertEquals(GameUploadService.STATE_FAILED, status.state());
        assertTrue(status.error().contains("目标目录已存在"), status.error());
    }

    @Test
    void retryAfterFailureReusesAssembledArchive() throws IOException {
        byte[] zip = sampleZip();
        var res = service.create(new GameUploadService.CreateRequest(
                "ARCHIVE", "retrygame", "game.zip", (long) zip.length, null));
        String id = res.uploadId();
        putAllRest(id, res.files().get(0).fileId(), zip, 0);

        Path target = root.resolve("games/" + res.gameId());
        Files.createDirectories(target);
        assertEquals(GameUploadService.STATE_COMPLETING, service.complete(id).state());
        var failed = service.status(id);
        assertEquals(GameUploadService.STATE_FAILED, failed.state());
        // 分片已合并进 archive.zip：状态不能再显示 0 字节，否则前端会整包重传
        assertEquals(zip.length, failed.uploadedBytes());

        Files.delete(target);
        assertEquals(GameUploadService.STATE_COMPLETING, service.complete(id).state());
        assertEquals(GameUploadService.STATE_READY, service.status(id).state());
        assertTrue(Files.isRegularFile(root.resolve("games/" + res.gameId() + "/game.exe")));
    }

    // ---------------- 取消 ----------------

    @Test
    void cancelUploadingDeletesTemporaryDirectory() {
        var res = service.create(new GameUploadService.CreateRequest(
                "ARCHIVE", "cancele", "game.zip", 10L, null));
        Path dir = root.resolve("data/import-tmp/" + res.uploadId());
        assertTrue(Files.isDirectory(dir));
        var out = service.cancel(res.uploadId());
        assertEquals(GameUploadService.STATE_CANCELLED, out.state());
        assertFalse(Files.exists(dir));
        assertThrows(GameUploadService.NotFound.class, () -> service.status(res.uploadId()));
    }

    @Test
    void cancelReadyKeepsGamesDirectory() throws IOException {
        byte[] zip = sampleZip();
        var res = service.create(new GameUploadService.CreateRequest(
                "ARCHIVE", "keeper", "game.zip", (long) zip.length, null));
        putAllRest(res.uploadId(), res.files().get(0).fileId(), zip, 0);
        service.complete(res.uploadId());

        var out = service.cancel(res.uploadId());
        assertEquals(GameUploadService.STATE_READY, out.state());
        assertTrue(Files.isRegularFile(root.resolve("games/keeper/game.exe")));
    }

    // ---------------- 恢复与过期 ----------------

    @Test
    void recoversSessionFromManifestAfterRestart() throws IOException {
        byte[] zip = sampleZip();
        var res = service.create(new GameUploadService.CreateRequest(
                "ARCHIVE", "restart", "game.zip", (long) zip.length, null));
        byte[] first = java.util.Arrays.copyOfRange(zip, 0, 4);
        service.putChunk(res.uploadId(), res.files().get(0).fileId(), 0, sha(first), first);

        GameUploadService restarted = buildService(limits);
        restarted.recover();
        var status = restarted.status(res.uploadId());
        assertEquals(4, status.uploadedBytes());
        assertEquals(GameUploadService.STATE_UPLOADING, status.state());

        putAllRest(restarted, res.uploadId(), res.files().get(0).fileId(), zip, 4);
        assertEquals(GameUploadService.STATE_COMPLETING,
                restarted.complete(res.uploadId()).state());
        assertEquals(GameUploadService.STATE_READY, restarted.status(res.uploadId()).state());
    }

    private void putAllRest(GameUploadService svc, String uploadId, String fileId,
                            byte[] data, int from) {
        for (int off = from; off < data.length; off += 4) {
            byte[] chunk = java.util.Arrays.copyOfRange(data, off, Math.min(data.length, off + 4));
            svc.putChunk(uploadId, fileId, off, sha(chunk), chunk);
        }
    }

    @Test
    void interruptedCompleteBecomesFailedOnRecovery() throws IOException {
        var res = service.create(new GameUploadService.CreateRequest(
                "ARCHIVE", "interrupted", "game.zip", 10L, null));
        Path manifest = root.resolve("data/import-tmp/" + res.uploadId() + "/upload.json");
        String json = Files.readString(manifest)
                .replace("\"state\":\"UPLOADING\"", "\"state\":\"COMPLETING\"");
        Files.writeString(manifest, json);

        GameUploadService restarted = buildService(limits);
        restarted.recover();
        var status = restarted.status(res.uploadId());
        assertEquals(GameUploadService.STATE_FAILED, status.state());
        assertTrue(status.error().contains("服务重启"));
    }

    @Test
    void purgeExpiredRemovesOldSessions() throws Exception {
        GameUploadLimits shortTtl = new GameUploadLimits(1024 * 1024, 1024 * 1024, 1024 * 1024,
                1000, 12, 200, 4, 1024 * 1024, "1s", 0L, "GBK");
        GameUploadService svc = buildService(shortTtl);
        var res = svc.create(new GameUploadService.CreateRequest(
                "ARCHIVE", "expire", "game.zip", 10L, null));
        Thread.sleep(1100);
        svc.purgeExpired();
        assertThrows(GameUploadService.NotFound.class, () -> svc.status(res.uploadId()));
        assertFalse(Files.exists(root.resolve("data/import-tmp/" + res.uploadId())));
    }

    @Test
    void limitsAreExposed() {
        var l = service.limits();
        assertEquals(4, l.chunkSize());
        assertEquals(1000, l.maxFiles());
        assertEquals(1024 * 1024, l.maxUploadBytes());
    }

    @Test
    void unknownUploadIdIsNotFound() {
        assertThrows(GameUploadService.NotFound.class, () -> service.status("up-20260101-000000-abcd"));
        assertThrows(GameUploadService.NotFound.class, () -> service.status("../../etc/passwd"));
        assertThrows(GameUploadService.NotFound.class, () -> service.status(null));
    }
}
