/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import com.galworld.config.PathConfig;
import com.galworld.deploy.DeployRun;
import com.galworld.domain.Game;
import com.galworld.platform.CommandRunner;
import com.galworld.platform.OsKind;
import com.galworld.repo.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 游戏接入：发现未注册目录，注册 game.env，初始化 wine 前缀，首次启动并验证。
 *
 * 只负责编排；参数校验、exe 枚举、槽位分配各自独立成类，便于单测。
 */
@Service
public class GameImportService {

    private static final Logger log = LoggerFactory.getLogger(GameImportService.class);

    public record Candidate(String id, String gameId, long sizeBytes, String mtime,
                            List<GameExeScanner.Exe> exes) {
    }

    public record Candidates(String gamesDir, boolean supported, List<String> warnings,
                             List<Candidate> candidates) {
    }

    public record Slots(int displayNum, int port, int loopbackCard, int loopbackSlot) {
    }

    public record StepState(String id, String title, String state) {
    }

    /**
     * @param gameId 后端实际注册用的 id（归一化后）。刷新/重试/断线恢复时前端
     *               必须以它为准，不能信 URL 里的旧值——旧页面可能传的是原目录名。
     */
    public record RunSnapshot(String runId, String state, List<StepState> steps,
                              List<String> lines, String error, String gameId) {
    }

    /**
     * start() 的结果。gameId 是后端实际注册用的 id（可能经过归一化），
     * 前端必须用它做路由，不能依赖候选列表的预览值。
     * renamedFrom 是改名前的原目录名，未改名时为 null。
     */
    public record StartResult(String runId, String gameId, String renamedFrom) {
    }

    /** 命令执行的唯一出口：生产用 ProcessExec，测试注入假实现 */
    public interface Exec {
        int streaming(DeployRun run, List<String> argv, Map<String, String> env, Duration timeout)
                throws Exception;
    }

    /**
     * 接入管线：只做到「注册 + 初始化」，**不自动启动游戏**。
     * 启动由用户在详情页/游玩页点按钮触发，避免接入完就占用显示号与 CPU。
     */
    static final List<String> STEP_IDS =
            List.of("validate", "allocate", "register", "wineboot", "fonts");
    static final Map<String, String> STEP_TITLES = stepTitles();

