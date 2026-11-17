/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.domain.Setting;
import io.github.sander_i.i18n.I18n;
import io.github.sander_i.i18n.I18nService;
import io.github.sander_i.repo.SettingRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;

/** 键值设置，带默认值与类型校验。 */
@Service
public class SettingsService {

    /** 进入游戏的方式：tab=新标签页，page=当前页跳转 */
    public static final String KEY_SWITCH_MODE = "switchMode";
    /** 空闲自动停止分钟数，0 表示关闭 */
    public static final String KEY_IDLE_STOP_MINUTES = "idleStopMinutes";
    /** 宣传 CG 默认是否有声：true / false（默认静音，避免与游戏声音打架） */
    public static final String KEY_CG_SOUND = "cgSound";
    /** 轮换方式：seq 顺序 / random 随机（旧版，仅作封面/CG 规则的迁移来源） */
    public static final String KEY_ROTATION_MODE = "rotationMode";
    /** 轮换间隔，单位秒（旧版，仅作迁移来源） */
    public static final String KEY_ROTATION_INTERVAL = "rotationInterval";
    /** 封面轮换方式：seq / random */
    public static final String KEY_COVER_ROTATION_MODE = "coverRotationMode";
    /** 封面轮换间隔（秒） */
    public static final String KEY_COVER_ROTATION_INTERVAL = "coverRotationInterval";
    /** 宣传 CG 一级行为：autoNext 播完下一条 / repeat 重复当前 / stop 播完停止 */
    public static final String KEY_CG_ROTATION_MODE = "cgRotationMode";
    /** 宣传 CG 二级顺序（仅 autoNext 使用）：seq / random */
    public static final String KEY_CG_ROTATION_ORDER = "cgRotationOrder";
    /** 宣传 CG 图片项的间隔（秒） */
    public static final String KEY_CG_ROTATION_INTERVAL = "cgRotationInterval";
    /** 是否开启封面 / 宣传 CG 轮换：true / false */
    public static final String KEY_ROTATION_ENABLED = "rotationEnabled";
    /** 单个游戏最多几张封面 */
    public static final String KEY_COVER_MAX_COUNT = "coverMaxCount";
    /** 单个游戏最多几个宣传 CG */
    public static final String KEY_CG_MAX_COUNT = "cgMaxCount";
    /** 图片单张上限（MB）：封面、图标、图片类 CG 共用 */
    public static final String KEY_IMAGE_MAX_MB = "imageMaxMb";
    /** 视频单张上限（MB）：宣传 CG 视频 */
    public static final String KEY_VIDEO_MAX_MB = "videoMaxMb";
    /** 游戏页默认展示：cover=只显示封面 / cg=封面就绪后切 CG */
    public static final String KEY_DEFAULT_MEDIA_MODE = "defaultMediaMode";
    /** 是否生成按显示尺寸缩放的派生图（图片预加载优化）：true / false */
    public static final String KEY_IMAGE_VARIANT = "imageVariantEnabled";
    /** 元件仓库地址（Nexus raw 仓库根，例如 http://127.0.0.1:8081/repository/galworld-artifacts） */
    public static final String KEY_ARTIFACT_NEXUS = "artifactNexusBase";
    /** 元件镜像前缀，逗号或换行分隔；下载失败时按顺序回退 */
    public static final String KEY_ARTIFACT_MIRRORS = "artifactMirrors";
    /** 首页推荐位高度：medium=190px 大图，off=只留一条继续游玩横条 */
    public static final String KEY_HOME_HERO_SIZE = "homeHeroSize";
    /** 是否展示「公司」维度：true / false（停用只隐藏 UI，数据保留） */
    public static final String KEY_COMPANY_ENABLED = "companyEnabled";
    /** 是否展示「汉化组」维度：true / false（停用只隐藏 UI，数据保留） */
    public static final String KEY_LOCALIZATION_ENABLED = "localizationGroupEnabled";
    /** 画布校准方式：auto=进入游玩页自动校准，manual=只在游玩页点按钮 */
    public static final String KEY_CANVAS_CALIBRATE_MODE = "canvasCalibrateMode";
    /** 项目代码仓库地址：显示在「设置 → 关于」，默认取 GALWORLD_REPO_URL */
    public static final String KEY_REPO_URL = "repoUrl";
    /** 是否在公开的 /api/health 里下发仓库地址：true / false */
    public static final String KEY_REPO_URL_PUBLIC = "repoUrlPublic";
    /**
     * 远程更新源：发布接口根地址（GitHub / Gitee / Gitea / 自建 GitLab 的 API 前缀），
     * 默认取 GALWORLD_UPDATE_API_BASE。连不上 GitHub 时指向可用的镜像即可。
     */
    public static final String KEY_UPDATE_API_BASE = "updateApiBase";
    /** 远程更新源：仓库路径 owner/repo，默认取 GALWORLD_UPDATE_REPO */
    public static final String KEY_UPDATE_REPO = "updateRepo";
    /** 远程更新源：仓库网页根地址，用于拼发布页链接，默认取 GALWORLD_UPDATE_WEB_BASE */
    public static final String KEY_UPDATE_WEB_BASE = "updateWebBase";
    /**
     * 站点默认语言（{@code en} / {@code zh-CN} / {@code zh-TW} / {@code ja} 或外部语言包 id）。
     *
     * 浏览器本机的选择存在 localStorage，优先于它；这里是新访客与后端消息的默认值。
     */
    public static final String KEY_LANGUAGE = I18nService.KEY_LANGUAGE;

