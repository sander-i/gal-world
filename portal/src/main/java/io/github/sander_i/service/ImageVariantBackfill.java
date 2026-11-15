/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.config.PathConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 存量图片回填派生图（文档 §3.8）。
 *
 * 启动后以及「图片派生图开关被打开」时，在后台单线程顺序扫描所有 games/&lt;id&gt;.meta/media/，
 * 给还没有派生图的原图补生成；开关关闭时任务会在当前文件处理完后中止（已生成的派生图保留）。
 * 幂等：已有 .s640 / .s1280 的文件直接跳过，中断后重启会自然续跑；
 * state 文件仅用于记录进度，便于观察。任何异常都只记日志，绝不阻断应用启动。
 */
@Component
@Order(100)
public class ImageVariantBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ImageVariantBackfill.class);

    /** 原图文件名：cover-1.jpg / cg-2.png（派生图 cover-1.jpg.s640 不会命中） */
    private static final Pattern ORIGINAL =
            Pattern.compile("^(cover|cg)-\\d+\\.(png|jpg|jpeg)$", Pattern.CASE_INSENSITIVE);
    /** 每处理这么多张写一次进度 */
    private static final int STATE_EVERY = 50;

    private final PathConfig paths;
    private final SettingsService settings;
    private final ImageVariantService variants;
    /** 是否有任务在跑：同一时刻只允许一个回填任务 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 运行期间收到的触发被合并到这里，当前任务结束后补跑一次 */
    private final AtomicBoolean rerunRequested = new AtomicBoolean(false);
    /** 后台线程：不占用启动线程，也不与请求线程争抢 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "image-variant-backfill");
        t.setDaemon(true);
        return t;
    });

    public ImageVariantBackfill(PathConfig paths, SettingsService settings,
                                ImageVariantService variants) {
        this.paths = paths;
        this.settings = settings;
        this.variants = variants;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 启动只投递任务，不再同步跑完整扫描
        triggerAsync("启动");
    }

    /** 投递一次后台回填；已有任务在跑时直接跳过 */
    public void triggerAsync(String reason) {
        if (!running.compareAndSet(false, true)) {
            // 已有任务在跑：记下这次触发，等当前任务结束后补跑
            rerunRequested.set(true);
            log.debug("存量派生图回填已在运行，本次触发合并为结束后补跑（{}）", reason);
            return;
        }
        log.info("已投递存量派生图回填任务（{}）", reason);
        try {
            executor.submit(this::scanOnce);
        } catch (RuntimeException e) {
            // 线程池已关闭等异常：放开标记，避免后续再也触发不了
            running.set(false);
            log.warn("投递存量派生图回填任务失败: {}", e.toString());
        }
    }

    /** 开关被打开：后台补生成派生图 */
    @EventListener
    public void onEnabled(ImageVariantEnabledEvent e) {
        if (e.enabled()) {
            triggerAsync("开关开启");
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 实际扫描一遍所有原图；整个过程都持有 running 标记 */
    private void scanOnce() {
        try {
            if (!settings.imageVariantEnabled()) {
                log.info("图片派生图开关已关闭，跳过存量回填");
                return;
            }
            Path gamesDir = paths.gamesDir();
            if (!Files.isDirectory(gamesDir)) {
                return;
            }
            Path stateFile = paths.dataDir().resolve("portal/variant-backfill.state");
            long processed = readProcessed(stateFile);
            List<Path> originals = collectOriginals(gamesDir);
            log.info("存量派生图回填开始，待检查 {} 张", originals.size());

            int generated = 0;
            for (Path original : originals) {
                // 每张之前检查一次开关，关闭后立刻停下（已生成的派生图保留）
                if (!settings.imageVariantEnabled()) {
                    log.info("回填中止：开关已关闭");
                    break;
                }
                processed++;
                try {
                    if (!hasVariant(original) && variants.generate(original)) {
                        generated++;
                    }
                } catch (Exception e) {
                    // generate() 内部已吞异常，这里兜底文件系统错误
                    log.warn("回填派生图失败 {}: {}", original, e.toString());
                }
                if (processed % STATE_EVERY == 0) {
                    writeState(stateFile, processed, original);
                }
            }
            writeState(stateFile, processed, originals.isEmpty()
                    ? null : originals.get(originals.size() - 1));
            log.info("存量派生图回填结束，本次新增 {} 张", generated);
        } catch (Exception e) {
            log.warn("存量派生图回填中止: {}", e.toString());
        } finally {
            // 先放开标记再重投，否则 compareAndSet 必然失败
            running.set(false);
            if (rerunRequested.getAndSet(false) && settings.imageVariantEnabled()) {
                triggerAsync("补跑");
            }
        }
    }

    /** 收集所有 *.meta/media/ 下的 cover/cg 原图 */
    private List<Path> collectOriginals(Path gamesDir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (var metas = Files.list(gamesDir)) {
            for (Path meta : metas.filter(p -> Files.isDirectory(p)
                    && p.getFileName().toString().endsWith(".meta")).sorted().toList()) {
                Path mediaDir = meta.resolve("media");
                if (!Files.isDirectory(mediaDir)) {
                    continue;
                }
                try (var files = Files.list(mediaDir)) {
                    out.addAll(files.filter(p -> ORIGINAL.matcher(
                                    p.getFileName().toString()).matches())
                            .sorted().toList());
                }
            }
        }
        return out;
    }

    /** 任一派生图已存在即认为该原图已处理过 */
    private boolean hasVariant(Path original) {
        return Files.isRegularFile(variants.variantPath(original, 640))
                || Files.isRegularFile(variants.variantPath(original, 1280));
    }

    /** state 内容：processed=N（累计检查张数）+ last=最后处理的文件名 */
    private long readProcessed(Path stateFile) {
        try {
            if (!Files.isRegularFile(stateFile)) {
                return 0;
            }
            for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
                if (line.startsWith("processed=")) {
                    return Long.parseLong(line.substring("processed=".length()).trim());
                }
            }
        } catch (Exception e) {
            log.debug("读取回填进度失败，按 0 处理: {}", e.toString());
        }
        return 0;
    }

    private void writeState(Path stateFile, long processed, Path last) {
        try {
            Files.createDirectories(stateFile.getParent());
            String body = "processed=" + processed + "\nlast="
                    + (last == null ? "" : last.getFileName()) + "\n";
            Files.writeString(stateFile, body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("写入回填进度失败: {}", e.toString());
        }
    }
}
