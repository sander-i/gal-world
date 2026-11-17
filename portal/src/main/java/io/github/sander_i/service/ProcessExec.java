/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.i18n.I18n;
import io.github.sander_i.deploy.DeployRun;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 流式命令执行：逐行回传日志，超时强杀；进程句柄挂在 run 上供取消使用。 */
@Component
public class ProcessExec implements GameImportService.Exec {

    @Override
    public int streaming(DeployRun run, List<String> argv, Map<String, String> env,
                         Duration timeout) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        if (env != null) {
            pb.environment().putAll(env);
        }
        Process p = pb.start();
        run.process(p);
        run.log("$ " + String.join(" ", argv));
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    run.log(line);
                }
            } catch (IOException ignore) {
                // 进程被杀时流会断
            }
        }, "game-import-stream");
        reader.setDaemon(true);
        reader.start();
        boolean finished = p.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        if (!finished) {
            run.log(I18n.t("超时（{0}s），终止进程", timeout.toSeconds()));
            p.destroyForcibly();
        }
        reader.join(2000);
        return finished ? p.exitValue() : -1;
    }
}
