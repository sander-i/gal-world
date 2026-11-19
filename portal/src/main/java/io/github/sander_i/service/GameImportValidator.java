/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.i18n.I18n;
import io.github.sander_i.repo.GameRepository;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** 接入参数校验。所有拒绝都抛 IllegalArgumentException，由 controller 转 400。 */
@Component
public class GameImportValidator {

    public record Normalized(String id, String name, String exe, String exeAlt,
                             int width, int height, String framerate, String gameLang) {
    }

    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,31}$");

    /**
     * 是否为合法游戏 id；与 {@link #validate} 用同一条 ID 正则，避免两套标准漂移。
     *
     * <p>不做 trim：调用方拿到的目录名首尾带空格时，必须按「不合法 → 归一化改名」处理，
     * 与 {@code GameImportService.start()} 用原始目录名查路径的行为保持一致。
     * {@link #validate} 内部自己 trim，不受影响。
     */
    public static boolean isValidId(String id) {
        return ID.matcher(id == null ? "" : id).matches();
    }
    /**
     * Selkies 的 FRAMERATE 语法：`60`（固定）、`30-60`（范围）、`60,30-60`（初始值 + 范围）。
     *
     * 第三种是 Ctrl 快速跳过对话这类高速场景需要的：门户默认锁 `30-30`，
     * 客户端想降帧时会被钳回去（日志里是 "Client value for 'framerate' (10.0)
     * was clamped to 30 (server range: 30-30)"）。给个范围让客户端自己选，
     * 同时用初始值保证首帧就是高帧率。
     */
    private static final Pattern FRAMERATE =
            Pattern.compile("^\\d{1,3}(-\\d{1,3})?(,\\d{1,3}(-\\d{1,3})?)?$");
    /** 允许的 GAME_LANG：空 = 默认 C.UTF-8；zh_CN 用于 GBK/CP936 汉化补丁 */
    private static final java.util.Set<String> LANGS =
            java.util.Set.of("", "zh_CN.UTF-8");

    private final PathConfig paths;
    private final GameRepository repo;

    public GameImportValidator(PathConfig paths, GameRepository repo) {
        this.paths = paths;
        this.repo = repo;
    }

    /** 兼容旧调用：不带备用 exe */
    public Normalized validate(String id, String name, String exeRel,
                               Integer width, Integer height, String framerate) {
        return validate(id, name, exeRel, null, width, height, framerate);
    }

    public Normalized validate(String id, String name, String exeRel, String exeAltRel,
                               Integer width, Integer height, String framerate) {
        return validate(id, name, exeRel, exeAltRel, width, height, framerate, null);
    }

    public Normalized validate(String id, String name, String exeRel, String exeAltRel,
                               Integer width, Integer height, String framerate, String gameLang) {
        String gid = id == null ? "" : id.trim();
        if (!ID.matcher(gid).matches()) {
            throw new IllegalArgumentException(
                    I18n.t("目录名不合法：只能用小写字母、数字、点、下划线、连字符，2–32 位"));
        }
        if (repo.existsById(gid)) {
            throw new IllegalArgumentException(I18n.t("游戏已存在: {0}", gid));
        }
        Path gameDir = paths.gameDir(gid);
        if (!Files.isDirectory(gameDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(I18n.t("目录不存在: {0}", gid));
        }
        if (Files.exists(gameDir.resolve("game.env"))) {
            throw new IllegalArgumentException(I18n.t("该目录已注册 game.env: {0}", gid));
        }
        String exe = GameExePaths.resolve(gameDir, exeRel);
        String alt = "";
        if (exeAltRel != null && !exeAltRel.isBlank()) {
            alt = GameExePaths.resolve(gameDir, exeAltRel);
            if (alt.equals(exe)) {
                throw new IllegalArgumentException(I18n.t("备用启动程序不能与当前启动程序相同"));
            }
        }
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 120) {
            throw new IllegalArgumentException(I18n.t("显示名必填，且不超过 120 字"));
        }
        int w = width == null ? 1280 : width;
        int h = height == null ? 720 : height;
        if (w < 640 || w > 3840 || h < 480 || h > 2160) {
            throw new IllegalArgumentException(I18n.t("分辨率超出范围"));
        }
        String fr = framerate == null || framerate.isBlank() ? "30" : framerate.trim();
        if (!FRAMERATE.matcher(fr).matches()) {
            throw new IllegalArgumentException(I18n.t("帧率格式应为 30、30-60 或 60,30-60"));
        }
        String lang = gameLang == null ? "" : gameLang.trim();
        if (!LANGS.contains(lang)) {
            throw new IllegalArgumentException(I18n.t("GAME_LANG 只支持空或 zh_CN.UTF-8"));
        }
        return new Normalized(gid, n, exe, alt, w, h, fr, lang);
    }
}
