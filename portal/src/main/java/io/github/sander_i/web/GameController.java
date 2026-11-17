/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.domain.Game;
import io.github.sander_i.i18n.I18n;
import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.service.GameScanService;
import io.github.sander_i.service.PlaytimeService;
import io.github.sander_i.service.SessionService;
import io.github.sander_i.service.SettingsService;
import io.github.sander_i.service.TagsAndMeta;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/games")
public class GameController {

    /** 单页上限，防止前端传大值把响应撑爆 */
    private static final int MAX_PAGE = 200;
    /** 未传 limit 时的默认单页条数 */
    private static final String DEFAULT_PAGE = "40";

    private final GameRepository repo;
    private final GameScanService scan;
    private final SessionService sessions;
    private final PlaytimeService playtime;
    private final TagsAndMeta meta;
    private final io.github.sander_i.service.CompanyService companyService;
    private final io.github.sander_i.service.LocalizationGroupService localizationService;
    private final io.github.sander_i.service.GameMediaService gameMedia;
    private final SettingsService settings;
    private final io.github.sander_i.service.GameDeletionService deletion;

    public GameController(GameRepository repo, GameScanService scan, SessionService sessions,
                          PlaytimeService playtime, TagsAndMeta meta,
                          io.github.sander_i.service.CompanyService companyService,
                          io.github.sander_i.service.LocalizationGroupService localizationService,
                          io.github.sander_i.service.GameMediaService gameMedia,
                          SettingsService settings,
                          io.github.sander_i.service.GameDeletionService deletion) {
        this.repo = repo;
        this.scan = scan;
        this.sessions = sessions;
        this.playtime = playtime;
        this.meta = meta;
        this.companyService = companyService;
        this.localizationService = localizationService;
        this.gameMedia = gameMedia;
        this.settings = settings;
        this.deletion = deletion;
    }

    @GetMapping
    public ResponseEntity<List<GameView>> list(
                               @RequestParam(name = "tag", required = false) String tag,
                               @RequestParam(name = "company", required = false) String company,
                               @RequestParam(name = "localization", required = false) String localization,
                               @RequestParam(name = "q", required = false) String q,
                               @RequestParam(name = "offset", defaultValue = "0") int offset,
                               @RequestParam(name = "limit", defaultValue = DEFAULT_PAGE) int limit,
                               HttpServletResponse response) {
        // 每次列表都同步一次磁盘，命令行新加的游戏能立刻出现
        scan.syncAll();
        List<Game> games = repo.findAllByOrderByNameAsc();
        Map<String, List<io.github.sander_i.domain.Tag>> tagMap =
                meta.tagsByGame(games.stream().map(Game::getId).toList());
        java.util.Set<String> companyIds = (company == null || company.isBlank())
                ? null
                : isId(company) ? companyService.gameIdsOfId(Long.parseLong(company.strip()))
                : companyService.gameIdsOf(company.strip());
        java.util.Set<String> localizationIds = (localization == null || localization.isBlank())
                ? null
                : isId(localization)
                        ? localizationService.gameIdsOfId(Long.parseLong(localization.strip()))
                        : localizationService.gameIdsOf(localization.strip());

        List<Game> filtered = games.stream()
                .filter(g -> companyIds == null || companyIds.contains(g.getId()))
                .filter(g -> localizationIds == null || localizationIds.contains(g.getId()))
                .filter(g -> matches(g, tagMap.get(g.getId()), tag, q))
                .toList();

        int size = Math.max(1, Math.min(MAX_PAGE, limit));
        int from = Math.max(0, Math.min(offset, filtered.size()));
        int to = Math.min(filtered.size(), from + size);
        // 总数放响应头：保持响应体仍是数组，现有调用方不用改解析
        response.setHeader("X-Total-Count", String.valueOf(filtered.size()));
        return ResponseEntity.ok(filtered.subList(from, to).stream()
                .map(g -> toView(g, tagMap.get(g.getId())))
                .toList());
    }