    private static final java.util.Set<String> HERO_SIZES = java.util.Set.of("medium", "off");

    /** 元件地址校验：只接受 http(s) URL */
    private static final java.util.regex.Pattern URL_PATTERN =
            java.util.regex.Pattern.compile("https?://\\S+");

    /** 仓库路径：owner/repo，只允许常见字符，防止拼进 URL 时被注入路径 */
    private static final java.util.regex.Pattern REPO_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry(KEY_SWITCH_MODE, "page"),
            Map.entry(KEY_IDLE_STOP_MINUTES, "0"),
            Map.entry(KEY_CG_SOUND, "false"),
            Map.entry(KEY_ROTATION_MODE, "seq"),
            Map.entry(KEY_ROTATION_INTERVAL, "6"),
            Map.entry(KEY_COVER_ROTATION_MODE, "seq"),
            Map.entry(KEY_COVER_ROTATION_INTERVAL, "6"),
            Map.entry(KEY_CG_ROTATION_MODE, "autoNext"),
            Map.entry(KEY_CG_ROTATION_ORDER, "seq"),
            Map.entry(KEY_CG_ROTATION_INTERVAL, "6"),
            Map.entry(KEY_ROTATION_ENABLED, "true"),
            Map.entry(KEY_COVER_MAX_COUNT, "8"),
            Map.entry(KEY_CG_MAX_COUNT, "5"),
            Map.entry(KEY_IMAGE_MAX_MB, "8"),
            Map.entry(KEY_VIDEO_MAX_MB, "120"),
            Map.entry(KEY_IMAGE_VARIANT, "true"),
            Map.entry(KEY_DEFAULT_MEDIA_MODE, "cg"),
            Map.entry(KEY_HOME_HERO_SIZE, "medium"),
            Map.entry(KEY_COMPANY_ENABLED, "true"),
            Map.entry(KEY_LOCALIZATION_ENABLED, "true"),
            Map.entry(KEY_CANVAS_CALIBRATE_MODE, "auto"),
            Map.entry(KEY_ARTIFACT_NEXUS, "http://127.0.0.1:8081/repository/galworld-artifacts"),
            Map.entry(KEY_ARTIFACT_MIRRORS, "https://gh-proxy.com,https://ghfast.top"),
            // 默认留空：仓库地址随部署环境由 GALWORLD_REPO_URL / galworld.repo-url 提供，
            // 代码里不写死任何内网域名。
            Map.entry(KEY_REPO_URL, ""),
            Map.entry(KEY_REPO_URL_PUBLIC, "true"),
            // 同样留空：更新源由 GALWORLD_UPDATE_API_BASE / _REPO / _WEB_BASE 提供
            Map.entry(KEY_UPDATE_API_BASE, ""),
            Map.entry(KEY_UPDATE_REPO, ""),
            Map.entry(KEY_UPDATE_WEB_BASE, ""),
            Map.entry(KEY_LANGUAGE, I18nService.DEFAULT_LOCALE));

    /** 运维类设置：只允许在 /api/admin/settings 修改，普通设置接口拒绝 */
    public static final java.util.Set<String> ADMIN_KEYS = java.util.Set.of(
            KEY_COVER_MAX_COUNT,
            KEY_CG_MAX_COUNT,
            KEY_IMAGE_MAX_MB,
            KEY_VIDEO_MAX_MB,
            KEY_IMAGE_VARIANT,
            KEY_ARTIFACT_NEXUS,
            KEY_ARTIFACT_MIRRORS,
            KEY_REPO_URL,
            KEY_REPO_URL_PUBLIC,
            KEY_UPDATE_API_BASE,
            KEY_UPDATE_REPO,
            KEY_UPDATE_WEB_BASE);

    /** 容器层 multipart 上限（见 application.yml 的 512MB），业务上限不得超过它 */
    private static final int MAX_IMAGE_MB_CEILING = 64;
    private static final int MAX_VIDEO_MB_CEILING = 500;
    private static final int MAX_COUNT_CEILING = 30;

    private final SettingRepository repo;
    private final ApplicationEventPublisher publisher;
    /** 语言包服务：校验语言 id 是否已安装，并在切换默认语言后刷新缓存 */
    private final I18nService i18n;

    /**
     * application.yml / 环境变量里配置的元件仓库地址（GALWORLD_ARTIFACT_NEXUS）。
     *
     * 它是 {@code artifactNexusBase} 的「默认值」，只在设置里没有显式改动时生效。
     * 不能只在 DEFAULTS 里写死 127.0.0.1：那样页面上显示的和实际用的会不一致，
     * 用户一次「原样保存」就把内网地址固化成库里的 127.0.0.1，压掉环境变量。
     */
    @org.springframework.beans.factory.annotation.Value("${galworld.artifacts.nexus-base:}")
    private String configuredNexusBase = "";

    /**
     * 部署环境里配置的仓库地址（GALWORLD_REPO_URL / galworld.repo-url）。
     *
     * 与元件仓库地址同理：它是 {@code repoUrl} 的默认值，设置里没有显式改动时才生效，
     * 这样换环境不用改库。
     */
    @org.springframework.beans.factory.annotation.Value("${galworld.repo-url:}")
    private String configuredRepoUrl = "";

    /**
     * 部署环境里配置的更新源（GALWORLD_UPDATE_API_BASE / _REPO / _WEB_BASE）。
     *
     * <p>同上：这三个是 {@code updateApiBase} / {@code updateRepo} / {@code updateWebBase}
     * 的默认值。设置里没有显式改动时用环境变量，改过之后以设置里的值为准，
     * 这样换镜像站不用改库、也不用重启。
     */
    @org.springframework.beans.factory.annotation.Value("${galworld.update.api-base:}")
    private String configuredUpdateApiBase = "";

    @org.springframework.beans.factory.annotation.Value("${galworld.update.repo:}")
    private String configuredUpdateRepo = "";

    @org.springframework.beans.factory.annotation.Value("${galworld.update.web-base:}")
    private String configuredUpdateWebBase = "";

    public SettingsService(SettingRepository repo, ApplicationEventPublisher publisher,
                           I18nService i18n) {
        this.repo = repo;
        this.publisher = publisher;
        this.i18n = i18n;
    }

    /** 测试用：替换配置的元件仓库地址 */
    void setConfiguredNexusBase(String value) {
        this.configuredNexusBase = value == null ? "" : value;
    }

    /** 测试用：替换配置的仓库地址 */
    void setConfiguredRepoUrl(String value) {
        this.configuredRepoUrl = value == null ? "" : value;
    }

    /** 测试用：替换配置的更新源默认值 */
    void setConfiguredUpdateSource(String apiBase, String repo, String webBase) {
        this.configuredUpdateApiBase = apiBase == null ? "" : apiBase;
        this.configuredUpdateRepo = repo == null ? "" : repo;
        this.configuredUpdateWebBase = webBase == null ? "" : webBase;
    }

    /** 某键在「没有显式设置」时的取值 */
    private String defaultOf(String key) {
        if (KEY_ARTIFACT_NEXUS.equals(key) && configuredNexusBase != null
                && !configuredNexusBase.isBlank()) {
            return configuredNexusBase.replaceAll("/+$", "");
        }
        if (KEY_REPO_URL.equals(key) && configuredRepoUrl != null
                && !configuredRepoUrl.isBlank()) {
            return configuredRepoUrl.trim().replaceAll("/+$", "");
        }
        if (KEY_UPDATE_API_BASE.equals(key) && configuredUpdateApiBase != null
                && !configuredUpdateApiBase.isBlank()) {
            return configuredUpdateApiBase.trim().replaceAll("/+$", "");
        }
        if (KEY_UPDATE_REPO.equals(key) && configuredUpdateRepo != null
                && !configuredUpdateRepo.isBlank()) {
            return configuredUpdateRepo.trim().replaceAll("^/+|/+$", "");
        }
        if (KEY_UPDATE_WEB_BASE.equals(key) && configuredUpdateWebBase != null
                && !configuredUpdateWebBase.isBlank()) {
            return configuredUpdateWebBase.trim().replaceAll("/+$", "");
        }
        return DEFAULTS.get(key);
    }

    /** 老库只有 rotationMode/rotationInterval：首次升级时复制成封面与 CG 两份规则 */
    @PostConstruct
    void migrateLegacyRotation() {
        copyIfMissing(KEY_ROTATION_MODE, KEY_COVER_ROTATION_MODE);
        copyIfMissing(KEY_ROTATION_INTERVAL, KEY_COVER_ROTATION_INTERVAL);
        copyIfMissing(KEY_ROTATION_INTERVAL, KEY_CG_ROTATION_INTERVAL);

        // CG 从旧的 seq/random/videoEnd 升级成「一级行为 + 二级顺序」
        String mode = repo.findById(KEY_CG_ROTATION_MODE)
                .map(Setting::getValue).orElse(null);
        if (mode == null) {
            String old = repo.findById(KEY_ROTATION_MODE)
                    .map(Setting::getValue).orElse("seq");
            repo.save(new Setting(KEY_CG_ROTATION_MODE, "autoNext"));
            repo.save(new Setting(KEY_CG_ROTATION_ORDER,
                    "random".equals(old) ? "random" : "seq"));
        } else if (mode.equals("seq") || mode.equals("random") || mode.equals("videoEnd")) {
            repo.save(new Setting(KEY_CG_ROTATION_MODE, "autoNext"));
            repo.save(new Setting(KEY_CG_ROTATION_ORDER,
                    "random".equals(mode) ? "random" : "seq"));
        }
        if (repo.findById(KEY_CG_ROTATION_ORDER).isEmpty()) {
            repo.save(new Setting(KEY_CG_ROTATION_ORDER, "seq"));
        }
    }

    private void copyIfMissing(String from, String to) {
        if (repo.findById(to).isPresent()) {
            return;
        }
        repo.findById(from).ifPresent(s -> repo.save(new Setting(to, s.getValue())));
    }

    @Transactional(readOnly = true)
    public Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : DEFAULTS.keySet()) {
            out.put(key, defaultOf(key));
        }
        repo.findAll().forEach(s -> out.put(s.getKey(), s.getValue()));
        return out;
    }

    @Transactional(readOnly = true)
    public String get(String key) {
        return repo.findById(key).map(Setting::getValue).orElseGet(() -> defaultOf(key));
    }

    @Transactional(readOnly = true)
    public int idleStopMinutes() {
        String v = get(KEY_IDLE_STOP_MINUTES);
        try {
            return Math.max(0, Integer.parseInt(v.trim()));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public boolean rotationEnabled() {
        return !"false".equalsIgnoreCase(get(KEY_ROTATION_ENABLED));
    }

    /** 公司维度是否展示；默认开启，仅显式 false 时关闭 */
    public boolean companyEnabled() {
        return !"false".equalsIgnoreCase(get(KEY_COMPANY_ENABLED));
    }

    /** 汉化组维度是否展示；默认开启，仅显式 false 时关闭 */
    public boolean localizationEnabled() {
        return !"false".equalsIgnoreCase(get(KEY_LOCALIZATION_ENABLED));
    }

    /** 是否生成派生显示图；默认开启，仅显式 false 时关闭 */
    public boolean imageVariantEnabled() {
        return !"false".equalsIgnoreCase(get(KEY_IMAGE_VARIANT));
    }

    /** 进入游玩页是否自动校准画布；只有显式 manual 时关闭 */
    public boolean canvasAutoCalibrate() {
        return !"manual".equalsIgnoreCase(get(KEY_CANVAS_CALIBRATE_MODE));
    }

    public String coverRotationMode() {
        return get(KEY_COVER_ROTATION_MODE);
    }

    public int coverRotationInterval() {
        return intSetting(KEY_COVER_ROTATION_INTERVAL, 6, 1, 86400);
    }

    public String cgRotationMode() {
        return get(KEY_CG_ROTATION_MODE);
    }

    public String cgRotationOrder() {
        return get(KEY_CG_ROTATION_ORDER);
    }

    public int cgRotationInterval() {
        return intSetting(KEY_CG_ROTATION_INTERVAL, 6, 1, 86400);
    }

    public int coverMaxCount() {
        return intSetting(KEY_COVER_MAX_COUNT, 8, 1, MAX_COUNT_CEILING);
    }

    public int cgMaxCount() {
        return intSetting(KEY_CG_MAX_COUNT, 5, 1, MAX_COUNT_CEILING);
    }

    /** 图片上限（字节） */
    public long imageMaxBytes() {
        return intSetting(KEY_IMAGE_MAX_MB, 8, 1, MAX_IMAGE_MB_CEILING) * 1024L * 1024L;
    }

    /** 视频上限（字节） */
    public long videoMaxBytes() {
        return intSetting(KEY_VIDEO_MAX_MB, 120, 1, MAX_VIDEO_MB_CEILING) * 1024L * 1024L;
    }

    /** 游戏页默认展示：cover=只显示封面 / cg=先封面，首个视频就绪后切 CG */
    public String defaultMediaMode() {
        return "cover".equalsIgnoreCase(get(KEY_DEFAULT_MEDIA_MODE)) ? "cover" : "cg";
    }

    /** 首页推荐位档位：medium=大图，off=只留横条；未知值回退 medium */
    public String homeHeroSize() {
        String v = get(KEY_HOME_HERO_SIZE);
        return HERO_SIZES.contains(v) ? v : "medium";
    }

    /** 元件仓库根地址（末尾不带斜杠），用于拼 manifest.json 与元件相对路径 */
    public String artifactNexusBase() {
        return get(KEY_ARTIFACT_NEXUS).trim();
    }

    /** 项目代码仓库地址（末尾不带斜杠），显示在「设置 → 关于」 */
    public String repoUrl() {
        return get(KEY_REPO_URL).trim();
    }

    /** 更新源接口根地址（末尾不带斜杠），例如 https://gitee.com/api/v5/repos */
    public String updateApiBase() {
        return get(KEY_UPDATE_API_BASE).trim().replaceAll("/+$", "");
    }

    /** 更新源仓库路径 owner/repo（两端不带斜杠） */
    public String updateRepo() {
        return get(KEY_UPDATE_REPO).trim().replaceAll("^/+|/+$", "");
    }

    /** 更新源仓库网页根地址（末尾不带斜杠），用于拼发布页链接 */
    public String updateWebBase() {
        return get(KEY_UPDATE_WEB_BASE).trim().replaceAll("/+$", "");
    }

    /** 公开的 /api/health 是否下发仓库地址；默认公开，仅显式 false 时隐藏 */
    public boolean repoUrlPublic() {
        return !"false".equalsIgnoreCase(get(KEY_REPO_URL_PUBLIC).trim());
    }

    /** 元件镜像前缀列表：下载失败时按顺序回退；空列表表示只用 Nexus */
    public java.util.List<String> artifactMirrors() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : get(KEY_ARTIFACT_MIRRORS).split("[,\\n\\r]+")) {
            String v = part.trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private int intSetting(String key, int fallback, int min, int max) {
        String v = get(key);
        try {
            return Math.min(max, Math.max(min, Integer.parseInt(v.trim())));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    @Transactional
    public Map<String, String> update(Map<String, String> patch) {
        if (patch == null || patch.isEmpty()) {
            return all();
        }
        for (Map.Entry<String, String> e : patch.entrySet()) {
            String key = e.getKey();
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (!DEFAULTS.containsKey(key)) {
                throw new IllegalArgumentException(I18n.t("未知设置项: {0}", key));
            }
            if (KEY_SWITCH_MODE.equals(key) && !(value.equals("tab") || value.equals("page"))) {
                throw new IllegalArgumentException(I18n.t("switchMode 只能是 tab 或 page"));
            }
            if (KEY_IDLE_STOP_MINUTES.equals(key)) {
                int n;
                try {
                    n = Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException(I18n.t("idleStopMinutes 必须是整数"));
                }
                if (n < 0 || n > 1440) {
                    throw new IllegalArgumentException(I18n.t("idleStopMinutes 范围 0-1440"));
                }
                value = String.valueOf(n);
            }
            if (KEY_CG_SOUND.equals(key)) {
                String v = value.toLowerCase(java.util.Locale.ROOT);
                if (!(v.equals("true") || v.equals("false"))) {
                    throw new IllegalArgumentException(I18n.t("cgSound 只能是 true 或 false"));
                }
                value = v;
            }
            if (KEY_CANVAS_CALIBRATE_MODE.equals(key)) {
                if (!(value.equals("auto") || value.equals("manual"))) {
                    throw new IllegalArgumentException(
                            I18n.t("canvasCalibrateMode 只能是 auto 或 manual"));
                }
            }
            if (KEY_ROTATION_MODE.equals(key)) {
                if (!(value.equals("seq") || value.equals("random"))) {
                    throw new IllegalArgumentException(I18n.t("rotationMode 只能是 seq 或 random"));
                }
            }
            if (KEY_ROTATION_INTERVAL.equals(key)) {
                int n;
                try {
                    n = Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException(I18n.t("rotationInterval 必须是整数"));
                }
                // 1 秒 – 24 小时
                if (n < 1 || n > 86400) {
                    throw new IllegalArgumentException(I18n.t("rotationInterval 范围 1-86400 秒"));
                }
                value = String.valueOf(n);
            }
            if (KEY_COVER_ROTATION_MODE.equals(key)) {
                if (!(value.equals("seq") || value.equals("random"))) {
                    throw new IllegalArgumentException(I18n.t("coverRotationMode 只能是 seq 或 random"));
                }
            }
            if (KEY_CG_ROTATION_MODE.equals(key)) {
                if (!(value.equals("autoNext") || value.equals("repeat")
                        || value.equals("stop"))) {
                    throw new IllegalArgumentException(
                            I18n.t("cgRotationMode 只能是 autoNext、repeat 或 stop"));
                }
            }
            if (KEY_CG_ROTATION_ORDER.equals(key)) {
                if (!(value.equals("seq") || value.equals("random"))) {
                    throw new IllegalArgumentException(I18n.t("cgRotationOrder 只能是 seq 或 random"));
                }
            }
            if (KEY_COVER_ROTATION_INTERVAL.equals(key)
                    || KEY_CG_ROTATION_INTERVAL.equals(key)) {
                int n = parseInt(value, key);
                if (n < 1 || n > 86400) {
                    throw new IllegalArgumentException(I18n.t("{0} 范围 1-86400 秒", key));
                }
                value = String.valueOf(n);
            }
            if (KEY_ROTATION_ENABLED.equals(key)) {
                String v = value.toLowerCase(java.util.Locale.ROOT);
                if (!(v.equals("true") || v.equals("false"))) {
                    throw new IllegalArgumentException(I18n.t("rotationEnabled 只能是 true 或 false"));
                }
                value = v;
            }
            if (KEY_COMPANY_ENABLED.equals(key) || KEY_LOCALIZATION_ENABLED.equals(key)) {
                String v = value.toLowerCase(java.util.Locale.ROOT);
                if (!(v.equals("true") || v.equals("false"))) {
                    throw new IllegalArgumentException(I18n.t("{0} 只能是 true 或 false", key));
                }
                value = v;
            }
            if (KEY_COVER_MAX_COUNT.equals(key) || KEY_CG_MAX_COUNT.equals(key)) {
                int n = parseInt(value, key);
                if (n < 1 || n > MAX_COUNT_CEILING) {
                    throw new IllegalArgumentException(I18n.t("{0} 范围 1-{1}", key, MAX_COUNT_CEILING));
                }
                value = String.valueOf(n);
            }
            if (KEY_IMAGE_MAX_MB.equals(key)) {
                int n = parseInt(value, key);
                if (n < 1 || n > MAX_IMAGE_MB_CEILING) {
                    throw new IllegalArgumentException(
                            I18n.t("imageMaxMb 范围 1-{0}", MAX_IMAGE_MB_CEILING));
                }
                value = String.valueOf(n);
            }
            if (KEY_VIDEO_MAX_MB.equals(key)) {
                int n = parseInt(value, key);
                if (n < 1 || n > MAX_VIDEO_MB_CEILING) {
                    throw new IllegalArgumentException(
                            I18n.t("videoMaxMb 范围 1-{0}", MAX_VIDEO_MB_CEILING));
                }
                value = String.valueOf(n);
            }
            if (KEY_IMAGE_VARIANT.equals(key)) {
                String v = value.toLowerCase(java.util.Locale.ROOT);
                if (!(v.equals("true") || v.equals("false"))) {
                    throw new IllegalArgumentException(I18n.t("imageVariantEnabled 只能是 true 或 false"));
                }
                value = v;
            }
            if (KEY_DEFAULT_MEDIA_MODE.equals(key)) {
                if (!(value.equals("cover") || value.equals("cg"))) {
                    throw new IllegalArgumentException(I18n.t("defaultMediaMode 只能是 cover 或 cg"));
                }
            }
            if (KEY_HOME_HERO_SIZE.equals(key)) {
                if (!HERO_SIZES.contains(value)) {
                    throw new IllegalArgumentException(I18n.t("homeHeroSize 只能是 medium 或 off"));
                }
            }
            if (KEY_ARTIFACT_NEXUS.equals(key)) {
                // 去掉末尾斜杠，方便下载器直接拼 "/manifest.json"
                value = value.replaceAll("/+$", "");
                if (!value.isEmpty() && !URL_PATTERN.matcher(value).matches()) {
                    throw new IllegalArgumentException(I18n.t("artifactNexusBase 必须是 http(s) 地址"));
                }
            }
            if (KEY_ARTIFACT_MIRRORS.equals(key)) {
                java.util.List<String> parts = new java.util.ArrayList<>();
                for (String part : value.split("[,\\n\\r]+")) {
                    String v = part.trim().replaceAll("/+$", "");
                    if (v.isEmpty()) {
                        continue;
                    }
                    if (!URL_PATTERN.matcher(v).matches()) {
                        throw new IllegalArgumentException(I18n.t("镜像地址必须是 http(s): {0}", v));
                    }
                    parts.add(v);
                }
                value = String.join(",", parts);
            }
            if (KEY_REPO_URL.equals(key)) {
                // 去掉末尾斜杠，链接与复制出来的地址保持一致
                value = value.replaceAll("/+$", "");
                if (!value.isEmpty() && !URL_PATTERN.matcher(value).matches()) {
                    throw new IllegalArgumentException(I18n.t("repoUrl 必须是 http(s) 地址"));
                }
            }
            if (KEY_REPO_URL_PUBLIC.equals(key)) {
                String v = value.toLowerCase(java.util.Locale.ROOT);
                if (!(v.equals("true") || v.equals("false"))) {
                    throw new IllegalArgumentException(I18n.t("repoUrlPublic 只能是 true 或 false"));
                }
                value = v;
            }
            if (KEY_UPDATE_API_BASE.equals(key) || KEY_UPDATE_WEB_BASE.equals(key)) {
                value = value.replaceAll("/+$", "");
                if (!value.isEmpty() && !URL_PATTERN.matcher(value).matches()) {
                    throw new IllegalArgumentException(I18n.t("{0} 必须是 http(s) 地址", key));
                }
            }
            if (KEY_UPDATE_REPO.equals(key)) {
                value = value.replaceAll("^/+|/+$", "");
                if (!value.isEmpty() && !REPO_PATTERN.matcher(value).matches()) {
                    throw new IllegalArgumentException(I18n.t("updateRepo 必须是 owner/repo 形式"));
                }
            }
            if (KEY_LANGUAGE.equals(key)) {
                // 只接受已安装的语言包：写进库里但翻译时找不到，界面会整片显示键名
                String norm = io.github.sander_i.i18n.LocalePack.normalize(value);
                if (norm.isEmpty() || !i18n.exists(norm)) {
                    throw new IllegalArgumentException(
                            I18n.t("language 必须是已安装的语言包: {0}", i18n.packs().stream()
                                    .map(io.github.sander_i.i18n.LocalePack::id).toList()));
                }
                value = norm;
            }
            // 存成默认值等于没设置：删掉这行，让环境变量/内置默认重新生效。
            // 否则页面把当前显示值原样保存一次，就把默认值固化成「显式设置」，
            // 反过来压掉 GALWORLD_ARTIFACT_NEXUS 这类环境变量（2026-11-09 真机踩到）。
            if (value.equals(defaultOf(key))) {
                repo.findById(key).ifPresent(repo::delete);
            } else {
                repo.save(new Setting(key, value));
            }
        }
        // 开关开启时通知回填任务在后台补生成派生图；关闭时不发布（没有要生成的东西）
        if (patch.containsKey(KEY_IMAGE_VARIANT) && imageVariantEnabled()) {
            // 事务提交后才真正发布，保证后台线程的新事务能读到最新值；无事务时直接发布
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                publisher.publishEvent(new ImageVariantEnabledEvent(true));
                            }
                        });
            } else {
                publisher.publishEvent(new ImageVariantEnabledEvent(true));
            }
        }
        // 默认语言变了：翻译服务缓存的是旧值，事务提交后刷新。
        // 提交前刷新会让后台线程读不到新值（与派生图事件同理）。
        if (patch.containsKey(KEY_LANGUAGE)) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                i18n.refreshDefaultLocale();
                            }
                        });
            } else {
                i18n.refreshDefaultLocale();
            }
        }
        return all();
    }

    private int parseInt(String value, String key) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(I18n.t("{0} 必须是整数", key));
        }
    }
}
