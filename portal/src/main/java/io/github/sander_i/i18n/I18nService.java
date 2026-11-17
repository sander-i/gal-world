/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.i18n;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.repo.SettingRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语言包管理：内置 4 套 + 磁盘目录热加载。
 *
 * <p>目录：{@code <root>/data/i18n/<id>.json}。放进去即生效，不需要重启——
 * 每次翻译都按文件修改时间判断是否需要重读，用户上传后刷新页面就能看到。
 *
 * <p>优先级：外部包覆盖同 id 的内置包。这样发布后修正某条翻译不必重新发版；
 * 想恢复内置版本，删掉外部文件即可。
 *
 * <p>兜底链：当前语言 → 站点默认语言（设置里的 {@code language}，默认 {@code en}）
 * → 键名本身。键名会原样出现在页面上，漏翻译一眼可见，不会静默变空白。
 */
@Service
public class I18nService {

    private static final Logger log = LoggerFactory.getLogger(I18nService.class);

    /** 站点默认语言：设置里没有显式选择时用它 */
    public static final String DEFAULT_LOCALE = "en";

    /** 设置里存站点默认语言的键；与 {@code SettingsService.KEY_LANGUAGE} 一致 */
    public static final String KEY_LANGUAGE = "language";

    /** 内置语言 id；新增内置语言要同时加 classpath:i18n/<id>.json */
    public static final List<String> BUILTIN_IDS = List.of("en", "zh-CN", "zh-TW", "ja");

    /** 单条消息上限：语言包是给人写的，超长多半是塞错了内容 */
    private static final int MAX_MESSAGE_LENGTH = 4000;
    /** 键长上限：源码里的文案都很短，超长基本是误把整段消息当键 */
    private static final int MAX_KEY_LENGTH = 300;
    /** 单个语言包消息条数上限 */
    private static final int MAX_MESSAGES = 20000;
    /** 上传文件上限（字节） */
    public static final long MAX_PACK_BYTES = 2L * 1024 * 1024;

    private final PathConfig paths;
    private final SettingRepository settings;

    /** 内置包，启动时读一次 */
    private final Map<String, Map<String, String>> builtin = new LinkedHashMap<>();
    /** 外部包缓存：id → 包 + 文件时间戳，用于判断是否需要重读 */
    private final Map<String, Cached> external = new ConcurrentHashMap<>();
    /** 站点默认语言缓存：settings 每次读库太贵，翻译在异常路径上 */
    private volatile String defaultLocale = DEFAULT_LOCALE;

    private record Cached(LocalePack pack, long modified, long size) {
    }

    public I18nService(PathConfig paths, SettingRepository settings) {
        this.paths = paths;
        this.settings = settings;
    }

    @PostConstruct
    void init() {
        builtin.putAll(PackJson.loadClasspathPacks());
        I18n.bind(this);
        reloadExternal();
        refreshDefaultLocale();
        log.info("语言包已加载：内置 {}，外部 {}，默认语言 {}",
                builtin.keySet(), external.keySet(), defaultLocale);
    }

    /** 站点默认语言（设置里的 language）；未知值回退 en */
    public String defaultLocale() {
        return defaultLocale;
    }

    /**
     * 从设置里刷新默认语言。
     *
     * 设置服务与语言服务互相独立：设置改动后由 {@code SettingsService} 发事件触发，
     * 这里不反向依赖它，避免构造期循环。
     */
    public void refreshDefaultLocale() {
        String raw = null;
        try {
            raw = settings.findById(KEY_LANGUAGE).map(s -> s.getValue()).orElse(null);
        } catch (RuntimeException e) {
            // 启动早期库还没就绪：保持上一次的值
            log.debug("读取站点默认语言失败，沿用 {}", defaultLocale);
        }
        String norm = LocalePack.normalize(raw);
        defaultLocale = norm.isEmpty() || !exists(norm) ? DEFAULT_LOCALE : norm;
    }


    /** 外部语言包目录 */
    public Path packDir() {
        return paths.dataDir().resolve("i18n");
    }

    // ---------------- 翻译 ----------------

    /**
     * 当前语言翻译。
     *
     * 兜底分两种，不能混：
     * <ul>
     *   <li>语言包不存在（请求了一个没装的语言）→ 用站点默认语言</li>
     *   <li>语言包存在但缺这个键（外部包常见）→ 回退中文原文</li>
     * </ul>
     * 后者不回退英文：这个项目的用户以中文玩家为主，缺译处显示原文比显示
     * 英文更好读，也便于一眼看出哪些还没翻译。
     */
    public String translate(String locale, String key, Object... args) {
        Map<String, String> pack = packMessages(LocalePack.normalize(locale));
        if (pack == null) {
            pack = packMessages(defaultLocale);
        }
        if (pack == null) {
            pack = builtin.get(DEFAULT_LOCALE);
        }
        String msg = pack == null ? null : pack.get(key);
        if (msg == null) {
            return key;
        }
        return format(msg, args);
    }