    /** 筛选参数既接受数字 id（前端），也兼容公司/汉化组名称（旧链接） */
    private boolean isId(String value) {
        try {
            Long.parseLong(value.strip());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean matches(Game g, List<io.github.sander_i.domain.Tag> tags, String tag, String q) {        if (tag != null && !tag.isBlank()) {
            boolean has = tags != null && tags.stream().anyMatch(t -> t.getName().equals(tag.strip()));
            if (!has) {
                return false;
            }
        }
        if (q != null && !q.isBlank()) {
            String needle = q.strip().toLowerCase(java.util.Locale.ROOT);
            return g.getName().toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || (g.getDescription() != null
                        && g.getDescription().toLowerCase(java.util.Locale.ROOT).contains(needle));
        }
        return true;
    }

    @GetMapping("/{id}")
    public ResponseEntity<GameView> one(@PathVariable String id) {
        return repo.findById(id)
                .map(game -> ResponseEntity.ok(toView(game, meta.ofGame(game.getId()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/scan")
    public List<GameView> rescan() {
        scan.syncAll();
        return repo.findAllByOrderByNameAsc().stream()
                .map(g -> toView(g, meta.ofGame(g.getId())))
                .toList();
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<GameView> start(@PathVariable String id) {
        return repo.findById(id)
                .map(g -> {
                    var st = sessions.start(g.getId(), g.getPort());
                    return ResponseEntity.ok(toView(g, st, meta.ofGame(g.getId())));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<GameView> stop(@PathVariable String id) {
        return repo.findById(id)
                .map(g -> {
                    try {
                        var st = sessions.stop(g.getId(), g.getPort());
                        return ResponseEntity.ok(toView(g, st, meta.ofGame(g.getId())));
                    } finally {
                        // 兜底结算：用户点了停止，就算 systemctl 调用失败也要把时长落账，
                        // 否则进行中的记录会一直涨（开发环境无 systemctl 时正是如此）
                        playtime.closeOpen(g.getId());
                    }
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record MediaModeRequest(String mode) {
    }

    /** 详情页大屏展示偏好：cg / cover，存进游戏配置，所有浏览器共用 */
    @PostMapping("/{id}/media-mode")
    public ResponseEntity<?> updateMediaMode(@PathVariable String id,
                                             @org.springframework.web.bind.annotation.RequestBody
                                             MediaModeRequest req) {
        String mode = req == null || req.mode() == null
                ? "" : req.mode().strip().toLowerCase(java.util.Locale.ROOT);
        if (!(mode.equals("cg") || mode.equals("cover"))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", I18n.t("mode 只能是 cg 或 cover")));
        }
        return repo.findById(id)
                .map(g -> {
                    g.setMediaMode(mode);
                    repo.save(g);
                    return ResponseEntity.ok(Map.of("mediaMode", mode));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            deletion.delete(id);
            return ResponseEntity.ok(Map.of("deleted", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        } catch (java.io.IOException e) {
            return ResponseEntity.status(500)
                    .body(Map.of("message", I18n.t("删除失败：{0}", e.getMessage())));
        }
    }

    private GameView toView(Game g, List<io.github.sander_i.domain.Tag> tags) {
        var st = sessions.status(g.getId(), g.getPort());
        return toView(g, st, tags);
    }

    private GameView toView(Game g, SessionService.SessionStatus st,
                            List<io.github.sander_i.domain.Tag> tags) {
        var comps = companyService.ofGame(g.getId()).stream()
                .map(c -> new GameView.CompanyView(c.getId(), c.getName(),
                        c.getAlias() == null ? "" : c.getAlias(),
                        c.getWebsite() == null ? "" : c.getWebsite(),
                        c.getDescription() == null ? "" : c.getDescription()))
                .toList();
        var groups = localizationService.ofGame(g.getId()).stream()
                .map(c -> new GameView.GroupView(c.getId(), c.getName(),
                        c.getAlias() == null ? "" : c.getAlias(),
                        c.getWebsite() == null ? "" : c.getWebsite(),
                        c.getDescription() == null ? "" : c.getDescription()))
                .toList();
        var covers = gameMedia.itemsOf(g.getId(), "cover");
        var cgs = gameMedia.itemsOf(g.getId(), "cg");
        // 单游戏覆盖优先：新字段 -> 旧字段 -> 全局规则
        String legacyMode = g.getRotationMode();
        Integer legacyInterval = g.getRotationInterval();
        String coverMode = g.getCoverRotationMode() != null ? g.getCoverRotationMode()
                : legacyMode != null ? legacyMode : settings.coverRotationMode();
        int coverInterval = g.getCoverRotationInterval() != null ? g.getCoverRotationInterval()
                : legacyInterval != null ? legacyInterval : settings.coverRotationInterval();
        String cgRaw = g.getCgRotationMode() != null ? g.getCgRotationMode()
                : legacyMode != null ? legacyMode : settings.cgRotationMode();
        String cgMode;
        String cgOrder;
        if (cgRaw.equals("seq") || cgRaw.equals("random") || cgRaw.equals("videoEnd")) {
            // 旧配置只有顺序/随机：等价于「播完自动下一条 + 对应顺序」
            cgMode = "autoNext";
            cgOrder = "random".equals(cgRaw) ? "random" : "seq";
        } else {
            cgMode = cgRaw;
            cgOrder = g.getCgRotationOrder() != null ? g.getCgRotationOrder()
                    : legacyMode != null && legacyMode.equals("random") ? "random"
                    : settings.cgRotationOrder();
        }
        int cgInterval = g.getCgRotationInterval() != null ? g.getCgRotationInterval()
                : legacyInterval != null ? legacyInterval : settings.cgRotationInterval();
        return GameView.of(g, st, playtime.summary(g.getId()).totalSeconds(), tags, comps,
                groups, covers, cgs, coverMode, coverInterval, coverMode, coverInterval,
                cgMode, cgOrder, cgInterval);
    }
}
