/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sander_i.config.PathConfig;
import io.github.sander_i.domain.Game;
import io.github.sander_i.repo.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 存档备份 / 切换（恢复）。
 *
 * 设计原则（防把游戏搞坏）:
 *  1. 备份只读源目录，绝不改动游戏存档本身
 *  2. 切换（恢复）前自动对**当前**存档再做一次 pre-restore 快照，恢复错了能回退
 *  3. 恢复前必须确认会话已停止 —— 游戏在跑时写盘会与解压冲突
 *  4. 解压前校验归档成员，拒绝绝对路径与 ".."，防止写到游戏目录外
 *  5. 恢复采用「先解到临时目录 → 校验 → 覆盖」，解压失败不会留下半截存档
 *
 * 每份备份的标签、描述、类型写在同名 sidecar（`<archive>.tar.gz.json`）里。
 * 老备份没有 sidecar，仍从文件名解析，不影响列表与切换。
 */
@Service
public class SaveService {

    private static final Logger log = LoggerFactory.getLogger(SaveService.class);
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    /** 手动备份 */
    public static final String KIND_MANUAL = "MANUAL";
    /** 导入游戏时自动留存的原始存档 */
    public static final String KIND_ORIGINAL = "ORIGINAL";
    /** 切换 / 恢复前自动快照 */
    public static final String KIND_PRE_RESTORE = "PRE_RESTORE";

    private static final List<String> KINDS =
            List.of(KIND_MANUAL, KIND_ORIGINAL, KIND_PRE_RESTORE);

    /** 约定的存档目录名，存在几个就备份几个 */
    private static final List<String> SAVE_DIR_NAMES =
            List.of("savedata_cn", "savedata_jp", "savedata", "save");

    private final PathConfig paths;
    private final GameRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    public SaveService(PathConfig paths, GameRepository repo) {
        this.paths = paths;
        this.repo = repo;
    }

    public record SaveArchive(String name, String label, String description, String kind,
                              long sizeBytes, Instant createdAt) {
    }

    /** sidecar 内容；字段直接对应 JSON，不用 Map 是为了类型稳定 */
    public static class ArchiveMeta {
        public String label = "";
        public String description = "";
        public String kind = KIND_MANUAL;
        public String createdAt = "";
    }