    private Map<String, String> packMessages(String locale) {
        if (locale == null || locale.isEmpty()) {
            return null;
        }
        LocalePack ext = externalPack(locale);
        if (ext != null) {
            return ext.messagesOrEmpty();
        }
        return builtin.get(locale);
    }

    /** MessageFormat 的占位符是 {0}；单引号在 MessageFormat 里是转义符，需先加倍 */
    static String format(String template, Object... args) {
        if (args == null || args.length == 0 || !template.contains("{")) {
            return template;
        }
        try {
            return new MessageFormat(template, Locale.ROOT)
                    .format(java.util.Arrays.stream(args)
                            .map(a -> a == null ? "" : String.valueOf(a))
                            .toArray());
        } catch (IllegalArgumentException e) {
            // 模板里花括号写坏了：原样返回，别把整条消息吞掉
            return template;
        }
    }

    // ---------------- 语言包清单 ----------------

    /** 可用语言（内置 + 外部），按「内置顺序 → 外部 id 字典序」排列 */
    public List<LocalePack> packs() {
        reloadExternal();
        Map<String, LocalePack> out = new LinkedHashMap<>();
        for (String id : BUILTIN_IDS) {
            Map<String, String> msgs = builtin.get(id);
            if (msgs == null) {
                continue;
            }
            out.put(id, new LocalePack(id, builtinName(id), builtinName(id), true,
                    "classpath:i18n/" + id + ".json", msgs));
        }
        external.values().stream()
                .map(Cached::pack)
                .sorted(Comparator.comparing(LocalePack::id))
                .forEach(p -> out.put(p.id(), p));
        return new ArrayList<>(out.values());
    }

    /** 语言包摘要，供后台管理页 */
    public List<Map<String, Object>> summaries() {
        return packs().stream().map(LocalePack::summary).toList();
    }

    /** 某语言的完整消息表；不存在时返回空表 */
    public Map<String, String> messages(String locale) {
        String id = LocalePack.normalize(locale);
        Map<String, String> ext = packMessages(id);
        return ext == null ? Map.of() : ext;
    }

    /** 语言是否存在 */
    public boolean exists(String locale) {
        String id = LocalePack.normalize(locale);
        return packMessages(id) != null;
    }

    /** 语言的自称；未知语言回退到 id */
    public String displayName(String locale) {
        String id = LocalePack.normalize(locale);
        LocalePack ext = externalPack(id);
        if (ext != null) {
            return ext.name();
        }
        return builtinName(id);
    }

    private String builtinName(String id) {
        return switch (id) {
            case "en" -> "English";
            case "zh-CN" -> "简体中文";
            case "zh-TW" -> "繁體中文";
            case "ja" -> "日本語";
            default -> id;
        };
    }

    // ---------------- 外部包读写 ----------------

    /** 重新扫描外部目录，只读变化过的文件 */
    public void reloadExternal() {
        Path dir = packDir();
        List<Path> files = PackJson.listFiles(dir);
        Map<String, Cached> fresh = new LinkedHashMap<>();
        for (Path f : files) {
            String fileName = f.getFileName().toString();
            String fallbackId = fileName.substring(0, fileName.length() - ".json".length());
            try {
                long mod = Files.getLastModifiedTime(f).toMillis();
                long size = Files.size(f);
                Cached old = external.get(LocalePack.normalize(fallbackId));
                if (old != null && old.modified() == mod && old.size() == size) {
                    fresh.put(old.pack().id(), old);
                    continue;
                }
                LocalePack pack = PackJson.parse(Files.readAllBytes(f), fallbackId,
                        f.toString(), false);
                fresh.put(pack.id(), new Cached(pack, mod, size));
                log.info("外部语言包已加载：{}（{} 条）", pack.id(), pack.messagesOrEmpty().size());
            } catch (IOException | RuntimeException e) {
                log.warn("外部语言包读取失败，已跳过 {}：{}", f, e.getMessage());
            }
        }
        // 被删掉的文件从缓存里移除
        external.keySet().retainAll(fresh.keySet());
        external.putAll(fresh);
    }

    private LocalePack externalPack(String id) {
        reloadExternal();
        Cached c = external.get(id);
        return c == null ? null : c.pack();
    }

