/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.update;

import com.galworld.config.PathConfig;
import com.galworld.platform.OsKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * 更新器进程的启动方式按平台分派。
 *
 * <p>Linux 用 {@code systemd-run --user} 让更新器脱离 Portal 存活，启动命令 10 秒内必须退出；
 * Windows 的更新器是长任务（要停掉 Portal 再把它拉起来），只能探测启动是否立即失败，
 * 不能等待结束——否则会把正在干活的更新器杀掉。
 */
@Component
public class UpdaterLauncher {

    static final String WINDOWS_SCRIPT_RESOURCE = "deploy/windows/update-portal.ps1";
    /** Windows 启动探测窗口：这段时间内退出且非 0 才算启动失败。 */
    private static final long WINDOWS_PROBE_SECONDS = 3;

    private final PathConfig paths;
    private final OsKind os;
    private final int port;
    private final String scheme;

    /**
     * Spring 要注入这个构造器。类里还有一个包可见的测试构造器，
     * 不显式标注 @Autowired 时 Spring 6.1 会按「无默认构造器」处理并启动失败。
     */
    @Autowired
    public UpdaterLauncher(PathConfig paths,
                           @Value("${server.port:8088}") int port,
                           @Value("${server.ssl.enabled:false}") boolean tls) {
        this(paths, OsKind.current(), port, tls);
    }

    UpdaterLauncher(PathConfig paths, OsKind os, int port, boolean tls) {
        this.paths = paths;
        this.os = os;
        this.port = port;
        this.scheme = tls ? "https" : "http";
    }

    public boolean supported() {
        return os == OsKind.LINUX || os == OsKind.WINDOWS;
    }

    /** 启动更新器并返回 PID。 */
    public long launch(String jobId) throws IOException {
        if (!supported()) {
            throw new UnsupportedOperationException("当前平台不支持远程更新: " + os);
        }
        Process p = builder(jobId).start();
        try {
            if (os == OsKind.LINUX) {
                if (!p.waitFor(10, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IllegalStateException("启动更新器超时");
                }
                if (p.exitValue() != 0) {
                    throw new IllegalStateException("systemd-run 启动失败，exit=" + p.exitValue());
                }
            } else if (p.waitFor(WINDOWS_PROBE_SECONDS, TimeUnit.SECONDS) && p.exitValue() != 0) {
                throw new IllegalStateException("更新器启动失败，exit=" + p.exitValue()
                        + "，详见 " + launchLog());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("启动更新器被中断", e);
        }
        return p.pid();
    }

    /** 包可见：测试只验证命令行，不真正拉起进程。 */
    ProcessBuilder builder(String jobId) throws IOException {
        if (os == OsKind.WINDOWS) {
            Path script = extractWindowsScript();
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-WindowStyle", "Hidden",
                    "-File", script.toString(),
                    "-Root", paths.root().toString(),
                    "-Port", String.valueOf(port),
                    "-Scheme", scheme);
            pb.directory(paths.root().toFile());
            Path log = launchLog();
            Files.createDirectories(log.getParent());
            return pb.redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        }
        if (os == OsKind.LINUX) {
            Path script = paths.root().resolve("bin/updater.sh");
            if (!Files.isRegularFile(script)) {
                throw new IllegalStateException("更新器不存在: " + script);
            }
            return new ProcessBuilder("systemd-run", "--user", "--collect",
                    "--unit=galworld-updater-" + jobId.replace("upd-", ""),
                    "/bin/bash", script.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        throw new UnsupportedOperationException("当前平台不支持远程更新: " + os);
    }

    /** 把 jar 内的更新器释放到 data/update/，顺带实现更新器自升级。 */
    Path extractWindowsScript() throws IOException {
        Path dir = paths.dataDir().resolve("update");
        Files.createDirectories(dir);
        Path target = dir.resolve("update-portal.ps1");
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream(WINDOWS_SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new IOException("jar 内缺少资源: " + WINDOWS_SCRIPT_RESOURCE);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    Path launchLog() {
        return paths.dataDir().resolve("update-launch.log");
    }
}
