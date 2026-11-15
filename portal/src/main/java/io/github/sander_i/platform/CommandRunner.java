/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 带超时的外部命令执行器，供环境探测与会话管理共用。
 *
 * 所有方法都不抛异常：失败时返回空串 / false，调用方按「未就绪」处理。
 */
@Component
public class CommandRunner {

    private static final Logger log = LoggerFactory.getLogger(CommandRunner.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    public record Result(int exit, String output) {
        public boolean ok() {
            return exit == 0;
        }
    }

    public Result exec(List<String> cmd) {
        return exec(cmd, DEFAULT_TIMEOUT);
    }

    public Result exec(List<String> cmd, Duration timeout) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            long millis = Math.max(1, timeout.toMillis());
            if (!p.waitFor(millis, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                log.debug("命令超时: {}", cmd);
                return new Result(-1, "");
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Result(p.exitValue(), out);
        } catch (IOException e) {
            log.debug("命令执行失败 {}: {}", cmd, e.toString());
            return new Result(-1, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) {
                p.destroyForcibly();
            }
            return new Result(-1, "");
        }
    }

    public Result exec(String... cmd) {
        return exec(List.of(cmd));
    }

    public boolean ok(String... cmd) {
        return exec(cmd).ok();
    }

    public String output(String... cmd) {
        return exec(cmd).output().strip();
    }

    /** 本机端口是否可连接；用于判断串流 / Web UI 是否在监听。 */
    public boolean portOpen(int port) {
        return portOpen("127.0.0.1", port, 300);
    }

    public boolean portOpen(String host, int port, int timeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
