package com.galworld.service;

import com.galworld.config.PathConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class GameDownloadServiceTest {

    @TempDir Path root;
    ExecutorService queue;
    GameDownloadService service;

    @BeforeEach
    void setUp() throws Exception {
        queue = Executors.newSingleThreadExecutor();
        service = new GameDownloadService(new PathConfig(root.toString()), queue);

        Path game = root.resolve("games/g1");
        Files.createDirectories(game.resolve("savedata"));
        Files.createDirectories(game.resolve("wineprefix/drive_c"));
        Files.createDirectories(game.resolve(".galworld-restore-old"));
        Files.writeString(game.resolve("game.exe"), "exe");
        Files.writeString(game.resolve("savedata/global.sav"), "save");
        Files.writeString(game.resolve("wineprefix/drive_c/system.reg"), "reg");
        Files.writeString(game.resolve("game.env"), "GAME_ID=g1");
        Files.writeString(game.resolve(".galworld-restore-old/leak.txt"), "leak");
    }

    @AfterEach
    void tearDown() {
        queue.shutdownNow();
    }

    private GameDownloadService.JobView await(String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        GameDownloadService.JobView job = service.view("g1", jobId);
        while (System.currentTimeMillis() < deadline && !terminal(job.state())) {
            Thread.sleep(20);
            job = service.view("g1", jobId);
        }
        return job;
    }

    private static boolean terminal(String state) {
        return Set.of(GameDownloadService.STATE_READY, GameDownloadService.STATE_FAILED,
                GameDownloadService.STATE_CANCELLED).contains(state);
    }

    @Test
    void packageContainsGameFilesButNotPortalArtifacts() throws Exception {
        GameDownloadService.JobView job = service.start("g1");
        job = await(job.jobId());

        assertEquals(GameDownloadService.STATE_READY, job.state(), job.error());
        assertEquals("g1.zip", job.fileName());
        assertEquals(2, job.totalFiles());
        assertEquals(7, job.totalBytes());

        Set<String> names = new TreeSet<>();
        try (ZipFile zip = new ZipFile(service.file("g1", job.jobId()).toFile())) {
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                names.add(e.nextElement().getName());
            }
        }
        assertTrue(names.contains("game.exe"), names.toString());
        assertTrue(names.contains("savedata/global.sav"), names.toString());
        assertTrue(names.contains("savedata/"), names.toString());
        assertFalse(names.stream().anyMatch(n -> n.startsWith("wineprefix")));
        assertFalse(names.contains("game.env"));
        assertFalse(names.stream().anyMatch(n -> n.startsWith(".galworld-")));
    }

    @Test
    void secondStartReusesRunningJob() throws Exception {
        ExecutorService blocked = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        blocked.submit(() -> {
            release.await();
            return null;
        });
        try {
            GameDownloadService queued = new GameDownloadService(new PathConfig(root.toString()), blocked);
            GameDownloadService.JobView a = queued.start("g1");
            GameDownloadService.JobView b = queued.start("g1");
            assertEquals(a.jobId(), b.jobId());

            // 排队中不允许下载
            assertThrows(GameDownloadService.Conflict.class,
                    () -> queued.file("g1", a.jobId()));
        } finally {
            release.countDown();
            blocked.shutdownNow();
        }
    }

    @Test
    void missingGameDirectoryIsRejected() {
        assertThrows(GameDownloadService.NotFound.class, () -> service.start("nope"));
    }

    @Test
    void deleteRemovesPackage() throws Exception {
        GameDownloadService.JobView job = await(service.start("g1").jobId());
        Path archive = service.file("g1", job.jobId());

        service.delete("g1", job.jobId());

        assertFalse(Files.exists(archive));
        assertThrows(GameDownloadService.NotFound.class, () -> service.view("g1", job.jobId()));
    }
}
