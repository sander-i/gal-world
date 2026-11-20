/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * JDK 在线升级后的自愈。
 *
 * <p>问题：`unattended-upgrades` 会在门户运行中把 `openjdk-21-jre-headless`
 * 换成新版本。进程内存里还是旧 JDK 的镜像，但磁盘上的 `bin/java`、
 * `lib/jspawnhelper` 已被替换成新版本。此后 Java 每次 `fork()` 子进程
 * （`ProcessBuilder` / `Runtime.exec`）都会失败：
 *
 * <pre>
 * Incorrect Java version: 21.0.13.0.3+1-1~24.04.1
 * jspawnhelper version 21.0.13.1+2-1~24.04.1-24.04.1
 * </pre>
 *
 * <p>后果是门户所有依赖子进程的功能静默失效 —— 最典型的是
 * `SystemdSessionManager.activeStates()` 调不到 `systemctl`，
 * 会话状态永远返回 `UNKNOWN`，页面启动进度条卡在 55%。
 * 因为门户本身没崩（HTTP 正常、日志只有 stderr 噪音），
 * 这个故障可以潜伏几小时不被发现（2026-11-20 实测：09:40 启动，
 * 09:51 被升级，14:19 才由用户发现）。
 *
 * <p>`needrestart` 在 unattended 模式下配置是 `restart = 'i'`（交互），
 * 不会自动重启守护进程，所以只能由进程自己发现并退出，
 * 靠 unit 的 `Restart=on-failure` 拉起一个新的、与磁盘 JDK 一致的进程。
 *
 * <p>判定方式：启动时记下 `java.home` 目录的 mtime，之后定期比对。
 * 升级会重写该目录下的文件，mtime 随之变化。不用版本号字符串比较，
 * 因为安全补丁可能只改 build 号，也可能同版本重装。
 */
@Service
public class JdkDriftWatchdog {

    private static final Logger log = LoggerFactory.getLogger(JdkDriftWatchdog.class);

    /** 检测间隔。升级窗口通常只有几十秒，1 分钟足够及时发现又不浪费资源。 */
    private static final long CHECK_INTERVAL_MS = 60_000;

    private final Path javaHome;
    private final long initialMtime;
    private final boolean enabled;

    public JdkDriftWatchdog(
            @Value("${galworld.jdk-watchdog-enabled:true}") boolean enabled) {
        this.enabled = enabled;
        this.javaHome = Path.of(System.getProperty("java.home", ""));
        this.initialMtime = mtime(javaHome);
    }

    /**
     * 定期检查 JDK 目录是否被替换。
     *
     * <p>命中后调用 {@link System#exit}：不能用 {@code SpringApplication.exit}，
     * 那会走正常关闭路径、把退出码置 0，而 `Restart=on-failure` 只认非 0。
     * 退出码用 1（on-failure 触发条件之一）。
     *
     * <p>不在这里主动停掉游戏会话：会话是独立 systemd 单元，
     * 门户重启不影响它们（`AGENTS.md` 铁律）。
     */
    @Scheduled(fixedDelay = CHECK_INTERVAL_MS, initialDelay = CHECK_INTERVAL_MS)
    public void check() {
        if (!enabled) {
            return;
        }
        long now = mtime(javaHome);
        if (!drifted(initialMtime, now)) {
            return;
        }
        log.error("检测到 JDK 在运行中被替换（java.home={}，启动时 mtime={}，当前 mtime={}）；"
                        + "继续运行会导致所有子进程调用失败（systemctl 状态永远是 UNKNOWN）。"
                        + "退出进程让 systemd 用新 JDK 重启门户。",
                javaHome, initialMtime, now);
        // 给日志一点时间落盘，再退出
        try {
            Thread.sleep(200);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        System.exit(1);
    }

    /**
     * 是否发生了 JDK 漂移。
     *
     * <p>任一侧读不到（≤0）时一律判「未漂移」：目录被删、权限变化都不该误杀进程，
     * 宁可漏报一次也不能在正常运行时随机退出。
     */
    static boolean drifted(long initialMtime, long currentMtime) {
        return initialMtime > 0 && currentMtime > 0 && initialMtime != currentMtime;
    }

    /**
     * `java.home` 目录的 mtime（epoch 毫秒）。读不到返回 -1。
     *
     * <p>用目录本身而不是 `bin/java` 文件：apt 升级是「解包新版本到临时目录
     * 再改名」，`bin/java` 可能被替换成新 inode 而路径不变；
     * 目录 mtime 在文件增删改时都会更新，覆盖面更广。
     */
    private static long mtime(Path dir) {
        if (dir == null || dir.toString().isEmpty()) {
            return -1;
        }
        try {
            return Files.getLastModifiedTime(dir, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    /** 供测试与诊断接口读取当前 JDK 路径 */
    public String javaHomePath() {
        return javaHome.toString();
    }
}
