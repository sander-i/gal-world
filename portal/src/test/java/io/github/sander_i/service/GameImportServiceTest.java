/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.deploy.DeployRun;
import io.github.sander_i.domain.Game;
import io.github.sander_i.platform.CommandRunner;
import io.github.sander_i.platform.OsKind;
import io.github.sander_i.repo.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameImportServiceTest {

    @TempDir Path root;

    /** 假命令执行器：wineboot 返回指定退出码，其余命令返回 0 */
    static GameImportService.Exec exec(int winebootExit) {
        return (run, argv, env, timeout) -> {
            run.log("$ " + String.join(" ", argv));
            return argv.contains("wineboot") ? winebootExit : 0;
        };
    }

    GameRepository repo;
    GameScanService scan;
    SessionService sessions;
    CommandRunner runner;
    GameImportService service;
    String fontPath;

    @BeforeEach
    void setUp() throws Exception {
        Path game = root.resolve("games/sample-game");
        Files.createDirectories(game);
        Files.write(game.resolve("game.exe"), new byte[64]);

        repo = mock(GameRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.existsById(anyString())).thenReturn(false);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scan = mock(GameScanService.class);
        // 让 writeEnv 真的落盘，后续断言才有意义
        doAnswer(inv -> {
            Game g = inv.getArgument(0);
            Path env = root.resolve("games/" + g.getId() + "/game.env");
            Files.createDirectories(env.getParent());
            Files.writeString(env, "GAME_ID=" + g.getId() + "\n");
            return null;
        }).when(scan).writeEnv(any());

        sessions = mock(SessionService.class);
        when(sessions.unitName(anyString())).thenReturn("galworld-game@sample-game");
        when(sessions.start(anyString(), anyInt())).thenReturn(new SessionService.SessionStatus(
                "sample-game", SessionService.State.RUNNING, "running", null, false));
        when(sessions.status(anyString(), anyInt())).thenReturn(new SessionService.SessionStatus(
                "sample-game", SessionService.State.RUNNING, "running", null, false));

        runner = mock(CommandRunner.class);
        when(runner.portOpen(anyInt())).thenReturn(true);

        fontPath = root.resolve("font.ttc").toString();
        service = buildService(exec(0));
    }

    private GameImportService buildService(GameImportService.Exec exec) {
        return new GameImportService(new PathConfig(root.toString()), repo, scan, sessions,
                runner, new GameExeScanner(), new GameSlotAllocator(),
                new GameImportValidator(new PathConfig(root.toString()), repo),
                new SaveService(new PathConfig(root.toString()), null),
                fontPath, root.resolve("wine-fonts.sh").toString(), exec);
    }

    private static void await(DeployRun run) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !run.terminal()) {
            Thread.sleep(20);
        }
    }

    @Test
    void importCreatesOriginalSaveBaselineOnce() throws Exception {
        Files.createDirectories(root.resolve("games/sample-game/savedata"));
        Files.writeString(root.resolve("games/sample-game/savedata/global.sav"), "orig");
        SaveService saves = new SaveService(new PathConfig(root.toString()), null);

        String runId = service.start("sample-game", "标题", "game.exe", 1280, 720, "30").runId();
        await(service.find(runId));

        var originals = saves.list("sample-game").stream()
                .filter(a -> SaveService.KIND_ORIGINAL.equals(a.kind()))
                .toList();
        assertEquals(1, originals.size());
        assertTrue(originals.get(0).label().contains("原始"));
    }

    @Test
    void candidatesExcludeRegisteredAndMetaDirectories() throws Exception {        Files.write(root.resolve("games/sample-game/game.env"), new byte[0]);
        Files.createDirectories(root.resolve("games/fresh"));
        Files.write(root.resolve("games/fresh/main.exe"), new byte[8]);
        Files.createDirectories(root.resolve("games/x.meta"));

        var c = service.candidates();
        assertEquals(List.of("fresh"),
                c.candidates().stream().map(GameImportService.Candidate::id).toList());
    }

    @Test
    void candidatesExposeNormalizedGameId() throws Exception {
        Files.createDirectories(root.resolve("games/9 Nine Episode 2"));
        Files.write(root.resolve("games/9 Nine Episode 2/g.exe"), new byte[8]);
        Files.createDirectories(root.resolve("games/a--b"));
        Files.write(root.resolve("games/a--b/g.exe"), new byte[8]);
        Files.createDirectories(root.resolve("games/x-y"));
        Files.write(root.resolve("games/x-y/g.exe"), new byte[8]);
        Files.createDirectories(root.resolve("games/x y"));
        Files.write(root.resolve("games/x y/g.exe"), new byte[8]);
        var c = service.candidates();
        var nine = c.candidates().stream()
                .filter(x -> x.id().equals("9 Nine Episode 2")).findFirst().orElseThrow();
        assertEquals("9-nine-episode-2", nine.gameId());
        // 合法 id 直通：a--b 是合法目录名，不应被归一化成 a-b
        var legal = c.candidates().stream()
                .filter(x -> x.id().equals("a--b")).findFirst().orElseThrow();
        assertEquals("a--b", legal.gameId());
        // 归一化后与已存在目录 x-y 冲突：追加 -2
        var conflict = c.candidates().stream()
                .filter(x -> x.id().equals("x y")).findFirst().orElseThrow();
        assertEquals("x-y-2", conflict.gameId());
    }

    @Test
    void slotsFollowAllocator() {
        assertEquals(2, service.slots().displayNum());
        assertEquals(9002, service.slots().port());
    }

    @Test
    void startRegistersThenRunsPipeline() throws Exception {
        String runId = service.start("sample-game", "标题", "game.exe", 1280, 720, "30").runId();
        DeployRun run = service.find(runId);
        await(run);
        assertEquals(DeployRun.State.SUCCESS, run.state(), run.error());
        assertTrue(Files.exists(root.resolve("games/sample-game/game.env")));
        verify(scan).writeEnv(any());
        verify(sessions, never()).start(anyString(), anyInt());   // 接入不再自动启动
    }

    @Test
    void fontsStepRunsWineFontsScript() throws Exception {
        Files.writeString(root.resolve("font.ttc"), "font");
        Path script = root.resolve("wine-fonts.sh");
        Files.writeString(script, "#!/usr/bin/env bash\nexit 0\n");
        List<List<String>> calls = new CopyOnWriteArrayList<>();
        GameImportService svc = buildService((run, argv, env, timeout) -> {
            calls.add(List.copyOf(argv));
            return 0;
        });
        String runId = svc.start("sample-game", "标题", "game.exe", 1280, 720, "30").runId();
        await(svc.find(runId));
        assertTrue(calls.stream().anyMatch(a ->
                        a.size() == 3 && a.get(1).equals(script.toString())),
                "应调用 wine-fonts.sh：" + calls);
    }

    @Test
    void snapshotExposesStepStates() throws Exception {        String runId = service.start("sample-game", "标题", "game.exe", 1280, 720, "30").runId();
        await(service.find(runId));
        var snap = service.snapshot(runId);
        assertEquals(5, snap.steps().size());   // 不再有 launch / verify
        assertTrue(snap.steps().stream().allMatch(s -> "SUCCESS".equals(s.state())));
    }

    @Test
    void winebootFailureMarksStepAndKeepsRegistration() throws Exception {
        service = buildService(exec(1));
        String runId = service.start("sample-game", "标题", "game.exe", 1280, 720, "30").runId();
        DeployRun run = service.find(runId);
        await(run);
        assertEquals(DeployRun.State.FAILED, run.state());
        assertTrue(run.error().contains("wineboot"), run.error());
        assertTrue(Files.exists(root.resolve("games/sample-game/game.env")));
    }

    @Test
    void secondStartWhileBusyIsRejected() throws Exception {
        AtomicBoolean release = new AtomicBoolean(false);
        service = buildService((run, argv, env, timeout) -> {
            while (!release.get()) {
                Thread.sleep(20);
            }
            return 0;
        });
        String runId = service.start("sample-game", "标题", "game.exe", 1280, 720, "30").runId();
        try {
            assertThrows(IllegalStateException.class,
                    () -> service.start("sample-game", "标题", "game.exe", 1280, 720, "30"));
        } finally {
            release.set(true);
        }
        // 等后台管线收尾：否则临时目录删除时文件仍被占用（Windows 上直接报错）
        await(service.find(runId));
    }

    @Test
    void missingFontIsWarningNotFailure() throws Exception {
        String runId = service.start("sample-game", "标题", "game.exe", 1280, 720, "30").runId();
        DeployRun run = service.find(runId);
        await(run);
        assertEquals(DeployRun.State.SUCCESS, run.state(), run.error());
        assertTrue(run.lines().stream().anyMatch(l -> l.contains("字体")));
    }

    @Test
    void platformGuardIsReportedByCandidates() {
        assertEquals(OsKind.current() == OsKind.LINUX, service.candidates().supported());
    }

    /** 接入只做注册与初始化，不自动启动游戏（启动由用户在详情页/游玩页触发）。 */
    @Test
    void importDoesNotStartOrStopSession() throws Exception {
        String runId = service.start("sample-game", "标题", "game.exe", 1280, 720, "30").runId();
        DeployRun run = service.find(runId);
        await(run);

        assertEquals(DeployRun.State.SUCCESS, run.state(), run.error());
        assertFalse(run.lines().stream().anyMatch(l -> l.contains("systemctl --user start")),
                run.lines().toString());
        verify(sessions, never()).start(anyString(), anyInt());
        verify(sessions, never()).stop(anyString(), anyInt());
    }

    @Test
    void startRenamesDirWithIllegalNameAndRegistersLegalId() throws Exception {
        Path raw = root.resolve("games/9 Nine Episode 2");
        Files.createDirectories(raw);
        Files.write(raw.resolve("nine.exe"), new byte[64]);
        String exeAbs = raw.resolve("nine.exe").toAbsolutePath().toString();

        String runId = service.start("9 Nine Episode 2", "9-nine", exeAbs, 1280, 720, "30").runId();
        DeployRun run = service.find(runId);
        await(run);

        assertEquals(DeployRun.State.SUCCESS, run.state(), run.error());
        Path renamed = root.resolve("games/9-nine-episode-2");
        assertTrue(Files.isDirectory(renamed), "目录应被重命名");
        assertFalse(Files.exists(raw), "原目录不应残留");
        assertTrue(Files.exists(renamed.resolve("game.env")));
        verify(sessions, never()).start(anyString(), anyInt());
    }

    @Test
    void renameIsRolledBackWhenValidationFails() throws Exception {
        Path raw = root.resolve("games/Bad Name");
        Files.createDirectories(raw);
        Files.write(raw.resolve("g.exe"), new byte[8]);
        assertThrows(IllegalArgumentException.class,
                () -> service.start("Bad Name", "n", "g.exe", 100, 100, "30"));
        assertTrue(Files.isDirectory(raw), "校验失败应改回原名");
        assertFalse(Files.exists(root.resolve("games/bad-name")));
    }

    /** 候选预览的 gameId 只是推演值；后端 start() 返回的实际 id 必须与之一致。 */
    @Test
    void previewGameIdMatchesRegisteredId() throws Exception {
        Path dir = root.resolve("games/9 Nine Episode 2");
        Files.createDirectories(dir);
        Files.write(dir.resolve("g.exe"), new byte[8]);

        var preview = service.candidates().candidates().stream()
                .filter(x -> x.id().equals("9 Nine Episode 2")).findFirst().orElseThrow();
        GameImportService.StartResult res =
                service.start("9 Nine Episode 2", "n", "g.exe", 1280, 720, "30");
        assertEquals(preview.gameId(), res.gameId());
        assertEquals("9 Nine Episode 2", res.renamedFrom());

        await(service.find(res.runId()));
        verify(sessions, never()).start(anyString(), anyInt());
    }

    /** 改名之后的步骤失败时，不能让用户的原始目录名消失。 */
    @Test
    void scanFailureBeforeRenameDoesNotLeaveRenamedDir() throws Exception {
        Path raw = root.resolve("games/Bad Name");
        Files.createDirectories(raw);
        Files.write(raw.resolve("g.exe"), new byte[8]);
        doThrow(new IllegalStateException("db down")).when(scan).syncAll();

        assertThrows(IllegalStateException.class,
                () -> service.start("Bad Name", "n", "g.exe", 1280, 720, "30"));
        assertTrue(Files.isDirectory(raw), "扫描失败时目录还没改名，应保持原样");
        assertFalse(Files.exists(root.resolve("games/bad-name")));
    }

    /** Error（不是 Exception）也要释放 busy，否则后续接入会被 409 卡死。 */
    @Test
    void busyIsReleasedWhenStartThrowsError() throws Exception {
        when(scan.syncAll())
                .thenThrow(new java.io.IOError(new java.io.IOException("boom")))
                .thenReturn(List.of());

        assertThrows(java.io.IOError.class,
                () -> service.start("sample-game", "标题", "game.exe", 1280, 720, "30"));

        GameImportService.StartResult res =
                service.start("sample-game", "标题", "game.exe", 1280, 720, "30");
        DeployRun run = service.find(res.runId());
        await(run);
        assertEquals(DeployRun.State.SUCCESS, run.state(), run.error());
    }

    @Test
    void renameDoesNotClobberExistingDirectory() throws Exception {
        Files.createDirectories(root.resolve("games/bad-name"));
        Path raw = root.resolve("games/Bad Name");
        Files.createDirectories(raw);
        Files.write(raw.resolve("g.exe"), new byte[8]);
        String runId = service.start("Bad Name", "n", "g.exe", 1280, 720, "30").runId();
        await(service.find(runId));
        assertTrue(Files.isDirectory(root.resolve("games/bad-name-2")), "冲突时应加后缀");
    }

    @Test
    void rejectsIdThatEscapesGamesDirectory() throws Exception {
        Files.createDirectories(root.resolve("data/secret"));
        assertThrows(IllegalArgumentException.class,
                () -> service.start("../data/secret", "n", "x.exe", 1280, 720, "30"));
        assertTrue(Files.isDirectory(root.resolve("data/secret")), "games 外目录不应被移动");
        try (var entries = Files.list(root.resolve("games"))) {
            assertEquals(1, entries.count(), "games 下不应新增目录");
        }
        String runId = service.start("sample-game", "标题", "game.exe", 1280, 720, "30").runId();
        await(service.find(runId));
        assertEquals(DeployRun.State.SUCCESS, service.find(runId).state());
    }

    @Test
    void keepsAlreadyLegalIdWithoutRenaming() throws Exception {
        Path legal = root.resolve("games/a--b");
        Files.createDirectories(legal);
        Files.write(legal.resolve("g.exe"), new byte[8]);
        String runId = service.start("a--b", "n", "g.exe", 1280, 720, "30").runId();
        await(service.find(runId));
        assertEquals(DeployRun.State.SUCCESS, service.find(runId).state(),
                service.find(runId).error());
        assertTrue(Files.isDirectory(legal), "合法 id 的目录不应被移动");
        assertFalse(Files.exists(root.resolve("games/a-b")), "合法 id 不应被归一化改名");
    }
}