    /** 游戏里实际存在的存档目录 */
    /**
     * 游戏里实际存在的存档条目。
     *
     * 存档有两种形态：**目录**（如 savedata/、SteamEmu/Saves/）和**单个文件**
     * （如某个 .ksd / .sav）。两者都要支持，不能只认目录。
     */
    public List<Path> saveDirs(String gameId) {
        Path work = paths.gameDir(gameId);
        List<Path> items = new ArrayList<>();
        for (String name : resolveSaveDirNames(gameId)) {
            Path p = work.resolve(name);
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                items.add(p);
            }
        }
        return items;
    }

    /** 游戏目录本身，供控制器计算存档目录的相对展示名 */
    public Path gameDirOf(String gameId) {
        return paths.gameDir(gameId);
    }

    /** 生效的存档目录名（相对游戏目录）：用户配置优先，未配置时用默认约定 */
    public List<String> saveDirNames(String gameId) {
        return resolveSaveDirNames(gameId);
    }

    /** 设置存档目录名（相对游戏目录，逗号分隔）。空列表则清除配置，回到默认约定 */
    public List<String> setSaveDirs(String gameId, List<String> dirs) {
        Game game = repo.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("没有这个游戏: " + gameId));
        Path root = paths.gameDir(gameId).toAbsolutePath().normalize();
        String normalized = dirs == null ? "" : String.join(",", dirs.stream()
                .map(SaveService::normalizeRelDir)
                .filter(s -> !s.isEmpty())
                .filter(s -> insideGameDir(root, s))
                .distinct()
                .toList());
        game.setSaveDirs(normalized.isEmpty() ? null : normalized);
        repo.save(game);
        return resolveSaveDirNames(gameId);
    }

    /**
     * 归一化用户给的相对目录：允许子目录（如 {@code SteamEmu/Saves}），
     * 但拒绝绝对路径与任何 {@code ..} 片段。
     */
    private static String normalizeRelDir(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replace('\\', '/');
        if (s.isEmpty() || s.startsWith("/")) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String p : s.split("/")) {
            if (p.isEmpty() || ".".equals(p) || "..".equals(p)) {
                return "";
            }
            parts.add(p);
        }
        return String.join("/", parts);
    }

    /** 解析后必须仍在游戏目录内，挡住符号链接与拼接出来的越界路径 */
    private static boolean insideGameDir(Path root, String rel) {
        Path p = root.resolve(rel).normalize();
        return p.startsWith(root) && !p.equals(root);
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    /** 浏览游戏目录下的内容，供「手动选择存档目录」用。relPath 为空时列游戏根目录 */
    public Map<String, Object> listFiles(String gameId, String relPath) throws IOException {
        Path root = paths.gameDir(gameId).toAbsolutePath().normalize();
        Path target = root.resolve(relPath == null ? "" : relPath).normalize();
        if (!target.startsWith(root) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目录不存在: " + relPath);
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        try (Stream<Path> s = Files.list(target)) {
            for (Path p : s.sorted().toList()) {
                entries.add(Map.of(
                        "name", p.getFileName().toString(),
                        "dir", Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS),
                        "size", sizeOf(p)
                ));
            }
        }
        return Map.of("path", relPath == null ? "" : relPath, "entries", entries);
    }

    private List<String> resolveSaveDirNames(String gameId) {
        String configured = repo == null ? null
                : repo.findById(gameId).map(Game::getSaveDirs).orElse(null);        if (configured == null || configured.isBlank()) {
            return SAVE_DIR_NAMES;
        }
        List<String> names = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return names.isEmpty() ? SAVE_DIR_NAMES : names;
    }

    public Path backupRoot(String gameId) {
        return paths.dataDir().resolve("backups").resolve("saves").resolve(gameId);
    }

    /** 创建备份，返回归档路径。label 会做文件名净化。 */
    public Path backup(String gameId, String label) throws IOException {
        return backup(gameId, label, "", KIND_MANUAL);
    }

    /** 创建备份并写入标签 / 描述 / 类型。 */
    public synchronized Path backup(String gameId, String label, String description, String kind)
            throws IOException {
        List<Path> dirs = saveDirs(gameId);
        if (dirs.isEmpty()) {
            throw new IllegalStateException("游戏目录下没有找到存档目录（" + String.join("/", SAVE_DIR_NAMES) + "）");
        }
        Path root = backupRoot(gameId);
        Files.createDirectories(root);

        String safeKind = normalizeKind(kind);
        String safeLabel = sanitizeLabel(label);
        String base = TS.format(Instant.now());
        if (!safeLabel.isEmpty()) {
            base = base + "_" + safeLabel;
        }
        Path archive = root.resolve(base + ".tar.gz");
        // 同一秒内多次备份会重名，追加序号避免覆盖已有归档
        int n = 1;
        while (Files.exists(archive)) {
            archive = root.resolve(base + "-" + n + ".tar.gz");
            n++;
        }

        // tar -czf <archive> -C <gameDir> <dir1> <dir2> ...
        Path gameDir = paths.gameDir(gameId).toAbsolutePath().normalize();
        List<String> cmd = new ArrayList<>();
        cmd.add("tar");
        cmd.add("-czf");
        cmd.add(archive.toString());
        cmd.add("-C");
        cmd.add(gameDir.toString());
        for (Path d : dirs) {
            // 必须用相对游戏目录的完整路径：嵌套存档目录（SteamEmu/Saves）只取末段
            // 会变成 Saves，tar 找不到就整个备份失败
            Path abs = d.toAbsolutePath().normalize();
            if (!abs.startsWith(gameDir)) {
                continue;
            }
            cmd.add(gameDir.relativize(abs).toString().replace('\\', '/'));
        }
        runTar(cmd, "备份失败");

        ArchiveMeta meta = new ArchiveMeta();
        meta.label = displayLabel(label);
        meta.description = sanitizeDescription(description);
        meta.kind = safeKind;
        meta.createdAt = Instant.now().toString();
        try {
            writeMeta(archive, meta);
        } catch (IOException e) {
            // 元数据写失败不影响备份本身，列表会退回从文件名解析
            log.warn("写入存档元数据失败 {}: {}", archive, e.toString());
        }
        log.info("存档已备份 {} -> {}", gameId, archive);
        return archive;
    }

    /** 修改备份的标签 / 描述；类型与创建时间保持不变 */
    public SaveArchive updateMeta(String gameId, String archiveName,
                                  String label, String description) throws IOException {
        Path archive = resolveArchive(backupRoot(gameId), archiveName);
        if (!Files.isRegularFile(archive)) {
            throw new IllegalArgumentException("备份不存在: " + archiveName);
        }
        ArchiveMeta meta = readMeta(archive);
        if (meta == null) {
            // 老备份：从文件名补齐，保证写入后列表字段稳定
            SaveArchive old = describe(archive);
            meta = new ArchiveMeta();
            meta.kind = old.kind();
            meta.createdAt = old.createdAt().toString();
        }
        meta.label = displayLabel(label);
        meta.description = sanitizeDescription(description);
        writeMeta(archive, meta);
        return describe(archive);
    }

    public List<SaveArchive> list(String gameId) throws IOException {
        Path root = backupRoot(gameId);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".tar.gz"))
                    .map(this::describe)
                    .sorted(Comparator.comparing(SaveArchive::createdAt).reversed())
                    .toList();
        }
    }

    /** 是否已有导入时留存的原始存档（重复接入不重复留底） */
    public boolean hasOriginal(String gameId) throws IOException {
        return list(gameId).stream().anyMatch(a -> KIND_ORIGINAL.equals(a.kind()));
    }

    /**
     * 恢复 / 切换到指定存档。调用方必须先确认会话已停止。
     *
     * 顺序（任何一步失败都不会留下半截存档）:
     *  1. 可选：先对当前存档做 pre-restore 快照（{@code snapshotCurrent=false} 时跳过，
     *     避免每次切换都堆一份整包自动快照）
     *  2. 解压到**游戏目录内**的临时目录（同分区，重命名才是原子的；
     *     用系统临时目录会在跨分区 move 目录时失败）
     *  3. 校验归档成员无越界路径
     *  4. 按**存档目录的相对路径**逐个就位（嵌套目录如 SteamEmu/Saves 只替换该子目录，
     *     不能整体替换顶层 SteamEmu，否则会连 UserStats / Inventory 一起覆盖）
     *  5. 每个目标：现有目录改名为 *.galworld-old → 新目录改名就位 →
     *     成功后删掉 .old；失败则把 .old 改回来
     */
    public SaveArchive restore(String gameId, String archiveName) throws IOException {
        return restore(gameId, archiveName, true);
    }

    /**
     * @param snapshotCurrent 切换前是否给当前状态拍 pre-restore 快照。
     *                        常规切换传 false：每切一次就多一份整包备份，没必要。
     */
    public SaveArchive restore(String gameId, String archiveName, boolean snapshotCurrent)
            throws IOException {
        Path root = backupRoot(gameId);
        Path archive = resolveArchive(root, archiveName);
        if (!Files.isRegularFile(archive)) {
            throw new IllegalArgumentException("备份不存在: " + archiveName);
        }

        // 1) 保护性快照：把「切换前的当前进度」存下来，否则切走再切回来，
        //    这期间在游戏里存的进度就永久丢了（切换本质是整目录覆盖）。
        //    只保留最新一份，不会像以前那样每切一次就多堆积一份整包备份。
        if (snapshotCurrent) {
            try {
                backup(gameId, "切换前进度", "切换存档时自动保存的当前进度", KIND_PRE_RESTORE);
                // 清理时必须避开「本次要恢复的那份」—— 它本身也可能是一份 pre-restore，
                // 不排除的话刚拍完快照就把恢复目标删了，切换直接失败。
                prunePreRestore(gameId, archiveName);
            } catch (IllegalStateException e) {
                log.warn("恢复前快照跳过: {}", e.getMessage());
            }
        }

        Path gameDir = paths.gameDir(gameId).toAbsolutePath().normalize();
        cleanupStale(gameDir);
        Path tmp = Files.createTempDirectory(gameDir, ".galworld-restore-");
        try {
            // 2) 解压并校验
            runTar(List.of("tar", "-xzf", archive.toString(), "-C", tmp.toString()), "解压失败");
            validateExtracted(tmp);

            // 3) 原子替换：只搬归档里实际存在的存档目录，按相对路径定位
            List<Path> targets = extractedSaveDirs(tmp, gameId);
            if (targets.isEmpty()) {
                // 老备份（平铺目录）与目录名变更后的兜底：整层顶层项就位
                try (Stream<Path> s = Files.list(tmp)) {
                    for (Path src : s.toList()) {
                        swapIn(src, gameDir.resolve(src.getFileName().toString()));
                    }
                }
            } else {
                for (Path src : targets) {
                    Path rel = tmp.relativize(src);
                    swapIn(src, gameDir.resolve(rel));
                }
            }
            log.info("存档已切换 {} <- {}", gameId, archive.getFileName());
            // 记录「当前正在游玩的是哪份存档」，页面据此高亮
            writeCurrent(gameId, archive.getFileName().toString());
            return describe(archive);
        } finally {
            deleteRecursively(tmp);
        }
    }

    /** 当前生效的存档备份名；从未切换过则返回空串 */
    public String currentArchive(String gameId) {
        Path marker = currentMarker(gameId);
        try {
            String v = Files.readString(marker, java.nio.charset.StandardCharsets.UTF_8).trim();
            // 备份被删掉后标记失效，避免页面指向不存在的条目
            return Files.isRegularFile(backupRoot(gameId).resolve(v)) ? v : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void writeCurrent(String gameId, String archiveName) {
        Path marker = currentMarker(gameId);
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, archiveName + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 标记写失败不影响切换本身，只是页面不会高亮
            log.warn("写入当前存档标记失败 {}: {}", marker, e.toString());
        }
    }

    /** 当前存档标记文件放在备份目录旁边，跟着备份一起删干净 */
    private Path currentMarker(String gameId) {
        return paths.dataDir().resolve("backups").resolve("saves").resolve(gameId + ".current");
    }

    /**
     * 在解压结果里找出与「生效存档目录」对应的路径。
     *
     * 归档可能是嵌套结构（SteamEmu/Saves）也可能是平铺的旧结构（savedata），
     * 两者都按相对游戏目录的路径匹配；找不到就返回空，由调用方走兜底逻辑。
     */
    private List<Path> extractedSaveDirs(Path tmp, String gameId) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String name : resolveSaveDirNames(gameId)) {
            Path p = tmp.resolve(name).normalize();
            // 目录或单个文件都算：存档两种形态并存
            if (p.startsWith(tmp) && Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 把 src 目录换到 dst。同分区下 rename 是原子的，失败可回滚。 */
    private void swapIn(Path src, Path dst) throws IOException {
        // 嵌套存档目录（SteamEmu/Saves）的父级可能已被清理，先补出来
        Path parent = dst.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path old = dst.resolveSibling(dst.getFileName() + ".galworld-old");
        deleteRecursively(old);
        boolean hadOld = Files.exists(dst);
        if (hadOld) {
            Files.move(dst, old);
        }
        try {
            Files.move(src, dst);
        } catch (IOException e) {
            if (hadOld && !Files.exists(dst)) {
                Files.move(old, dst);
            }
            throw e;
        }
        deleteRecursively(old);
    }

    /**
     * 删除一份备份。
     *
     * 两类备份不允许删除：
     *  - 「原始存档」（{@link #KIND_ORIGINAL}）：导入游戏时留的底，玩家把游戏玩坏、
     *    存档全部丢失后唯一能回到初始状态的东西；
     *  - 「当前正在游玩的那份」：删掉它，游戏目录里的实际存档就失去了对应的备份锚点，
     *    页面上也没法再判断当前进度来自哪份备份。要删先切到别的存档。
     *
     * 前端同样隐藏删除按钮，这里再挡一道，防止直接调接口绕过。
     */
    public void delete(String gameId, String archiveName) throws IOException {
        Path archive = resolveArchive(backupRoot(gameId), archiveName);
        if (!Files.isRegularFile(archive)) {
            throw new IllegalArgumentException("备份不存在: " + archiveName);
        }
        if (KIND_ORIGINAL.equals(describe(archive).kind())) {
            throw new IllegalStateException("原始存档不可删除：它是游戏导入时的初始状态，"
                    + "存档全部丢失时靠它恢复");
        }
        if (archiveName.equals(currentArchive(gameId))) {
            throw new IllegalStateException("当前正在游玩的存档不可删除："
                    + "请先切换到其它存档，再删除这一份");
        }
        // 没有原始存档兜底时，最后一份备份就是唯一能回退的东西，不能删
        List<SaveArchive> all = list(gameId);
        boolean hasOriginal = all.stream().anyMatch(a -> KIND_ORIGINAL.equals(a.kind()));
        long remaining = all.stream().filter(a -> !a.name().equals(archiveName)).count();
        if (!hasOriginal && remaining == 0) {
            throw new IllegalStateException("至少要保留一份存档备份：这个游戏没有原始存档，"
                    + "删掉最后一份就没有任何可回退的状态了");
        }
        Files.deleteIfExists(archive);
        Files.deleteIfExists(metaPath(archive));
    }

    /**
     * 只保留最新一份 pre-restore 快照。
     *
     * 切换必须留一份「切换前的进度」当退路，但不需要保留历史上每一份 ——
     * 那样跑几次切换就堆出上百 MB。这里删掉除最新之外的全部。
     *
     * 不走 {@link #delete}：那条路径有「当前游玩中不可删」「最后一份不可删」
     * 的业务约束，清理内部快照不该受它们影响（也不该因为约束失败而中断切换）。
     *
     * @param restoreTarget 本次正在恢复的备份名，无论如何都要保留
     */
    private void prunePreRestore(String gameId, String restoreTarget) throws IOException {
        String current = currentArchive(gameId);
        List<SaveArchive> stale = list(gameId).stream()
                .filter(a -> KIND_PRE_RESTORE.equals(a.kind()))
                .filter(a -> !a.name().equals(current))
                .filter(a -> !a.name().equals(restoreTarget))
                .sorted(Comparator.comparing(SaveArchive::createdAt).reversed())
                .skip(1)
                .toList();
        for (SaveArchive a : stale) {
            Path p = backupRoot(gameId).resolve(a.name());
            try {
                Files.deleteIfExists(p);
                Files.deleteIfExists(metaPath(p));
                log.debug("清理旧快照 {}", a.name());
            } catch (IOException e) {
                log.warn("清理旧快照失败 {}: {}", a.name(), e.toString());
            }
        }
    }

    /** 原始存档（导入时留底）；没有则返回 null */
    public SaveArchive originalArchive(String gameId) throws IOException {
        return list(gameId).stream()
                .filter(a -> KIND_ORIGINAL.equals(a.kind()))
                .findFirst().orElse(null);
    }

    /** 备份文件路径；下载用。只做路径解析，不保证文件存在 */
    public Path archiveOf(String gameId, String archiveName) {
        return resolveArchive(backupRoot(gameId), archiveName);
    }

    // ---- helpers ----

    private Path resolveArchive(Path root, String name) {
        // 只允许备份目录下的普通文件名，挡住路径穿越
        String base = Path.of(name).getFileName().toString();
        if (!base.equals(name) || base.contains("..")) {
            throw new IllegalArgumentException("非法的备份名: " + name);
        }
        return root.resolve(base);
    }

    /** 清理上次异常中断留下的临时目录与 .old 残留 */
    private void cleanupStale(Path gameDir) {
        if (!Files.isDirectory(gameDir)) {
            return;
        }
        try (Stream<Path> s = Files.list(gameDir)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith(".galworld-restore-") || n.endsWith(".galworld-old")) {
                    log.warn("清理残留: {}", p);
                    deleteRecursively(p);
                }
            }
        } catch (IOException e) {
            log.warn("清理残留失败: {}", e.getMessage());
        }
    }

    private void validateExtracted(Path tmp) throws IOException {
        try (Stream<Path> s = Files.walk(tmp)) {
            for (Path p : s.toList()) {
                if (!p.normalize().startsWith(tmp.normalize())) {
                    throw new IOException("归档包含越界路径: " + p);
                }
            }
        }
    }

    private SaveArchive describe(Path archive) {
        String fn = archive.getFileName().toString().replace(".tar.gz", "");
        String label = "";
        int us = fn.indexOf('_');
        if (us > 0) {
            label = fn.substring(us + 1);
        }
        Instant created;
        try {
            created = Instant.from(TS.parse(fn.substring(0, 15)));
        } catch (Exception e) {
            created = Instant.EPOCH;
        }

        ArchiveMeta meta = readMeta(archive);
        String description = "";
        String kind = label.startsWith("pre-restore") ? KIND_PRE_RESTORE : KIND_MANUAL;
        if (meta != null) {
            if (meta.label != null && !meta.label.isBlank()) {
                label = meta.label;
            }
            description = meta.description == null ? "" : meta.description;
            kind = normalizeKind(meta.kind);
            if (meta.createdAt != null && !meta.createdAt.isBlank()) {
                try {
                    created = Instant.parse(meta.createdAt);
                } catch (RuntimeException ignored) {
                    // 元数据时间非法时沿用文件名里的时间
                }
            }
        }
        long size = 0;
        try {
            size = Files.size(archive);
        } catch (IOException ignored) {
            // 列表展示不因单文件读取失败而整体失败
        }
        return new SaveArchive(archive.getFileName().toString(), label, description, kind, size, created);
    }

    private Path metaPath(Path archive) {
        return archive.resolveSibling(archive.getFileName().toString() + ".json");
    }

    private ArchiveMeta readMeta(Path archive) {
        Path p = metaPath(archive);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            return mapper.readValue(p.toFile(), ArchiveMeta.class);
        } catch (IOException e) {
            log.warn("读取存档元数据失败 {}: {}", p, e.toString());
            return null;
        }
    }

    private void writeMeta(Path archive, ArchiveMeta meta) throws IOException {
        Path target = metaPath(archive);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        mapper.writeValue(tmp.toFile(), meta);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String normalizeKind(String kind) {
        if (kind == null) {
            return KIND_MANUAL;
        }
        String k = kind.strip().toUpperCase(Locale.ROOT);
        return KINDS.contains(k) ? k : KIND_MANUAL;
    }

    private String sanitizeLabel(String label) {
        if (label == null) {
            return "";
        }
        String cleaned = label.strip()
                .replaceAll("[^\\p{L}\\p{N}_-]+", "-")
                .replaceAll("^-+|-+$", "");
        // 用净化后的长度截断，否则原始 label 较长时 substring 会越界
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    private static String sanitizeDescription(String description) {
        if (description == null) {
            return "";
        }
        String cleaned = description.strip();
        return cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned;
    }

    /** 展示用标签保留空格与原文，只做长度限制；文件名另用 sanitizeLabel */
    private static String displayLabel(String label) {
        if (label == null) {
            return "";
        }
        String cleaned = label.strip();
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    private void runTar(List<String> cmd, String errMsg) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        try {
            if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException(errMsg + ": 超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(errMsg + ": 被中断", e);
        }
        if (p.exitValue() != 0) {
            throw new IOException(errMsg + ": " + out.strip().toLowerCase(Locale.ROOT));
        }
    }

    private void deleteRecursively(Path p) throws IOException {
        if (p == null || !Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(x);
            }
        }
    }
}
