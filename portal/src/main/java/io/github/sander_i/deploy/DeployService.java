/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sander_i.config.PathConfig;
import io.github.sander_i.i18n.I18n;
import io.github.sander_i.platform.ArtifactDownloader;
import io.github.sander_i.platform.CommandRunner;
import io.github.sander_i.platform.OsKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 部署执行引擎（docs/ONE-CLICK-DEPLOY.md §7）。
 *
 * 约束：
 * - 命令只来自 {@link DeployCommands}，前端只能传步骤 id；
 * - 单线程队列，同一时刻只跑一个步骤（并发会互相破坏 apt / modprobe / 服务）；
 * - destructive 步骤需要一次性确认令牌；
 * - 全程写 data/deploy/<runId>.log 与 audit.jsonl，并通过 SSE 推送。
 */
@Service
public class DeployService {

    private static final Logger log = LoggerFactory.getLogger(DeployService.class);

    private final DeploySteps steps;
    private final DeployProbes probes;
    private final DeployCommands commands;
    private final ArtifactDownloader downloader;
    private final PathConfig paths;
    /** 从 jar 内释放 Linux 脚本与 systemd 单元 */
    private final DeployAssets assets;
    private final CommandRunner runner = new CommandRunner();
    private final ObjectMapper mapper = new ObjectMapper();

    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "galworld-deploy");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Map<String, DeployRun> runs = new ConcurrentHashMap<>();
    /** stepId → 一次性确认令牌（5 分钟有效） */
    private final Map<String, Confirm> confirms = new ConcurrentHashMap<>();
    /** stepId → 最近一次运行 id */
    private final Map<String, String> lastRunByStep = new ConcurrentHashMap<>();
    /** 成功后标记，供 requires 判断（本次进程内） */
    private final Map<String, String> succeeded = new ConcurrentHashMap<>();

    @Value("${server.port:8088}")
    private int portalPort;

    /** 是否允许页面输入 sudo 密码（默认关闭，见文档 §15） */
    @Value("${galworld.deploy.allow-sudo-password:false}")
    private boolean allowSudoPassword;

    /**
     * 固定提权入口。deploy.sh 会写到这个路径并配好 sudoers，只放行几个固定子命令；
     * 存在时 ADMIN 步骤走它，页面就不需要 sudo 密码。
     */
    @Value("${galworld.deploy.sudo-wrapper:/usr/local/sbin/galworld-sudo}")
    private String sudoWrapper;

    /** stepId → 连续密码失败次数与锁定截止时间 */
    private record SudoAttempt(int fails, long lockUntil) {
    }

    private final Map<String, SudoAttempt> sudoAttempts = new ConcurrentHashMap<>();

    public boolean allowSudoPassword() {
        return allowSudoPassword;
    }

    public DeployService(DeploySteps steps, DeployProbes probes, DeployCommands commands,
                         ArtifactDownloader downloader, PathConfig paths,
                         DeployAssets assets) {
        this.steps = steps;
        this.probes = probes;
        this.commands = commands;
        this.downloader = downloader;
        this.paths = paths;
        this.assets = assets;
    }

    // ---------------- 状态 ----------------

    public record StepStatus(String id, String title, String summary, String kind,
                             String state, String probe, String lastRunId,
                             String lastState, String error, boolean destructive,
                             List<String> requires, String successHint, boolean uninstall,
                             List<String> commands, String runAs,
                             boolean running, long lastRunAt) {
    }

    /**
     * 提权入口现状。{@code present=false} 表示没装包装脚本；{@code version != expected}
     * 表示 jar 更新后脚本过期，两者都会让 ADMIN 步骤退回需要密码的旧路径。
     */
    public Map<String, Object> elevation() {
        Path p = Path.of(sudoWrapper);
        boolean present = Files.isExecutable(p);
        Integer version = null;
        if (present) {
            try {
                java.util.List<String> lines = Files.readAllLines(p);
                for (String line : lines.subList(0, Math.min(20, lines.size()))) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("#\\s*galworld-sudo-version:\\s*(\\d+)").matcher(line);
                    if (m.find()) {
                        version = Integer.valueOf(m.group(1));
                        break;
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.debug("读取提权脚本版本失败: {}", e.toString());
            }
        }
        return Map.of(
                "path", sudoWrapper,
                "present", present,
                "version", version == null ? 0 : version,
                "expected", DeployCommands.SUDO_WRAPPER_VERSION);
    }

    public List<StepStatus> status() {
        OsKind os = OsKind.current();
        List<StepStatus> out = new ArrayList<>();
        for (DeployStep s : steps.forOs(os)) {
            ProbeState probe = probeOf(s);
            String lastRunId = lastRunByStep.getOrDefault(s.id(), "");
            DeployRun run = lastRunId.isEmpty() ? null : runs.get(lastRunId);
            String state = effectState(s, probe, run);
            out.add(new StepStatus(s.id(), I18n.t(s.title()), I18n.t(s.summary()),
                    s.kind().name(),
                    state, probe.name(), lastRunId,
                    run == null ? "" : run.state().name(),
                    run == null ? "" : run.error(), s.destructive(), s.requires(),
                    I18n.t(s.successHint()), DeploySteps.isUninstall(s), preview(s),
                    s.runAs().name(),
                    run != null && run.state() == DeployRun.State.RUNNING,
                    run == null ? 0L : run.startedAt()));
        }
        return out;
    }

    /**
     * 命令预览：给页面展示「这一步到底会执行什么」。
     *
     * 只渲染不执行，前端依旧只能传 step id。元件路径在预览期尚未下载，
     * 用 {@code <元件 id>} 占位，真正执行时才替换成下载后的绝对路径。
     */
    private List<String> preview(DeployStep s) {
        if (s.command().isEmpty()) {
            return List.of();
        }
        String token = s.command().get(0);
        String sub = s.os() == OsKind.LINUX ? commands.sudoSubcommand(token) : null;
        // 有固定提权入口时，实际执行的不是这些行，预览就直接给出入口调用
        if (s.runAs() == DeployStep.RunAs.ADMIN && sub != null) {
            return List.of("sudo -n " + sudoWrapper + " " + sub,
                    I18n.t("（具体命令固定在脚本里，页面只能触发子命令）"));
        }
        try {
            List<String> raw = commands.resolve(s.os(), token);
            Map<String, String> vars = vars(s, I18n.t("<元件 {0}>", s.artifactId()));
            String prefix = s.os() != OsKind.LINUX ? ""
                    : s.runAs() == DeployStep.RunAs.ADMIN ? "sudo -n sh -c " : "sh -c ";
            List<String> out = new ArrayList<>(raw.size());
            for (String line : raw) {
                out.add(prefix + render(line, vars));
            }
            return out;
        } catch (RuntimeException e) {
            return List.of(I18n.t("预览不可用：{0}", e.getMessage()));
        }
    }

    private ProbeState probeOf(DeployStep s) {
        try {
            return probes.probe(s.id(), Map.of());
        } catch (RuntimeException e) {
            return ProbeState.UNKNOWN;
        }
    }

    private String effectState(DeployStep s, ProbeState probe, DeployRun run) {
        if (probe == ProbeState.READY) {
            return "READY";
        }
        if (run != null && !run.terminal()) {
            return run.state().name();
        }
        if (run != null && run.state() == DeployRun.State.SUCCESS) {
            return "SUCCESS";
        }
        for (String req : s.requires()) {
            if (!succeeded.containsKey(req) && probeOfById(req) != ProbeState.READY) {
                return "BLOCKED";
            }
        }
        return "NOT_READY";
    }

    private ProbeState probeOfById(String id) {
        DeployStep s = steps.find(id);
        return s == null ? ProbeState.UNKNOWN : probeOf(s);
    }

    // ---------------- 确认令牌 ----------------

    private record Confirm(String token, long expireAt) {
    }

    /** destructive 步骤：生成一次性令牌 */
    public String confirm(String stepId) {
        DeployStep s = steps.find(stepId);
        if (s == null) {
            throw new IllegalArgumentException(I18n.t("没有这个步骤: {0}", stepId));
        }
        String token = UUID.randomUUID().toString();
        confirms.put(stepId, new Confirm(token, System.currentTimeMillis() + Duration.ofMinutes(5).toMillis()));
        return token;
    }

    /** 破坏性步骤缺少 / 使用了无效的确认令牌；控制器据此回 403 而不是 409 */
    public static class ConfirmRequiredException extends IllegalStateException {
        public ConfirmRequiredException(String message) {
            super(message);
        }
    }

    private void checkConfirm(DeployStep s, String token) {
        if (!s.destructive()) {
            return;
        }
        Confirm c = confirms.get(s.id());
        if (c == null || c.expireAt() < System.currentTimeMillis()) {
            throw new ConfirmRequiredException(I18n.t("需要确认令牌：请先调用 /confirm"));
        }
        if (token == null || !token.equals(c.token())) {
            throw new ConfirmRequiredException(I18n.t("确认令牌无效或已使用"));
        }
        confirms.remove(s.id());   // 一次性
    }

    // ---------------- 执行 ----------------

    /** @return runId */
    public String run(String stepId, String confirmToken) {
        return run(stepId, confirmToken, null);
    }

    /**
     * @param sudoPassword 一次性 sudo 密码；仅当 allow-sudo-password=true 时接受，
     *                     用完由执行线程清零，不落盘、不进日志
     */
    public String run(String stepId, String confirmToken, char[] sudoPassword) {
        DeployStep s = steps.find(stepId);
        if (s == null) {
            throw new IllegalArgumentException(I18n.t("没有这个步骤: {0}", stepId));
        }
        if (s.os() != OsKind.current()) {
            throw new IllegalStateException(I18n.t("该步骤不适用于当前平台: {0}", s.os()));
        }
        checkConfirm(s, confirmToken);
        if (sudoPassword != null) {
            if (!allowSudoPassword) {
                java.util.Arrays.fill(sudoPassword, '\0');
                throw new SecurityException(I18n.t("服务端未开启 allow-sudo-password"));
            }
            SudoAttempt a = sudoAttempts.get(stepId);
            if (a != null && a.lockUntil() > System.currentTimeMillis()) {
                java.util.Arrays.fill(sudoPassword, '\0');
                throw new IllegalStateException(I18n.t("提权密码错误次数过多，请 5 分钟后再试"));
            }
        }

        for (String req : s.requires()) {
            if (!succeeded.containsKey(req) && probeOfById(req) != ProbeState.READY) {
                throw new IllegalStateException(I18n.t("前置步骤未完成: {0}", req));
            }
        }
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException(I18n.t("已有部署任务在执行，请稍后再试"));
        }

        String runId = "r-" + OffsetDateTime.now().format(java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd-HHmmss")) + "-" + UUID.randomUUID().toString().substring(0, 4);
        DeployRun run = new DeployRun(runId, stepId);
        run.sudo(sudoPassword);
        runs.put(runId, run);
        lastRunByStep.put(stepId, runId);
        // 后台线程拿不到请求上下文：把提交时的语言固定下来，
        // 否则日志会按站点默认语言输出，和界面语言不一致。
        String locale = I18n.locale();
        queue.submit(() -> I18n.runAs(locale, () -> {
            try {
                execute(run, s);
            } finally {
                busy.set(false);
            }
        }));
        return runId;
    }

    private void execute(DeployRun run, DeployStep s) {
        run.state(DeployRun.State.RUNNING);
        Path logFile = paths.dataDir().resolve("deploy").resolve(run.id() + ".log");
        StringBuilder auditTail = new StringBuilder();
        long t0 = System.currentTimeMillis();
        try {
            Files.createDirectories(logFile.getParent());
            ProbeState probe = probeOf(s);
            run.log(I18n.t("步骤: {0} · {1}", s.id(), I18n.t(s.title())));
            run.log(I18n.t("探测: {0}", probe));
            if (probe == ProbeState.READY) {
                run.log(I18n.t("已就绪，跳过执行"));
                finish(run, DeployRun.State.SUCCESS, 0, logFile, auditTail, t0);
                return;
            }

            String artifactPath = "";
            if (s.artifactId() != null && !s.artifactId().isBlank()) {
                run.log(I18n.t("下载元件: {0}", s.artifactId()));
                artifactPath = downloader.fetch(s.artifactId(), run::log).toAbsolutePath().toString();
                run.log(I18n.t("元件已就绪: {0}", artifactPath));
            }
            if (s.kind() == DeployStep.Kind.DOWNLOAD) {
                finish(run, DeployRun.State.SUCCESS, 0, logFile, auditTail, t0);
                return;
            }

            int exit = s.os() == OsKind.WINDOWS
                    ? runWindows(run, s, artifactPath)
                    : runLinux(run, s, artifactPath);

            for (String line : run.lines()) {
                auditTail.append(line).append('\n');
            }
            if (run.state() == DeployRun.State.NEED_ELEVATION) {
                finish(run, DeployRun.State.NEED_ELEVATION, exit, logFile, auditTail, t0);
                return;
            }
            if (exit == 0) {
                succeeded.put(s.id(), run.id());
                finish(run, DeployRun.State.SUCCESS, 0, logFile, auditTail, t0);
            } else {
                run.error(I18n.t("命令退出码 {0}；{1}", exit, s.successHint()));
                finish(run, DeployRun.State.FAILED, exit, logFile, auditTail, t0);
            }
        } catch (UnsupportedOperationException e) {
            run.log(I18n.t("未实现: {0}", e.getMessage()));
            run.error(e.getMessage());
            finish(run, DeployRun.State.FAILED, -1, logFile, auditTail, t0);
        } catch (Exception e) {
            run.log(I18n.t("失败: {0}", e));
            run.error(String.valueOf(e.getMessage()));
            finish(run, DeployRun.State.FAILED, -1, logFile, auditTail, t0);
        } finally {
            // 无论成功失败都清零一次性密码
            run.clearSudo();
        }
    }

    private void finish(DeployRun run, DeployRun.State state, int exit, Path logFile,
                        StringBuilder tail, long t0) {
        run.exit(exit);
        run.log(I18n.t("结果: {0}", state + (exit >= 0 ? "（exit=" + exit + "）" : "")));
        run.state(state);
        try {
            Files.createDirectories(logFile.getParent());
            Files.write(logFile, run.lines(), StandardCharsets.UTF_8);
            Path audit = paths.dataDir().resolve("deploy").resolve("audit.jsonl");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ts", OffsetDateTime.now().toString());
            entry.put("stepId", run.stepId());
            entry.put("runId", run.id());
            entry.put("exit", exit);
            entry.put("state", state.name());
            entry.put("durationMs", System.currentTimeMillis() - t0);
            entry.put("outputTail", tail.length() > 400 ? tail.substring(tail.length() - 400) : tail.toString());
            Files.writeString(audit, mapper.writeValueAsString(entry) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("写部署审计失败: {}", e.toString());
        }
    }

    /** Linux：逐行 sh -c；ADMIN 步骤统一加 sudo -n */
    private int runLinux(DeployRun run, DeployStep s, String artifactPath) throws Exception {
        Map<String, String> vars = vars(s, artifactPath);
        // 脚本/单元类步骤：先把 jar 内资产落盘，再执行赋权与注册命令
        int installed = assets.install(tokenOf(s));
        if (installed > 0) {
            run.log(I18n.t("已从 jar 安装 {0} 个资产文件", installed));
        }
        boolean admin = s.runAs() == DeployStep.RunAs.ADMIN;
        char[] sudo = admin ? run.sudo() : null;
        // 固定提权入口优先：不需要密码，权限边界由 sudoers 里的子命令白名单兜住
        String sub = commands.sudoSubcommand(tokenOf(s));
        if (admin && sudo == null && sub != null && Files.isExecutable(Path.of(sudoWrapper))) {
            run.log(I18n.t("提权入口: {0} {1}", sudoWrapper, sub));
            int exit = execStreaming(run, List.of("sudo", "-n", sudoWrapper, sub), s.timeout(), null);
            if (exit != 0) {
                if (exit == 1
                        && run.lines().stream().anyMatch(l -> l.contains("a password is required"))) {
                    run.error(I18n.t("提权入口未生效：重新运行 deploy.sh 安装 sudoers 后重试"));
                    run.state(DeployRun.State.NEED_ELEVATION);
                }
                return exit;
            }
            return 0;
        }
        for (String raw : commands.resolve(OsKind.LINUX, tokenOf(s))) {
            String line = render(raw, vars);
            String[] argv;
            if (!admin) {
                argv = new String[]{"sh", "-c", line};
            } else if (sudo != null) {
                // 密码经 stdin 传，不出现在命令行（ps 看不到）
                argv = new String[]{"sudo", "-S", "-p", "", "sh", "-c", line};
            } else {
                argv = new String[]{"sudo", "-n", "sh", "-c", line};
            }
            int exit = execStreaming(run, List.of(argv), s.timeout(), sudo);
            if (exit != 0) {
                if (sudo != null) {
                    sudoFailed(s.id());
                } else if (exit == 1
                        && run.lines().stream().anyMatch(l -> l.contains("a password is required"))) {
                    // 有固定入口却仍要密码，多半是入口没装好或 sudoers 被改
                    run.error(commands.sudoSubcommand(tokenOf(s)) != null
                            ? I18n.t("提权入口不可用：重新运行 deploy.sh 安装 sudoers（或加 --no-sudoers 后在页面输密码）")
                            : I18n.t("需要提权：请在页面输入 sudo 密码，或先执行 sudo -v"));
                    run.state(DeployRun.State.NEED_ELEVATION);
                }
                return exit;
            }
        }
        if (sudo != null) {
            sudoSucceeded(s.id());
        }
        return 0;
    }

    private void sudoFailed(String stepId) {
        long now = System.currentTimeMillis();
        SudoAttempt old = sudoAttempts.get(stepId);
        int fails = (old == null ? 0 : old.fails()) + 1;
        long lockUntil = fails >= 3 ? now + Duration.ofMinutes(5).toMillis() : 0;
        sudoAttempts.put(stepId, new SudoAttempt(fails, lockUntil));
    }

    private void sudoSucceeded(String stepId) {
        sudoAttempts.remove(stepId);
    }

    /** Windows：写 .ps1 后 powershell -File 执行 */
    private int runWindows(DeployRun run, DeployStep s, String artifactPath) throws Exception {
        Map<String, String> vars = vars(s, artifactPath);
        StringBuilder ps = new StringBuilder();
        // Windows PowerShell 5.1 默认按 ANSI 读取 .ps1：必须带 UTF-8 BOM，中文脚本才不会乱码报错
        ps.append("﻿");
        ps.append("$ErrorActionPreference = 'Stop'\n");
        // 让输出按 UTF-8 编码，后端按 UTF-8 读流
        ps.append("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\n");
        for (String raw : commands.resolve(OsKind.WINDOWS, tokenOf(s))) {
            ps.append(render(raw, vars)).append('\n');
        }
        Path script = paths.dataDir().resolve("deploy").resolve(run.id() + ".ps1");
        Files.createDirectories(script.getParent());
        Files.writeString(script, ps.toString(), StandardCharsets.UTF_8);
        return execStreaming(run,
                List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString()),
                s.timeout(), null);
    }

    /** 执行并逐行推送输出；超时或取消时强杀 */
    private int execStreaming(DeployRun run, List<String> argv, Duration timeout, char[] secret)
            throws Exception {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        run.process(p);
        run.log("$ " + String.join(" ", argv));
        // sudo -S：密码走 stdin，命令行里看不到；写完立即关闭流
        if (secret != null && argv.contains("-S")) {
            try (var stdin = p.getOutputStream()) {
                stdin.write(new String(secret).getBytes(StandardCharsets.UTF_8));
                stdin.write('\n');
                stdin.flush();
            } catch (IOException ignore) {
                // 进程已退出
            }
        }
        String mask = secret == null ? null : new String(secret);
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    run.log(maskSecret(line, mask));
                }
            } catch (IOException ignore) {
                // 进程被杀时流会断
            }
        }, "deploy-stream");
        reader.setDaemon(true);
        reader.start();
        boolean done = p.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        if (!done) {
            run.log(I18n.t("超时（{0}s），终止进程", timeout.toSeconds()));
            p.destroyForcibly();
        }
        reader.join(2000);
        return done ? p.exitValue() : -1;
    }

    static String maskSecret(String line, String secret) {
        if (secret == null || secret.isEmpty() || line == null) {
            return line;
        }
        return line.contains(secret) ? line.replace(secret, "***") : line;
    }

    private String tokenOf(DeployStep s) {
        if (s.command().isEmpty()) {
            throw new UnsupportedOperationException(I18n.t("步骤没有命令: {0}", s.id()));
        }
        return s.command().get(0);
    }

    /** 占位符白名单；未知占位符直接报错，避免注入 */
    private Map<String, String> vars(DeployStep s, String artifactPath) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("root", paths.root().toString());
        v.put("dataDir", paths.dataDir().toString());
        v.put("portalPort", String.valueOf(portalPort));
        v.put("user", System.getProperty("user.name", ""));
        if (s.artifactId() != null && !s.artifactId().isBlank()) {
            v.put("artifact:" + s.artifactId(), artifactPath);
        }
        return v;
    }

    static String render(String template, Map<String, String> vars) {
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue());
        }
        if (out.matches("(?s).*\\$\\{[^}]+}.*")) {
            throw new IllegalArgumentException(I18n.t("未知占位符: {0}", template));
        }
        return out;
    }

    // ---------------- 查询 / 取消 ----------------

    public DeployRun find(String runId) {
        return runs.get(runId);
    }

    public void cancel(String runId) {
        DeployRun run = runs.get(runId);
        if (run == null) {
            throw new IllegalArgumentException(I18n.t("没有这个 run: {0}", runId));
        }
        Process p = run.process();
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
        run.error(I18n.t("已取消"));
        run.state(DeployRun.State.CANCELLED);
    }
}