    private static Map<String, String> stepTitles() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("validate", "校验参数与目录");
        m.put("allocate", "分配显示号 / 端口 / 槽位");
        m.put("register", "写入 game.env 并入库");
        m.put("wineboot", "初始化 Wine 前缀");
        m.put("fonts", "安装中文字体");
        return Map.copyOf(m);
    }

    private static final Duration WINEBOOT_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration FONTS_TIMEOUT = Duration.ofSeconds(120);

    private final PathConfig paths;
    private final GameRepository repo;
    private final GameScanService scan;
    private final SessionService sessions;
    private final CommandRunner runner;
    private final GameExeScanner exeScanner;
    private final GameSlotAllocator allocator;
    private final GameImportValidator validator;
    private final SaveService saves;
    private final String fontPath;
    private final String fontScript;
    private final Exec exec;

    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "game-import");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, DeployRun> runs = new ConcurrentHashMap<>();
    private final Map<String, Ctx> contexts = new ConcurrentHashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean();

    public GameImportService(PathConfig paths, GameRepository repo, GameScanService scan,
                             SessionService sessions, CommandRunner runner,
                             GameExeScanner exeScanner, GameSlotAllocator allocator,
                             GameImportValidator validator, SaveService saves,
                             @Value("${galworld.import.font:/usr/share/fonts/truetype/wqy/wqy-microhei.ttc}")
                             String fontPath,
                             @Value("${galworld.import.font-script:}")
                             String fontScript,
                             Exec exec) {
        this.paths = paths;
        this.repo = repo;
        this.scan = scan;
        this.sessions = sessions;
        this.runner = runner;
        this.exeScanner = exeScanner;
        this.allocator = allocator;
        this.validator = validator;
        this.saves = saves;
        this.fontPath = fontPath;
        this.fontScript = fontScript == null || fontScript.isBlank()
                ? paths.root().resolve("bin/wine-fonts.sh").toString()
                : fontScript;
        this.exec = exec;
    }

    /** 一次 run 的上下文：请求参数、已算好的槽位、步骤状态 */
    private static final class Ctx {
        final GameImportValidator.Normalized req;
        final Slots slots;
        final List<StepState> steps = new ArrayList<>();
        final AtomicReference<String> current = new AtomicReference<>("");
        volatile boolean registered;
        volatile boolean cancelled;

        Ctx(GameImportValidator.Normalized req, Slots slots) {
            this.req = req;
            this.slots = slots;
            for (String id : STEP_IDS) {
                steps.add(new StepState(id, STEP_TITLES.get(id), "PENDING"));
            }
        }
    }

    // ---------------- 候选 ----------------

    public Candidates candidates() {
        List<String> warnings = new ArrayList<>();
        List<Candidate> out = new ArrayList<>();
        Path dir = paths.gamesDir();
        if (!Files.isDirectory(dir)) {
            return new Candidates(dir.toString(), OsKind.current() == OsKind.LINUX, warnings, out);
        }
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.getFileName().toString().endsWith(".meta"))
                    .filter(p -> !Files.exists(p.resolve("game.env")))
                    .sorted()
                    .forEach(p -> {
                        String id = p.getFileName().toString();
                        if (repo.existsById(id)) {
                            return;
                        }
                        try {
                            // 预览目标 id 必须与 start() 的判定完全一致：合法 id 原样保留，
                            // 不合法的归一化并避开已占用的目录名 / 已注册 id。
                            Path rawAbs = paths.gameDir(id).toAbsolutePath().normalize();
                            String gid = GameImportValidator.isValidId(id)
                                    ? id
                                    : GameIdNormalizer.normalizeUnique(id, other -> {
                                        Path otherAbs = paths.gameDir(other).toAbsolutePath().normalize();
                                        boolean dirTaken = !otherAbs.equals(rawAbs) && Files.exists(otherAbs);
                                        return dirTaken || repo.existsById(other);
                                    });
                            out.add(new Candidate(id, gid, dirSize(p),
                                    Files.getLastModifiedTime(p).toInstant().toString(),
                                    exeScanner.scan(p)));
                        } catch (IOException e) {
                            warnings.add(id + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            warnings.add("扫描 games 目录失败: " + e.getMessage());
        }
        return new Candidates(dir.toString(), OsKind.current() == OsKind.LINUX, warnings, out);
    }

    /** 有界目录体积：最多 8 层、50000 个文件，避免超大目录拖死请求 */
    private static long dirSize(Path dir) {
        long[] total = {0};
        long[] count = {0};
        try (Stream<Path> walk = Files.walk(dir, 8)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                if (count[0]++ > 50_000) {
                    return;
                }
                try {
                    total[0] += Files.size(p);
                } catch (IOException ignore) {
                    // 无权限的文件跳过
                }
            });
        } catch (IOException ignore) {
            // 目录不可读：返回已统计部分
        }
        return total[0];
    }

    public Slots slots() {
        scan.syncAll();
        return toDto(allocator.allocate(repo.findAll()));
    }

    private static Slots toDto(GameSlotAllocator.Slots s) {
        return new Slots(s.displayNum(), s.port(), s.loopbackCard(), s.loopbackSlot());
    }

    // ---------------- 启动 / 重试 ----------------

    /** 兼容旧调用：不带备用 exe */
    public StartResult start(String id, String name, String exe,
                             Integer width, Integer height, String framerate) {
        return start(id, name, exe, null, width, height, framerate);
    }

    public StartResult start(String id, String name, String exe, String exeAlt,
                             Integer width, Integer height, String framerate) {
        return start(id, name, exe, exeAlt, width, height, framerate, null);
    }

    /** 完整参数：gameLang 为空时跟随默认 C.UTF-8 */
    public StartResult start(String id, String name, String exe, String exeAlt,
                             Integer width, Integer height, String framerate, String gameLang) {
        // 先判并发再做校验：校验会检查 game.env 是否存在，而 env 要等上一个任务的
        // register 步骤才写盘；先校验会把「正在执行」误报成「项目未注册」。
        // 改名与校验都必须在持锁状态下做，否则两个请求可能同时改同一个目录。
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("已有接入任务在执行，请稍后");
        }
        boolean renamed = false;
        // run 创建成功后改名即交接给后台任务，不再回滚；busy 也由队列任务释放。
        boolean handedOff = false;
        String raw = null;
        String gid = null;
        Path rawDir = null;
        Path targetDir = null;
        try {
            // 不 trim：目录名是用户磁盘上的真实名字，首尾空格必须当作非法 id 走归一化改名，
            // 否则这里按 trim 后的名字找目录会与候选预览（用原始目录名）不一致。
            raw = id == null ? "" : id;
            if (raw.isEmpty() || raw.contains("/") || raw.contains("\\")
                    || raw.equals(".") || raw.equals("..")) {
                throw new IllegalArgumentException("目录名不合法：必须是 games 下的直接子目录");
            }
            rawDir = paths.gameDir(raw);
            Path gamesDir = paths.gamesDir();
            // id 在归一化前被当作路径用来改名，必须先确认它不会越出 games：
            // 否则 "../data" 之类会把 games 外的目录搬进来。
            Path rawAbs = rawDir.toAbsolutePath().normalize();
            if (!gamesDir.toAbsolutePath().normalize().equals(rawAbs.getParent())) {
                throw new IllegalArgumentException("目录名不合法：必须是 games 下的直接子目录");
            }
            // 已合法的 id 保持原样：normalize 会把 a--b 折成 a-b 等，贸然改名会动用户的目录。
            // 合法 id 的重名交给 validator.validate 报「游戏已存在」，语义不变。
            gid = GameImportValidator.isValidId(raw)
                    ? raw
                    : GameIdNormalizer.normalizeUnique(raw, p -> {
                        Path pAbs = paths.gameDir(p).toAbsolutePath().normalize();
                        boolean dirTaken = !pAbs.equals(rawAbs) && Files.exists(pAbs);
                        return dirTaken || repo.existsById(p);
                    });

            // 扫描与槽位分配不依赖改名结果，放在改名之前：这里失败时目录还没动，
            // 用户手里的原目录名仍然有效，无需回滚。
            scan.syncAll();
            GameSlotAllocator.Slots allocated = allocator.allocate(repo.findAll());

            String exeFixed = retarget(exe, rawDir, paths.gameDir(gid));
            String altFixed = retarget(exeAlt, rawDir, paths.gameDir(gid));

            targetDir = paths.gameDir(gid);
            boolean sameDir = rawDir.toAbsolutePath().normalize()
                    .equals(targetDir.toAbsolutePath().normalize());
            if (!gid.equals(raw) && !sameDir && Files.isDirectory(rawDir, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(targetDir)) {
                    throw new IllegalArgumentException("目标目录已存在: " + gid);
                }
                Files.move(rawDir, targetDir);
                renamed = true;
                log.info("目录改名 {} -> {}", raw, gid);
            }

            GameImportValidator.Normalized req = validator.validate(gid, name, exeFixed, altFixed,
                    width, height, framerate, gameLang);
            String runId = createAndRunLocked(req, toDto(allocated), 0, renamed ? raw : null);
            handedOff = true;
            return new StartResult(runId, gid, renamed ? raw : null);
        } catch (RuntimeException e) {
            if (renamed) {
                rollbackRename(e, targetDir, rawDir, gid);
            }
            throw e;
        } catch (java.io.IOException e) {
            IllegalArgumentException wrapped = new IllegalArgumentException(
                    "目录改名失败: " + e.getMessage(), e);
            if (renamed) {
                rollbackRename(wrapped, targetDir, rawDir, gid);
            }
            throw wrapped;
        } catch (Error e) {
            // IOError 之类的 Error 也必须回滚改名（若已改名）并释放 busy
            if (renamed) {
                rollbackRename(e, targetDir, rawDir, gid);
            }
            throw e;
        } finally {
            if (!handedOff) {
                busy.set(false);
            }
        }
    }

    /**
     * 改名已生效但 run 尚未创建成功：把目录搬回原名，避免用户磁盘上的原目录名消失。
     * 回滚自身失败时抛 {@link IllegalStateException}，原异常作为 cause。
     */
    private static void rollbackRename(Throwable cause, Path targetDir, Path rawDir, String gid) {
        try {
            Files.move(targetDir, rawDir);
        } catch (java.io.IOException re) {
            cause.addSuppressed(re);
            throw new IllegalStateException(
                    "目录改名回滚失败，目录现为 games/" + gid + "，请手动改回: " + cause.getMessage(),
                    cause);
        }
    }

    /**
     * 目录改名后，前端传来的绝对路径仍指向旧目录，按前缀改写。
     * 相对路径与目录外路径原样返回，交给 {@link GameExePaths#resolve} 判。
     */
    private static String retarget(String exe, Path fromDir, Path toDir) {
        if (exe == null || exe.isBlank()) {
            return exe;
        }
        Path p = Path.of(exe.trim());
        if (!p.isAbsolute()) {
            return exe;
        }
        if (fromDir.equals(toDir)) {
            return exe;
        }
        Path absFrom = fromDir.toAbsolutePath().normalize();
        Path abs = p.toAbsolutePath().normalize();
        if (!abs.startsWith(absFrom)) {
            return exe;
        }
        return toDir.toAbsolutePath().normalize()
                .resolve(absFrom.relativize(abs)).toString();
    }

    /** 从失败步骤重建 run；只有 FAILED 可重试 */
    public String retry(String runId) {
        DeployRun old = find(runId);
        if (old.state() != DeployRun.State.FAILED) {
            throw new IllegalStateException("只有失败的接入任务可以重试");
        }
        Ctx ctx = contexts.get(runId);
        if (ctx == null) {
            throw new IllegalStateException("没有该 run 的上下文");
        }
        return createAndRun(ctx.req, ctx.slots, failedStepIndex(ctx));
    }

    private String createAndRun(GameImportValidator.Normalized req, Slots slots, int fromIndex) {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("已有接入任务在执行，请稍后");
        }
        try {
            return createAndRunLocked(req, slots, fromIndex, null);
        } catch (RuntimeException e) {
            busy.set(false);
            throw e;
        }
    }

    /** 调用方必须已持有 busy 锁 */
    private String createAndRunLocked(GameImportValidator.Normalized req, Slots slots,
                                      int fromIndex, String renamedFrom) {
        String runId = "gi-" + OffsetDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-"
                + UUID.randomUUID().toString().substring(0, 4);
        DeployRun run = new DeployRun(runId, "game-import");
        Ctx ctx = new Ctx(req, slots);
        runs.put(runId, run);
        contexts.put(runId, ctx);
        if (renamedFrom != null) {
            run.log("目录改名: " + renamedFrom + " -> " + req.id());
        }
        try {
            queue.submit(() -> {
                try {
                    execute(run, ctx, fromIndex);
                } catch (Exception e) {
                    run.error(e.getMessage() == null ? e.toString() : e.getMessage());
                    run.state(DeployRun.State.FAILED);
                } finally {
                    busy.set(false);
                }
            });
        } catch (RuntimeException e) {
            // 提交失败：清掉半成品，调用方回滚改名并释放 busy
            runs.remove(runId);
            contexts.remove(runId);
            throw e;
        }
        return runId;
    }

    private void execute(DeployRun run, Ctx ctx, int fromIndex) {
        if (ctx.cancelled) {
            // 排队期间已被取消：不要再把状态写回 RUNNING
            run.state(DeployRun.State.CANCELLED);
            return;
        }
        run.state(DeployRun.State.RUNNING);
        try {
            for (int i = 0; i < STEP_IDS.size(); i++) {
                String id = STEP_IDS.get(i);
                if (i < fromIndex) {
                    setStep(ctx, id, "SUCCESS");
                    run.log("[" + id + "] 已完成，跳过");
                    continue;
                }
                if (ctx.cancelled) {
                    throw new IllegalStateException("已取消");
                }
                step(run, ctx, id);
                switch (id) {
                    case "validate" ->
                            run.log("校验通过: id=" + ctx.req.id() + ", exe=" + ctx.req.exe());
                    case "allocate" -> run.log(String.format(
                            "分配 display=:%d port=%d card=%d slot=%d",
                            ctx.slots.displayNum(), ctx.slots.port(),
                            ctx.slots.loopbackCard(), ctx.slots.loopbackSlot()));
                    case "register" -> register(run, ctx);
                    case "wineboot" -> wineboot(run, ctx);
                    case "fonts" -> fonts(run, ctx);
                    default -> throw new IllegalStateException("未知步骤: " + id);
                }
                done(run, ctx, id);
            }
            if (ctx.cancelled) {
                run.state(DeployRun.State.CANCELLED);
                return;
            }
            run.log("接入完成");
            run.state(DeployRun.State.SUCCESS);
        } catch (Exception e) {
            String stepId = ctx.current.get();
            fail(run, ctx, stepId, e.getMessage() == null ? e.toString() : e.getMessage());
            run.state(ctx.cancelled ? DeployRun.State.CANCELLED : DeployRun.State.FAILED);
        }
    }

    private void register(DeployRun run, Ctx ctx) throws IOException {
        Game g = new Game();
        g.setId(ctx.req.id());
        g.setName(ctx.req.name());
        g.setExe(ctx.req.exe());
        g.setExeAlt(ctx.req.exeAlt().isEmpty() ? null : ctx.req.exeAlt());
        g.setWorkDir(paths.gameDir(ctx.req.id()).toString());
        g.setWinePrefix(paths.gameDir(ctx.req.id()).resolve("wineprefix").toString());
        g.setGameLang(ctx.req.gameLang());
        g.setWidth(ctx.req.width());
        g.setHeight(ctx.req.height());
        g.setDisplayNum(ctx.slots.displayNum());
        g.setPort(ctx.slots.port());
        g.setLoopbackSlot(ctx.slots.loopbackSlot());
        g.setLoopbackCard(ctx.slots.loopbackCard());
        g.setFramerate(ctx.req.framerate());
        g.setOnDisk(true);
        Instant now = Instant.now();
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        try {
            scan.writeEnv(g);
            repo.save(g);
        } catch (IOException | RuntimeException e) {
            deleteRegistration(ctx.req.id());
            throw e;
        }
        ctx.registered = true;
        run.log("已写入 " + paths.gameEnv(ctx.req.id()));
        snapshotOriginalSaves(run, ctx.req.id());
    }

    /**
     * 接入后把游戏自带的存档留一份底。
     *
     * 只做一次：重复接入（失败重试 / 回滚后重来）不再重复留底。
     * 备份失败不阻塞接入 —— 用户仍然可以手动备份。
     */
    private void snapshotOriginalSaves(DeployRun run, String id) {
        try {
            if (saves.hasOriginal(id)) {
                run.log("已有原始存档备份，跳过");
                return;
            }
            if (saves.saveDirs(id).isEmpty()) {
                run.log("未发现存档目录，跳过原始存档备份");
                return;
            }
            Path archive = saves.backup(id, "原始存档",
                    "导入游戏时自动留存的原始存档", SaveService.KIND_ORIGINAL);
            run.log("已备份原始存档: " + archive.getFileName());
        } catch (IOException | RuntimeException e) {
            run.log("WARN: 原始存档备份失败，继续: " + e.getMessage());
        }
    }

    private void wineboot(DeployRun run, Ctx ctx) throws Exception {
        Path prefix = paths.gameDir(ctx.req.id()).resolve("wineprefix");
        Files.createDirectories(prefix);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("WINEPREFIX", prefix.toString());
        env.put("WINEDEBUG", "-all");
        env.put("LANG", "C.UTF-8");
        int exit = exec.streaming(run,
                List.of("xvfb-run", "-a", "wineboot", "-u"), env, WINEBOOT_TIMEOUT);
        if (exit != 0) {
            throw new IllegalStateException(
                    "wineboot 失败（exit=" + exit + "）；请确认已安装 wine 与 xvfb-run");
        }
    }

    private void fonts(DeployRun run, Ctx ctx) throws Exception {
        Path prefix = paths.gameDir(ctx.req.id()).resolve("wineprefix");
        Path dst = prefix.resolve("drive_c/windows/Fonts");
        Path src = Path.of(fontPath);
        if (!Files.isRegularFile(src)) {
            run.log("WARN: 字体文件不存在，跳过: " + src);
        } else {
            try {
                Files.createDirectories(dst);
                Files.copy(src, dst.resolve(src.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                run.log("已安装字体: " + src.getFileName());
            } catch (IOException e) {
                run.log("WARN: 字体安装失败，继续: " + e.getMessage());
            }
        }

        // 只把字体文件拷进 Fonts 目录还不够：游戏请求的是 Tahoma / MS Shell Dlg /
        // System 这些 family 名，Wine 找不到就回退到只有拉丁字形的内置字体，中文变方框。
        // wine-fonts.sh 生成 family 名匹配的字体并写入注册表（幂等）。
        Path script = Path.of(fontScript);
        if (!Files.isRegularFile(script)) {
            run.log("WARN: 字体注册脚本不存在，跳过: " + script);
            return;
        }
        Map<String, String> env = new LinkedHashMap<>();
        env.put("WINEPREFIX", prefix.toString());
        env.put("WINEDEBUG", "-all");
        env.put("GALWORLD_ROOT", paths.root().toString());
        int exit = exec.streaming(run, List.of("bash", script.toString(), prefix.toString()),
                env, FONTS_TIMEOUT);
        if (exit != 0) {
            run.log("WARN: 字体注册脚本退出码 " + exit + "（不阻塞接入）");
        }
    }

    // ---------------- 步骤状态 ----------------

    private void step(DeployRun run, Ctx ctx, String id) {
        ctx.current.set(id);
        setStep(ctx, id, "RUNNING");
        run.event("step", stepJson(id, "RUNNING"));
    }

    private void done(DeployRun run, Ctx ctx, String id) {
        setStep(ctx, id, "SUCCESS");
        run.event("step", stepJson(id, "SUCCESS"));
    }

    private void fail(DeployRun run, Ctx ctx, String id, String message) {
        if (id != null && !id.isEmpty()) {
            setStep(ctx, id, "FAILED");
            run.event("step", stepJson(id, "FAILED"));
        }
        run.log("ERROR: " + message);
        run.error(message);
    }

    private static String stepJson(String id, String state) {
        return "{\"id\":\"" + id + "\",\"state\":\"" + state
                + "\",\"title\":\"" + STEP_TITLES.get(id) + "\"}";
    }

    private static void setStep(Ctx ctx, String id, String state) {
        for (int i = 0; i < ctx.steps.size(); i++) {
            StepState s = ctx.steps.get(i);
            if (s.id().equals(id)) {
                ctx.steps.set(i, new StepState(s.id(), s.title(), state));
                return;
            }
        }
    }

    private static int failedStepIndex(Ctx ctx) {
        for (int i = 0; i < ctx.steps.size(); i++) {
            if ("FAILED".equals(ctx.steps.get(i).state())) {
                return i;
            }
        }
        return STEP_IDS.size() - 1;
    }

    // ---------------- 查询 / 取消 / 回滚 ----------------

    public DeployRun find(String runId) {
        DeployRun run = runs.get(runId);
        if (run == null) {
            throw new IllegalArgumentException("没有这个 run: " + runId);
        }
        return run;
    }

    public RunSnapshot snapshot(String runId) {
        DeployRun run = find(runId);
        Ctx ctx = contexts.get(runId);
        List<StepState> steps = ctx == null ? List.of() : List.copyOf(ctx.steps);
        // gameId 为后端权威值：快照里带上它，前端刷新/重试后不必依赖 URL 里的旧 id
        String gameId = ctx == null ? "" : ctx.req.id();
        return new RunSnapshot(runId, run.state().name(), steps,
                List.copyOf(run.lines()), run.error(), gameId);
    }

    /** 取消：杀当前进程，随后回滚注册 */
    public void cancel(String runId) {
        DeployRun run = find(runId);
        if (run.terminal()) {
            throw new IllegalStateException("任务已结束，无法取消");
        }
        Ctx ctx = contexts.get(runId);
        if (ctx != null) {
            ctx.cancelled = true;
        }
        Process p = run.process();
        if (p != null) {
            p.destroyForcibly();
        }
        if (ctx != null && ctx.registered) {
            deleteRegistration(ctx.req.id());
            ctx.registered = false;
        }
        run.log("已取消");
        run.state(DeployRun.State.CANCELLED);
    }

    /** 删除注册（game.env + DB 行 + 停服务），可重复执行 */
    public void rollback(String runId) {
        find(runId);
        Ctx ctx = contexts.get(runId);
        if (ctx == null) {
            return;
        }
        deleteRegistration(ctx.req.id());
        ctx.registered = false;
        runs.get(runId).log("已回滚注册: " + ctx.req.id());
    }

    private void deleteRegistration(String id) {
        try {
            repo.findById(id).ifPresent(g -> {
                try {
                    sessions.stop(id, g.getPort());
                } catch (RuntimeException ignore) {
                    // 服务可能没起来
                }
                repo.delete(g);
            });
        } catch (RuntimeException e) {
            log.warn("停止服务失败 {}: {}", id, e.toString());
        }
        try {
            Files.deleteIfExists(paths.gameEnv(id));
        } catch (IOException e) {
            log.warn("删除 game.env 失败 {}: {}", id, e.toString());
        }
    }
}
