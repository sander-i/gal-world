/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.domain.Game;
import io.github.sander_i.i18n.I18n;
import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.service.CompanyService;
import io.github.sander_i.service.LocalizationGroupService;
import io.github.sander_i.service.FaviconService;
import io.github.sander_i.service.GameMediaService;
import io.github.sander_i.service.MediaService;
import io.github.sander_i.service.TagsAndMeta;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 游戏元数据维护：标签、公司、简介、封面、图标、宣传 CG。 */
@RestController
@RequestMapping("/api/games/{id}")
public class GameMetaController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GameMetaController.class);

    private final GameRepository repo;
    private final TagsAndMeta meta;
    private final CompanyService companyService;
    private final LocalizationGroupService localizationService;
    private final GameMediaService gameMedia;
    private final MediaService media;
    private final PathConfig paths;
    private final io.github.sander_i.service.GameScanService scanWriter;
    private final io.github.sander_i.service.GameExeScanner exeScanner;
    private final FaviconService favicons;
    private final io.github.sander_i.service.CanvasCalibrationService calibration;

    public GameMetaController(GameRepository repo, TagsAndMeta meta, CompanyService companyService,
                              LocalizationGroupService localizationService,
                              GameMediaService gameMedia, MediaService media, PathConfig paths,
                              io.github.sander_i.service.GameScanService scanWriter,
                              io.github.sander_i.service.GameExeScanner exeScanner,
                              FaviconService favicons,
                              io.github.sander_i.service.CanvasCalibrationService calibration) {
        this.repo = repo;
        this.meta = meta;
        this.companyService = companyService;
        this.localizationService = localizationService;
        this.gameMedia = gameMedia;
        this.media = media;
        this.paths = paths;
        this.scanWriter = scanWriter;
        this.exeScanner = exeScanner;
        this.favicons = favicons;
        this.calibration = calibration;
    }

    public record MetaRequest(String name, Integer width, Integer height,
                              String description, List<String> tags,
                              List<CompanyService.CompanyRef> companies,
                              List<LocalizationGroupService.GroupRef> localizations,
                              String rotationMode, Integer rotationInterval,
                              Boolean followGlobal,
                              String coverRotationMode, Integer coverRotationInterval,
                              String cgRotationMode, String cgRotationOrder,
                              Integer cgRotationInterval,
                              String exe, String exeAlt,
                              // 画质参数：null = 不改（沿用 game.env 里人工写的值）
                              Integer videoCrf, Integer videoPaintoverCrf,
                              String rateControlMode, String videoBitrate,
                              Integer keyframeInterval) {
    }

    /** 编辑页用的完整视图：含服务器绝对路径 */
    @org.springframework.web.bind.annotation.GetMapping("/edit")
    public ResponseEntity<?> editView(@PathVariable String id) throws IOException {
        Game g = repo.findById(id).orElse(null);
        if (g == null) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> coverFiles = fileList(id, "cover");
        List<Map<String, Object>> cgFiles = fileList(id, "cg");
        List<Map<String, Object>> iconFiles = fileList(id, "icon");

        // Map.of 最多 10 对，这里用 HashMap 避免限制
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("id", g.getId());
        body.put("name", g.getName());
        body.put("width", g.getWidth());
        body.put("height", g.getHeight());
        body.put("description", g.getDescription() == null ? "" : g.getDescription());
        body.put("tags", meta.ofGame(id).stream().map(t -> t.getName()).toList());
        body.put("companies", companyService.ofGame(id).stream().map(c -> Map.of(
                "id", c.getId(),
                "name", c.getName(),
                "alias", c.getAlias() == null ? "" : c.getAlias(),
                "website", c.getWebsite() == null ? "" : c.getWebsite(),
                "description", c.getDescription() == null ? "" : c.getDescription())).toList());
        body.put("localizations", localizationService.ofGame(id).stream().map(c -> Map.of(
                "id", c.getId(),
                "name", c.getName(),
                "alias", c.getAlias() == null ? "" : c.getAlias(),
                "website", c.getWebsite() == null ? "" : c.getWebsite(),
                "description", c.getDescription() == null ? "" : c.getDescription())).toList());
        body.put("covers", gameMedia.ofGame(id, "cover"));
        body.put("cgs", gameMedia.ofGame(id, "cg"));
        body.put("iconPath", g.getIconPath() == null ? "" : g.getIconPath());
        body.put("port", g.getPort());
        // 画质参数：null 表示门户不管理，编辑页对应输入框留空
        body.put("videoCrf", g.getVideoCrf());
        body.put("videoPaintoverCrf", g.getVideoPaintoverCrf());
        body.put("rateControlMode", g.getRateControlMode());
        body.put("videoBitrate", g.getVideoBitrate());
        body.put("keyframeInterval", g.getKeyframeInterval());
        body.put("rotationMode", g.getRotationMode() == null ? "" : g.getRotationMode());
        body.put("rotationInterval", g.getRotationInterval() == null ? 0 : g.getRotationInterval());
        body.put("coverRotationMode",
                g.getCoverRotationMode() == null ? "" : g.getCoverRotationMode());
        body.put("coverRotationInterval",
                g.getCoverRotationInterval() == null ? 0 : g.getCoverRotationInterval());
        body.put("cgRotationMode",
                g.getCgRotationMode() == null ? "" : g.getCgRotationMode());
        body.put("cgRotationOrder",
                g.getCgRotationOrder() == null ? "" : g.getCgRotationOrder());
        body.put("cgRotationInterval",
                g.getCgRotationInterval() == null ? 0 : g.getCgRotationInterval());
        body.put("paths", Map.of(
                "exe", g.getExe() == null ? "" : g.getExe(),
                "exeAlt", g.getExeAlt() == null ? "" : g.getExeAlt(),
                "mediaDir", paths.gameMediaDir(id).toString(),
                "cover", absolutePaths(coverFiles),
                "icon", absolutePaths(iconFiles),
                "cg", absolutePaths(cgFiles)));
        return ResponseEntity.ok(body);
    }

    /** 编辑页的 exe 候选：目录内所有 exe + 当前主用与备用 */
    @org.springframework.web.bind.annotation.GetMapping("/exes")
    public ResponseEntity<?> exes(@PathVariable String id) {
        Game g = repo.findById(id).orElse(null);
        if (g == null) {
            return ResponseEntity.notFound().build();
        }
        Path dir = paths.gameDir(id);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("dir", dir.toString());
        body.put("current", g.getExe() == null ? "" : g.getExe());
        body.put("alt", g.getExeAlt() == null ? "" : g.getExeAlt());
        body.put("exes", exeScanner.scan(dir));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/meta")
    public ResponseEntity<?> updateMeta(@PathVariable String id, @RequestBody MetaRequest req) {
        Game g = repo.findById(id).orElse(null);
        if (g == null) {
            return ResponseEntity.notFound().build();
        }
        // 画布范围与接入校验保持一致，防止前端绕过限制写入越界值
        if (req.width() != null && (req.width() < 640 || req.width() > 3840)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", I18n.t("画布宽度需在 640–3840 之间")));
        }
        if (req.height() != null && (req.height() < 480 || req.height() > 2160)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", I18n.t("画布高度需在 480–2160 之间")));
        }
        // 画质参数范围与 Selkies settings.py 对齐，防止页面之外写入越界值
        if (req.videoCrf() != null && (req.videoCrf() < 5 || req.videoCrf() > 50)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", I18n.t("CRF 需在 5–50 之间")));
        }
        if (req.videoPaintoverCrf() != null
                && (req.videoPaintoverCrf() < 5 || req.videoPaintoverCrf() > 50)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", I18n.t("静止画面 CRF 需在 5–50 之间")));
        }
        if (req.keyframeInterval() != null
                && (req.keyframeInterval() < 0 || req.keyframeInterval() > 300)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", I18n.t("关键帧间隔需在 0–300 秒之间")));
        }
        if (req.rateControlMode() != null && !req.rateControlMode().isBlank()
                && !"crf".equals(req.rateControlMode()) && !"cbr".equals(req.rateControlMode())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", I18n.t("码率控制模式只能是 crf 或 cbr")));
        }
        // 名称改存 DB：扫描只对新游戏读 GAME_NAME，不会覆盖这里的修改
        if (req.name() != null && !req.name().isBlank()) {
            g.setName(req.name().strip());
        }
        // 分辨率需写回 game.env，run-game.sh 从那里读；改完要重启会话才生效
        boolean resolutionChanged = false;
        if (req.width() != null && req.width() > 0 && req.width() != g.getWidth()) {
            g.setWidth(req.width());
            resolutionChanged = true;
        }
        if (req.height() != null && req.height() > 0 && req.height() != g.getHeight()) {
            g.setHeight(req.height());
            resolutionChanged = true;
        }
        // 用户手动改画布后清掉校准状态，避免下一次自动校准又改回去
        if (resolutionChanged) {
            calibration.reset(id);
        }
        if (req.description() != null) {
            g.setDescription(req.description());
        }
        // 轮换覆盖：followGlobal=true 表示跟随全局，清空单游戏值
        if (Boolean.TRUE.equals(req.followGlobal())) {
            g.setRotationMode(null);
            g.setRotationInterval(null);
            g.setCoverRotationMode(null);
            g.setCoverRotationInterval(null);
            g.setCgRotationMode(null);
            g.setCgRotationOrder(null);
            g.setCgRotationInterval(null);
        } else {
            if (req.coverRotationMode() != null || req.coverRotationInterval() != null) {
                g.setCoverRotationMode(req.coverRotationMode());
                g.setCoverRotationInterval(req.coverRotationInterval());
            }
            if (req.cgRotationMode() != null || req.cgRotationOrder() != null
                    || req.cgRotationInterval() != null) {
                g.setCgRotationMode(req.cgRotationMode());
                g.setCgRotationOrder(req.cgRotationOrder());
                g.setCgRotationInterval(req.cgRotationInterval());
            }
            // 旧字段兼容：仍接收 rotationMode/rotationInterval
            if (req.rotationMode() != null || req.rotationInterval() != null) {
                g.setRotationMode(req.rotationMode());
                g.setRotationInterval(req.rotationInterval());
            }
        }
        // 启动程序：exe 是当前生效项，exeAlt 是备用（可空）。
        // 两者都要写回 game.env——扫描器以 env 为准，只改 DB 会被下次扫描冲掉。
        boolean exeChanged = false;
        if (req.exe() != null || req.exeAlt() != null) {
            Path dir = paths.gameDir(id);
            String active = req.exe() == null
                    ? (g.getExe() == null ? "" : g.getExe())
                    : io.github.sander_i.service.GameExePaths.resolve(dir, req.exe());
            String alt = req.exeAlt() == null
                    ? (g.getExeAlt() == null ? "" : g.getExeAlt())
                    : (req.exeAlt().isBlank()
                            ? ""
                            : io.github.sander_i.service.GameExePaths.resolve(dir, req.exeAlt()));
            if (!alt.isEmpty() && alt.equals(active)) {
                throw new IllegalArgumentException(I18n.t("备用启动程序不能与当前启动程序相同"));
            }
            if (!active.equals(g.getExe() == null ? "" : g.getExe())
                    || !alt.equals(g.getExeAlt() == null ? "" : g.getExeAlt())) {
                exeChanged = true;
            }
            g.setExe(active);
            g.setExeAlt(alt.isEmpty() ? null : alt);
        }
        // 画质参数：只覆盖请求里带了值的项，其余保持「门户不管理」
        boolean qualityChanged = false;
        if (req.videoCrf() != null) {
            qualityChanged |= !req.videoCrf().equals(g.getVideoCrf());
            g.setVideoCrf(req.videoCrf());
        }
        if (req.videoPaintoverCrf() != null) {
            qualityChanged |= !req.videoPaintoverCrf().equals(g.getVideoPaintoverCrf());
            g.setVideoPaintoverCrf(req.videoPaintoverCrf());
        }
        if (req.rateControlMode() != null && !req.rateControlMode().isBlank()) {
            qualityChanged |= !req.rateControlMode().equals(g.getRateControlMode());
            g.setRateControlMode(req.rateControlMode());
        }
        if (req.videoBitrate() != null && !req.videoBitrate().isBlank()) {
            qualityChanged |= !req.videoBitrate().equals(g.getVideoBitrate());
            g.setVideoBitrate(req.videoBitrate().strip());
        }
        if (req.keyframeInterval() != null) {
            qualityChanged |= !req.keyframeInterval().equals(g.getKeyframeInterval());
            g.setKeyframeInterval(req.keyframeInterval());
        }
        repo.save(g);
        if (req.tags() != null) {
            meta.setTags(id, req.tags());
        }
        if (req.companies() != null) {
            companyService.setForGame(id, req.companies());
        }
        if (req.localizations() != null) {
            localizationService.setForGame(id, req.localizations());
        }
        maybeFetchIcon(g, req);
        if (resolutionChanged || exeChanged || qualityChanged) {
            try {
                scanWriter.writeEnv(g);
            } catch (IOException e) {
                return ResponseEntity.status(500)
                        .body(Map.of("message", I18n.t("写入 game.env 失败: {0}", e.getMessage())));
            }
        }
        // Map.of 最多 10 对，字段多了改用 LinkedHashMap
        Map<String, Object> ok = new java.util.LinkedHashMap<>();
        ok.put("name", g.getName());
        ok.put("width", g.getWidth());
        ok.put("height", g.getHeight());
        ok.put("description", g.getDescription() == null ? "" : g.getDescription());
        ok.put("tags", meta.ofGame(id).stream().map(t -> t.getName()).toList());
        ok.put("companies", companyService.ofGame(id).stream().map(c -> Map.of(
                "id", c.getId(),
                "name", c.getName(),
                "alias", c.getAlias() == null ? "" : c.getAlias(),
                "website", c.getWebsite() == null ? "" : c.getWebsite(),
                "description", c.getDescription() == null ? "" : c.getDescription())).toList());
        ok.put("localizations", localizationService.ofGame(id).stream().map(c -> Map.of(
                "id", c.getId(),
                "name", c.getName(),
                "alias", c.getAlias() == null ? "" : c.getAlias(),
                "website", c.getWebsite() == null ? "" : c.getWebsite(),
                "description", c.getDescription() == null ? "" : c.getDescription())).toList());
        // 画质参数不重启就生效（下次开流时读 game.env），所以不进 restartRequired
        ok.put("restartRequired", resolutionChanged || exeChanged);
        ok.put("exe", g.getExe() == null ? "" : g.getExe());
        ok.put("exeAlt", g.getExeAlt() == null ? "" : g.getExeAlt());
        ok.put("videoCrf", g.getVideoCrf());
        ok.put("videoPaintoverCrf", g.getVideoPaintoverCrf());
        ok.put("rateControlMode", g.getRateControlMode());
        ok.put("videoBitrate", g.getVideoBitrate());
        ok.put("keyframeInterval", g.getKeyframeInterval());
        return ResponseEntity.ok(ok);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @PostMapping(value = "/media/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadMedia(@PathVariable String id, @PathVariable String kind,
                                         @RequestParam("file") MultipartFile file) throws IOException {
        if (!repo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", I18n.t("没有收到文件")));
        }
        try (var in = file.getInputStream()) {
            GameMediaService.MediaView saved =
                    gameMedia.upload(id, kind, file.getOriginalFilename(), in);
            // 上传即时化后前端会并发上传，列表快照可能缺项或重复；
            // 只回单条，列表统一走 GET /media/{kind}
            return ResponseEntity.ok(Map.of("media", saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 列出某类媒体（cover / cg）。
     *
     * 上传即时落库之后，前端在「取消上传」「局部刷新」时用它对账：
     * abort 可能发生在服务端已保存、响应未返回之间，必须以服务端列表为准。
     */
    @org.springframework.web.bind.annotation.GetMapping("/media/{kind}")
    public ResponseEntity<?> listMedia(@PathVariable String id, @PathVariable String kind) {
        if (!repo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        if (!kind.equals("cover") && !kind.equals("cg")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", I18n.t("kind 必须是 cover 或 cg")));
        }
        return ResponseEntity.ok(gameMedia.ofGame(id, kind));
    }

    @DeleteMapping("/media/{mediaId}")
    public ResponseEntity<?> deleteMedia(@PathVariable String id, @PathVariable long mediaId)
            throws IOException {
        try {
            gameMedia.delete(id, mediaId);
            return ResponseEntity.ok(Map.of("deleted", mediaId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    public record ReorderRequest(String kind, List<Long> ids) {
    }

    @PostMapping("/media/reorder")
    public ResponseEntity<?> reorder(@PathVariable String id, @RequestBody ReorderRequest req)
            throws IOException {
        try {
            gameMedia.reorder(id, req.kind(), req.ids());
            return ResponseEntity.ok(Map.of("all", gameMedia.ofGame(id, req.kind())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 图标上传（单张，走 MediaService 的固定名逻辑） */
    @PostMapping(value = "/icon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadIcon(@PathVariable String id,
                                        @RequestParam("file") MultipartFile file) throws IOException {
        Game g = repo.findById(id).orElse(null);
        if (g == null) {
            return ResponseEntity.notFound().build();
        }
        try (var in = file.getInputStream()) {
            MediaService.Saved saved = media.save(id, "icon", 0, file.getOriginalFilename(), in);
            g.setIconPath(saved.url());
            g.setIconAutoFetched(false);
            g.setIconAutoFetchDisabled(false);
            repo.save(g);
            return ResponseEntity.ok(Map.of("url", saved.url()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/icon")
    public ResponseEntity<?> clearIcon(@PathVariable String id) throws IOException {
        Game g = repo.findById(id).orElse(null);
        if (g == null) {
            return ResponseEntity.notFound().build();
        }
        media.deleteFile(id, "icon", 0);
        g.setIconPath(null);
        g.setIconAutoFetched(false);
        // 用户明确删掉图标：之后不再自动抓取
        g.setIconAutoFetchDisabled(true);
        repo.save(g);
        return ResponseEntity.ok(Map.of("cleared", "icon"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(
            org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413)
                .body(Map.of("message", I18n.t("文件过大：图片上限 {0} MB，视频上限 {1} MB",
                        media.imageMaxBytes() / 1024 / 1024,
                        media.videoMaxBytes() / 1024 / 1024)));
    }

    /** 保存公司关联后，若游戏没有图标则抓取首个有网址公司的站点图标 */
    private void maybeFetchIcon(Game g, MetaRequest req) {
        if (req.companies() == null) return;
        if (g.getIconPath() != null && !g.getIconPath().isBlank()) return;
        if (g.isIconAutoFetchDisabled()) return;
        for (io.github.sander_i.domain.Company c : companyService.ofGame(g.getId())) {
            String website = c.getWebsite();
            if (website == null || website.isBlank()) continue;
            var bytes = favicons.fetch(website);
            if (bytes.isEmpty()) continue;
            try (var in = new java.io.ByteArrayInputStream(bytes.get())) {
                MediaService.Saved saved = media.save(g.getId(), "icon", 0, "favicon.png", in);
                g.setIconPath(saved.url());
                g.setIconAutoFetched(true);
                repo.save(g);
            } catch (Exception e) {
                log.warn("保存公司站点图标失败: {}", e.toString());
            }
            return;
        }
    }

    // ---- 单游戏维度排序 ----

    /** kind = company / localization / tag */
    public record RelationOrderRequest(String kind, List<Long> ids) {
    }

    @PutMapping("/relations/order")
    public ResponseEntity<?> reorderRelations(@PathVariable String id,
                                              @RequestBody RelationOrderRequest req) {
        applyOrder(id, req.kind(), req.ids());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/relations/order")
    public ResponseEntity<?> resetRelationsOrder(@PathVariable String id,
                                                 @RequestParam String kind) {
        applyOrder(id, kind, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private void applyOrder(String id, String kind, List<Long> ids) {
        if ("company".equals(kind)) {
            if (ids == null) companyService.resetOrderForGame(id);
            else companyService.reorderForGame(id, ids);
        } else if ("localization".equals(kind)) {
            if (ids == null) localizationService.resetOrderForGame(id);
            else localizationService.reorderForGame(id, ids);
        } else if ("tag".equals(kind)) {
            if (ids == null) meta.resetOrderForGame(id);
            else meta.reorderForGame(id, ids);
        } else {
            throw new IllegalArgumentException(I18n.t("未知维度: {0}", kind));
        }
    }

    // ---- helpers ----

    private List<Map<String, Object>> fileList(String gameId, String kind) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Path p : media.listFiles(gameId, kind)) {
            out.add(Map.of(
                    "file", p.getFileName().toString(),
                    "size", p.toFile().length()));
        }
        return out;
    }

    private List<String> absolutePaths(List<Map<String, Object>> files) {
        return files.stream()
                .map(f -> String.valueOf(f.get("file")))
                .toList();
    }
}