    /**
     * 保存外部语言包。
     *
     * @param id       语言 id（同时决定文件名）
     * @param name     显示名；空时用 id
     * @param messages 键值表
     */
    public LocalePack save(String id, String name, Map<String, String> messages)
            throws IOException {
        String norm = LocalePack.normalize(id);
        if (!LocalePack.isValidId(norm)) {
            throw new IllegalArgumentException("语言包 id 非法: " + id);
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("语言包没有任何消息");
        }
        if (messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException("语言包消息条数超过上限 " + MAX_MESSAGES);
        }
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : messages.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().trim();
            String v = e.getValue() == null ? "" : e.getValue();
            if (k.isEmpty()) {
                throw new IllegalArgumentException("语言包包含空键");
            }
            // 键是中文原文，不能限定 ASCII：只挡控制字符与超长，
            // 否则任何非英文语言包都装不进来。
            if (k.length() > MAX_KEY_LENGTH || k.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("语言包键名非法: " + k);
            }
            if (v.length() > MAX_MESSAGE_LENGTH) {
                throw new IllegalArgumentException("消息过长: " + k);
            }
            clean.put(k, v);
        }
        String display = name == null || name.isBlank() ? norm : name.trim();
        Path file = packDir().resolve(norm + ".json");
        PackJson.write(file, norm, display, display, clean);
        external.remove(norm);
        reloadExternal();
        LocalePack saved = externalPack(norm);
        if (saved == null) {
            throw new IOException("语言包写入后读取失败: " + file);
        }
        return saved;
    }

    /** 解析并保存上传的 JSON；格式错误抛 IllegalArgumentException */
    public LocalePack importJson(String json, String fallbackId) throws IOException {
        byte[] bytes = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PACK_BYTES) {
            throw new IllegalArgumentException("语言包文件超过 " + (MAX_PACK_BYTES / 1024) + " KB");
        }
        LocalePack parsed;
        try {
            parsed = PackJson.parse(bytes, fallbackId, "", false);
        } catch (IOException e) {
            // 内容格式错误归为参数问题：调用方（控制器）只需捕 IllegalArgumentException
            throw new IllegalArgumentException("语言包解析失败: " + e.getMessage());
        }
        if (!LocalePack.isValidId(parsed.id())) {
            throw new IllegalArgumentException("语言包 id 非法: " + parsed.id());
        }
        return save(parsed.id(), parsed.name(), parsed.messagesOrEmpty());
    }

    // ---------------- 第三方格式导入 ----------------

    /** 项目已知的全部源文键（中文原文），由内置包定义 */
    public java.util.Set<String> sourceKeys() {
        Map<String, String> zh = builtin.get("zh-CN");
        if (zh != null) {
            return zh.keySet();
        }
        // 内置包缺失时退回英文包：键相同，只是值是译文
        Map<String, String> en = builtin.get(DEFAULT_LOCALE);
        return en == null ? java.util.Set.of() : en.keySet();
    }

    /** 导入结果：装了哪些、跳过了多少、占位符对不上的键 */
    public record ImportResult(LocalePack pack, String format, int imported, int skipped,
                               java.util.List<String> skippedSample,
                               java.util.List<String> missingSample,
                               java.util.List<String> placeholderMismatches) {
    }

    /**
     * 从市面通用格式导入语言包。
     *
     * <p>格式按扩展名与内容嗅探（见 {@link LocaleImporter#detect}）。符号键格式
     * （i18next 嵌套 JSON、properties、Android XML、.arb、.strings）需要 keyMap
     * 把符号键映射到中文原文；源文即键的格式（.po、XLIFF、CSV）不需要。
     *
     * <p>装进来的键做占位符校验：编号对不上会让页面上直接显示 {@code {0}}，
     * 但不会因此拒绝安装——译者可能只是漏了一处，其余几百条仍可用。
     *
     * @param keyMap 符号键 → 中文原文；可为 null
     */
    public ImportResult importAny(String content, String fileName, String fallbackId,
                                  Map<String, String> keyMap) throws IOException {
        byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PACK_BYTES) {
            throw new IllegalArgumentException(
                    "语言包文件超过 " + (MAX_PACK_BYTES / 1024) + " KB");
        }
        String format = LocaleImporter.detect(fileName, content);
        LocaleImporter.Parsed parsed = LocaleImporter.parse(format, content, fallbackId);
        String id = parsed.id() == null || parsed.id().isBlank() ? fallbackId : parsed.id();
        if (!LocalePack.isValidId(LocalePack.normalize(id))) {
            throw new IllegalArgumentException(
                    "语言包 id 非法: " + id + "（文件名或包内 id 需要是 2-16 位字母数字）");
        }
        LocaleImporter.Resolved resolved = LocaleImporter.resolve(
                parsed.entries(), keyMap, sourceKeys());
        if (resolved.messages().isEmpty()) {
            throw new IllegalArgumentException(
                    "没有可用的条目：这个文件的键与项目源文对不上。"
                            + "符号键格式需要提供 keyMap（符号键 → 中文原文）");
        }
        java.util.List<String> mismatches =
                LocaleImporter.placeholderMismatches(resolved.messages());
        LocalePack saved = save(LocalePack.normalize(id), parsed.name(), resolved.messages());
        int imported = resolved.messages().size();
        int skipped = parsed.entries().size() - imported;
        log.info("语言包导入：{}（{}）{} 条，跳过 {} 条，占位符不一致 {} 条",
                saved.id(), format, imported, skipped, mismatches.size());
        return new ImportResult(saved, format, imported, skipped,
                resolved.skippedSample(), resolved.missingSample(), mismatches);
    }

    /**
     * 导出为某格式，供译者在市场工具里编辑后导回。
     *
     * @param format json / po / xliff / csv / properties
     */
    public String export(String locale, String format) {
        String id = LocalePack.normalize(locale);
        Map<String, String> msgs = messages(id);
        if (msgs.isEmpty()) {
            throw new IllegalArgumentException("没有这个语言包: " + locale);
        }
        String name = displayName(id);
        return switch (format == null ? "json" : format) {
            case "po" -> exportPo(msgs);
            case "xliff" -> exportXliff(msgs, id, name);
            case "csv" -> exportCsv(msgs);
            case "properties" -> exportProperties(msgs);
            default -> exportJson(msgs, id, name);
        };
    }

    private static String exportJson(Map<String, String> msgs, String id, String name) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root =
                    com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                            .createObjectNode();
            root.put("id", id);
            root.put("name", name);
            com.fasterxml.jackson.databind.node.ObjectNode m = root.putObject("messages");
            new java.util.TreeMap<>(msgs).forEach(m::put);
            return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PO：msgid 是中文原文，msgstr 是译文；头部写语言与字符集 */
    private static String exportPo(Map<String, String> msgs) {
        StringBuilder sb = new StringBuilder();
        sb.append("msgid \"\"\n");
        sb.append("msgstr \"\"\n");
        sb.append("\"Content-Type: text/plain; charset=UTF-8\\n\"\n\n");
        new java.util.TreeMap<>(msgs).forEach((k, v) -> {
            sb.append("msgid \"").append(escapePo(k)).append("\"\n");
            sb.append("msgstr \"").append(escapePo(v)).append("\"\n\n");
        });
        return sb.toString();
    }

    private static String escapePo(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r");
    }

    /** XLIFF 1.2：Crowdin / Weblate / Lokalise / Transifex 都能读 */
    private static String exportXliff(Map<String, String> msgs, String id, String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<xliff version=\"1.2\" xmlns=\"urn:oasis:names:tc:xliff:document:1.2\">\n");
        sb.append("  <file source-language=\"zh-CN\" target-language=\"").append(id)
                .append("\" original=\"gal-world\" datatype=\"plaintext\">\n");
        sb.append("    <header><tool tool-id=\"gal-world\" tool-name=\"Gal World\"/></header>\n");
        sb.append("    <body>\n");
        int i = 0;
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(msgs).entrySet()) {
            sb.append("      <trans-unit id=\"").append(++i).append("\">\n");
            sb.append("        <source>").append(escapeXml(e.getKey())).append("</source>\n");
            sb.append("        <target>").append(escapeXml(e.getValue())).append("</target>\n");
            sb.append("      </trans-unit>\n");
        }
        sb.append("    </body>\n  </file>\n</xliff>\n");
        return sb.toString();
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** CSV：两列「原文,译文」，RFC 4180 引号规则 */
    private static String exportCsv(Map<String, String> msgs) {
        StringBuilder sb = new StringBuilder("source,target\n");
        new java.util.TreeMap<>(msgs).forEach((k, v) ->
                sb.append(csvCell(k)).append(',').append(csvCell(v)).append('\n'));
        return sb.toString();
    }

    private static String csvCell(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    /** properties：非 ASCII 转义成反斜杠 u 加四位十六进制，Java 各版本都能读 */
    private static String exportProperties(Map<String, String> msgs) {
        StringBuilder sb = new StringBuilder();
        new java.util.TreeMap<>(msgs).forEach((k, v) ->
                sb.append(escapeProp(k)).append('=').append(escapeProp(v)).append('\n'));
        return sb.toString();
    }

    private static String escapeProp(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                case '=', ':', '#' -> sb.append('\\').append(c);
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }


    /** 删除外部语言包；内置语言不可删，删了只会移除同 id 的外部覆盖 */
    public void delete(String locale) throws IOException {
        String id = LocalePack.normalize(locale);
        Path file = packDir().resolve(id + ".json");
        if (Files.isRegularFile(file)) {
            Files.delete(file);
        }
        external.remove(id);
        reloadExternal();
    }

    /** 是否是外部包（可删） */
    public boolean isExternal(String locale) {
        return externalPack(LocalePack.normalize(locale)) != null;
    }

    /** 语言包目录的路径描述，供页面提示用户手动放置 */
    public String directoryHint() {
        return packDir().toAbsolutePath().toString();
    }
}
