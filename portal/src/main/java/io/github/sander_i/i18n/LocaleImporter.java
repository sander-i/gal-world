/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把市面上的常见语言包格式解析成「键 → 译文」。
 *
 * <p>本项目的键是中文原文，所以只有「源文本身就是键」的格式能直接映射：
 * gettext {@code .po}、XLIFF、以源文为键的 CSV。符号键格式（i18next / vue-i18n
 * 嵌套 JSON、Java {@code .properties}、Android {@code strings.xml}、Flutter
 * {@code .arb}、Apple {@code .strings}）需要一份 keyMap 把符号键映射到中文原文，
 * 见 {@link #resolve}。
 *
 * <p>所有解析都在内存里完成，输入上限由 {@link I18nService#MAX_PACK_BYTES} 控制。
 * XML 一律禁用 DTD 与外部实体：语言包来自外部，不能让它读本机文件。
 */
final class LocaleImporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 解析结果；entries 的键可能仍是符号键，交给 {@link #resolve} 归一 */
    record Parsed(String id, String name, String format, Map<String, String> entries) {
    }

    private LocaleImporter() {
    }

    // ---------------- 格式识别 ----------------

    /**
     * 按扩展名识别；认不出时按内容嗅探。
     *
     * @return 小写格式名，见 {@link #parse}
     */
    static String detect(String fileName, String content) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        switch (ext) {
            case "po", "pot":
                return "po";
            case "xlf", "xliff":
                return "xliff";
            case "properties":
                return "properties";
            case "strings":
                return "strings";
            case "arb":
                return "arb";
            case "csv":
                return "csv";
            case "tsv":
                return "tsv";
            case "json":
                return "json";
            case "xml":
                // Android 的 strings.xml 与其它 XML 混在同一扩展名下
                return content != null && content.contains("<resources")
                        ? "android" : "xml";
            default:
                break;
        }
        String head = content == null ? "" : content.stripLeading();
        if (head.startsWith("msgid") || head.contains("\nmsgid ")) {
            return "po";
        }
        if (head.startsWith("<?xml") || head.startsWith("<xliff")
                || head.startsWith("<resources")) {
            if (head.contains("<resources")) {
                return "android";
            }
            return "xliff";
        }
        if (head.startsWith("{") || head.startsWith("[")) {
            return "json";
        }
        if (head.startsWith("#") || head.contains("=") || head.contains(":")) {
            return "properties";
        }
        throw new IllegalArgumentException(
                "无法识别的语言包格式；支持 .json / .po / .xlf / .properties / .strings"
                        + " / .xml / .arb / .csv");
    }

    // ---------------- 解析 ----------------

    /**
     * 按格式解析。
     *
     * @param fallbackId 文件名推断出的 id，包内没写 id 时用它
     */
    static Parsed parse(String format, String content, String fallbackId) {
        return switch (format) {
            case "po" -> parsePo(content, fallbackId);
            case "xliff", "xml" -> parseXliff(content, fallbackId);
            case "android" -> parseAndroid(content, fallbackId);
            case "arb" -> parseArb(content, fallbackId);
            case "properties" -> parseProperties(content, fallbackId);
            case "strings" -> parseAppleStrings(content, fallbackId);
            case "csv", "tsv" -> parseCsv(content, format, fallbackId);
            default -> parseJson(content, fallbackId);
        };
    }

    // ---------------- gettext .po ----------------

    private static final Pattern PO_ENTRY = Pattern.compile("^(msgctxt|msgid|msgid_plural|msgstr)(\\[\\d+])?\\s+\"(.*)\"$");
    private static final Pattern PO_CONT = Pattern.compile("^\"(.*)\"$");

    /**
     * .po 的 msgid 就是中文原文，能直接当键用。
     *
     * 复数条目（{@code msgid_plural}）只取 {@code msgstr[0]}：本项目没有复数形式，
     * 中文原文也不区分单复数。
     */
    static Parsed parsePo(String content, String fallbackId) {
        Map<String, String> out = new LinkedHashMap<>();
        String id = "";
        String msgctxt = null;
        String msgid = null;
        String msgstr = null;
        String field = null;

        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        for (String rawLine : lines) {
            String line = rawLine.replace("\r", "");
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (msgid != null && msgstr != null) {
                    putPo(out, msgctxt, msgid, msgstr);
                }
                msgctxt = null;
                msgid = null;
                msgstr = null;
                field = null;
                continue;
            }
            Matcher m = PO_ENTRY.matcher(trimmed);
            if (m.matches()) {
                String kind = m.group(1);
                String index = m.group(2);
                String value = unescapePo(m.group(3));
                if ("msgctxt".equals(kind)) {
                    msgctxt = value;
                    field = "msgctxt";
                } else if ("msgid".equals(kind)) {
                    msgid = value;
                    field = "msgid";
                } else if ("msgid_plural".equals(kind)) {
                    field = "ignore";
                } else if (index == null) {
                    msgstr = value;
                    field = "msgstr";
                } else {
                    // msgstr[0] 是单数形式；[1] 起忽略
                    if ("[0]".equals(index)) {
                        msgstr = value;
                    }
                    field = "ignore";
                }
                continue;
            }
            Matcher c = PO_CONT.matcher(trimmed);
            if (c.matches() && field != null) {
                String value = unescapePo(c.group(1));
                switch (field) {
                    case "msgctxt" -> msgctxt = (msgctxt == null ? "" : msgctxt) + value;
                    case "msgid" -> msgid = (msgid == null ? "" : msgid) + value;
                    case "msgstr" -> msgstr = (msgstr == null ? "" : msgstr) + value;
                    default -> { }
                }
            }
        }
        if (msgid != null && msgstr != null) {
            putPo(out, msgctxt, msgid, msgstr);
        }
        // 头部条目（msgid ""）的 msgstr 里带 "Language: ko" 之类的元信息
        if (id.isEmpty()) {
            id = languageFromPoHeader(content);
        }
        return new Parsed(id.isEmpty() ? fallbackId : id, "", "po", out);
    }

    private static void putPo(Map<String, String> out, String ctx, String msgid, String msgstr) {
        if (msgid.isEmpty() || msgstr.isEmpty()) {
            return;
        }
        // 本项目没有 msgctxt 维度；有上下文的条目按原文本身存，
        // 重复的原文后写覆盖先写，与 gettext 的「同 msgid 唯一」一致。
        out.put(msgid, msgstr);
    }

    private static String languageFromPoHeader(String content) {
        Matcher m = Pattern.compile("Language:\\s*([A-Za-z]{2,3}(?:[-_][A-Za-z]{2,4})?)")
                .matcher(content);
        return m.find() ? m.group(1).replace('_', '-') : "";
    }

    /** PO 字符串转义：\\n \\t \\r \\" \\\\ */
    private static String unescapePo(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }

    // ---------------- XLIFF ----------------

    /**
     * XLIFF 的 {@code <source>} 是原文，{@code <target>} 是译文，直接映射。
     *
     * Crowdin / Weblate / Lokalise / Transifex 都能导出这个格式，是跨工具最通用的一种。
     */
    static Parsed parseXliff(String content, String fallbackId) {
        Document doc = parseXml(content);
        Map<String, String> out = new LinkedHashMap<>();
        String id = "";

        NodeList files = doc.getElementsByTagName("file");
        if (files.getLength() > 0) {
            Element file = (Element) files.item(0);
            String target = file.getAttribute("target-language");
            if (!target.isBlank()) {
                id = target;
            }
        }

        NodeList units = doc.getElementsByTagName("trans-unit");
        for (int i = 0; i < units.getLength(); i++) {
            Element unit = (Element) units.item(i);
            String source = textOf(unit, "source");
            String target = textOf(unit, "target");
            if (target.isBlank()) {
                // 1.2 里还有 seg-source 表示分段；拿不到 target 就跳过
                continue;
            }
            String key = source.isBlank() ? unit.getAttribute("id") : source;
            if (key.isBlank()) {
                continue;
            }
            out.put(key, target);
        }
        return new Parsed(id.isEmpty() ? fallbackId : id, "", "xliff", out);
    }

    /** 取子元素文本；内联标记（ph / g / x）一并展开 */
    private static String textOf(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() == 0) {
            return "";
        }
        return list.item(0).getTextContent().strip();
    }

    // ---------------- Android strings.xml ----------------

    static Parsed parseAndroid(String content, String fallbackId) {
        Document doc = parseXml(content);
        Map<String, String> out = new LinkedHashMap<>();
        NodeList strings = doc.getElementsByTagName("string");
        for (int i = 0; i < strings.getLength(); i++) {
            Element e = (Element) strings.item(i);
            if ("false".equals(e.getAttribute("translatable"))) {
                continue;
            }
            String name = e.getAttribute("name");
            if (name.isBlank()) {
                continue;
            }
            out.put(name, unescapeAndroid(e.getTextContent()));
        }
        return new Parsed(fallbackId, "", "android", out);
    }

    private static String unescapeAndroid(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ex) {
                            sb.append("\\u");
                        }
                    } else {
                        sb.append("\\u");
                    }
                }
                case '\'' -> sb.append('\'');
                case '"' -> sb.append('"');
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }

    // ---------------- Flutter .arb ----------------

    /**
     * .arb 是 JSON，但每条消息可带一个 {@code @key} 元数据对象，要跳过。
     * 复数/占位符放在元数据里，这里只取正文。
     */
    static Parsed parseArb(String content, String fallbackId) {
        JsonNode root = readJson(content);
        Map<String, String> out = new LinkedHashMap<>();
        String id = root.path("@@locale").asText("").trim();
        root.fields().forEachRemaining(e -> {
            String key = e.getKey();
            if (key.startsWith("@") || key.startsWith("@@")) {
                return;
            }
            if (e.getValue().isTextual()) {
                out.put(key, e.getValue().asText());
            }
        });
        return new Parsed(id.isEmpty() ? fallbackId : id, "", "arb", out);
    }

    // ---------------- Java .properties ----------------

    /**
     * Java properties：反斜杠 u 加四位十六进制的转义与反斜杠转义交给 JDK 的 Properties 处理。
     *
     * 注意 ISO-8859-1 与 UTF-8 两种历史写法：用 String 读入（已按 UTF-8 解码），
     * Properties 只再做转义展开，UTF-8 直写的文件也能读。
     */
    static Parsed parseProperties(String content, String fallbackId) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(content));
        } catch (IOException e) {
            throw new IllegalArgumentException("properties 解析失败: " + e.getMessage());
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            out.put(name, props.getProperty(name));
        }
        // 语言 id 的约定键名：这几项不该被当成待翻译的文案
        String id = "";
        for (String meta : new String[]{"_locale", "locale", "language", "lang"}) {
            String v = props.getProperty(meta);
            if (v != null && !v.isBlank()) {
                id = v.strip();
                out.remove(meta);
                break;
            }
        }
        return new Parsed(id.isBlank() ? fallbackId : id.strip(), "", "properties", out);
    }

    // ---------------- Apple .strings ----------------

    /**
     * Apple 的 {@code "key" = "value";} 格式。
     *
     * 用字符扫描而不是正则：值里可以有转义引号、注释与跨行文本。
     */
    static Parsed parseAppleStrings(String content, String fallbackId) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        int n = content.length();
        while (i < n) {
            // 跳过空白与注释
            char c = content.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && content.charAt(i + 1) == '/') {
                while (i < n && content.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && content.charAt(i + 1) == '*') {
                int end = content.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c != '"') {
                i++;
                continue;
            }
            StringBuilder key = new StringBuilder();
            i = readQuoted(content, i, key);
            // 等号
            while (i < n && content.charAt(i) != '=' && content.charAt(i) != '"') {
                i++;
            }
            if (i < n && content.charAt(i) == '"') {
                // 没有等号：上一条是孤立字符串，忽略
                StringBuilder skip = new StringBuilder();
                i = readQuoted(content, i, skip);
                continue;
            }
            if (i >= n) {
                break;
            }
            i++;  // 吃掉 '='
            while (i < n && Character.isWhitespace(content.charAt(i))) {
                i++;
            }
            if (i >= n || content.charAt(i) != '"') {
                continue;
            }
            StringBuilder value = new StringBuilder();
            i = readQuoted(content, i, value);
            while (i < n && content.charAt(i) != ';') {
                i++;
            }
            i++;
            if (key.length() > 0 && value.length() > 0) {
                out.put(key.toString(), value.toString());
            }
        }
        return new Parsed(fallbackId, "", "strings", out);
    }

    /** 从 start 处的引号开始读一个字符串，返回结束引号之后的位置 */
    private static int readQuoted(String s, int start, StringBuilder out) {
        int i = start + 1;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char e = s.charAt(i + 1);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'u' -> {
                        if (i + 5 < n) {
                            try {
                                out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                                i += 6;
                                continue;
                            } catch (NumberFormatException ex) {
                                out.append(e);
                            }
                        } else {
                            out.append(e);
                        }
                    }
                    default -> out.append(e);
                }
                i += 2;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            out.append(c);
            i++;
        }
        return i;
    }

    // ---------------- CSV / TSV ----------------

    /**
     * 源文列名。分成两档：明确指源文的（source / 原文）优先于泛指的
     * （key / id / text）——三列表头 `id,source,target` 里 id 在前，
     * 按第一档匹配才能选中 source 列。
     */
    private static final Pattern SOURCE_HEADER_STRONG = Pattern.compile(
            "(?i)source|原文|源文|msgid|original");
    private static final Pattern SOURCE_HEADER_WEAK = Pattern.compile(
            "(?i)key|id|text|名称|键");

    /** 表头里源文列的下标；-1 表示没找到 */
    private static int findSourceColumn(List<String> header) {
        for (int i = 0; i < header.size(); i++) {
            if (SOURCE_HEADER_STRONG.matcher(header.get(i).strip()).matches()) {
                return i;
            }
        }
        for (int i = 0; i < header.size(); i++) {
            if (SOURCE_HEADER_WEAK.matcher(header.get(i).strip()).matches()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 两列：键,译文。三列（key,source,target 或 source,target,comment）时按表头找源文列。
     *
     * 翻译平台（Weblate / Crowdin / Lokalise）导出的 CSV 表头不统一，
     * 这里只看列名里有没有 source / 原文 / msgid 这类词。
     */
    static Parsed parseCsv(String content, String format, String fallbackId) {
        char delim = "tsv".equals(format) ? '\t' : detectDelimiter(content);
        List<List<String>> rows = readCsv(content, delim);
        Map<String, String> out = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            return new Parsed(fallbackId, "", format, out);
        }
        int keyCol = 0;
        int valCol = 1;
        int start = 0;
        List<String> header = rows.get(0);
        int sourceCol = header.size() >= 2 ? findSourceColumn(header) : -1;
        if (sourceCol >= 0) {
            start = 1;
            keyCol = sourceCol;
            // 译文列取「源文列的下一个」；源文在末列时退回第一列
            valCol = sourceCol + 1 < header.size() ? sourceCol + 1 : 0;
        }
        for (int r = start; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.size() <= Math.max(keyCol, valCol)) {
                continue;
            }
            String key = row.get(keyCol);
            String value = row.get(valCol);
            if (!key.isBlank() && !value.isBlank()) {
                out.put(key, value);
            }
        }
        return new Parsed(fallbackId, "", format, out);
    }

    private static char detectDelimiter(String content) {
        int lineEnd = content.indexOf('\n');
        String first = lineEnd < 0 ? content : content.substring(0, lineEnd);
        int commas = 0;
        int tabs = 0;
        int semis = 0;
        for (int i = 0; i < first.length(); i++) {
            char c = first.charAt(i);
            if (c == ',') {
                commas++;
            } else if (c == '\t') {
                tabs++;
            } else if (c == ';') {
                semis++;
            }
        }
        if (tabs > commas && tabs >= semis) {
            return '\t';
        }
        return semis > commas ? ';' : ',';
    }

    /** RFC 4180 风格：双引号包裹，内部 {@code ""} 表示一个引号，允许字段内换行 */
    private static List<List<String>> readCsv(String content, char delim) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        int n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && content.charAt(i + 1) == '"') {
                        cell.append('"');
                        i += 2;
                        continue;
                    }
                    quoted = false;
                    i++;
                    continue;
                }
                cell.append(c);
                i++;
                continue;
            }
            if (c == '"' && cell.length() == 0) {
                quoted = true;
                i++;
                continue;
            }
            if (c == delim) {
                row.add(cell.toString());
                cell.setLength(0);
                i++;
                continue;
            }
            if (c == '\n' || c == '\r') {
                row.add(cell.toString());
                cell.setLength(0);
                if (row.size() > 1 || !row.get(0).isBlank()) {
                    rows.add(row);
                }
                row = new ArrayList<>();
                if (c == '\r' && i + 1 < n && content.charAt(i + 1) == '\n') {
                    i++;
                }
                i++;
                continue;
            }
            cell.append(c);
            i++;
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    // ---------------- JSON（扁平 + i18next 嵌套） ----------------

    /**
     * JSON 两种形态：
     * <ul>
     *   <li>本项目格式 {@code {"id","name","messages":{...}}}，键是中文原文</li>
     *   <li>i18next / vue-i18n 的嵌套对象，如 {@code {"nav":{"library":"..."}}}，
     *       展开成 {@code nav.library} 形式的符号键</li>
     * </ul>
     */
    static Parsed parseJson(String content, String fallbackId) {
        JsonNode root = readJson(content);
        String id = root.path("id").asText("").trim();
        String name = root.path("name").asText("").trim();
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode messages = root.path("messages");
        if (messages.isObject()) {
            flatten(messages, "", out);
        } else {
            // i18next 常把内容包在 "translation" 下
            JsonNode translation = root.path("translation");
            flatten(translation.isObject() ? translation : root, "", out);
        }
        return new Parsed(id.isEmpty() ? fallbackId : id, name, "json", out);
    }

    private static void flatten(JsonNode node, String prefix, Map<String, String> out) {
        node.fields().forEachRemaining(e -> {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            JsonNode value = e.getValue();
            if (value.isObject()) {
                flatten(value, key, out);
            } else if (value.isTextual()) {
                out.put(key, value.asText());
            }
        });
    }

    // ---------------- 归一：符号键 → 中文原文 ----------------

    /**
     * 把解析结果映射到项目的源文键集合。
     *
     * <p>映射规则，按顺序：
     * <ol>
     *   <li>键本身就在源文集合里 → 直接用（源文即键的格式走这条）</li>
     *   <li>keyMap 里有这个键，且映射到的源文存在 → 用映射结果</li>
     *   <li>其余算未匹配，计入 skipped</li>
     * </ol>
     *
     * @param entries   解析出的原始条目
     * @param keyMap    符号键 → 中文原文；可为空
     * @param sourceKeys 项目已知的源文键
     */
    static Resolved resolve(Map<String, String> entries, Map<String, String> keyMap,
                            java.util.Set<String> sourceKeys) {
        Map<String, String> messages = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            if (sourceKeys.contains(key)) {
                messages.put(key, value);
                continue;
            }
            String mapped = keyMap == null ? null : keyMap.get(key);
            if (mapped != null && sourceKeys.contains(mapped)) {
                messages.put(mapped, value);
                continue;
            }
            if (skipped.size() < 20) {
                skipped.add(key);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String key : sourceKeys) {
            if (!messages.containsKey(key) && missing.size() < 20) {
                missing.add(key);
            }
        }
        return new Resolved(messages, skipped, missing);
    }

    record Resolved(Map<String, String> messages, List<String> skippedSample,
                    List<String> missingSample) {
    }

    // ---------------- 占位符一致性 ----------------

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    /** 返回占位符编号与源文不一致的键（最多 20 个） */
    static List<String> placeholderMismatches(Map<String, String> messages) {
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, String> e : messages.entrySet()) {
            if (!samePlaceholders(e.getKey(), e.getValue()) && bad.size() < 20) {
                bad.add(e.getKey());
            }
        }
        return bad;
    }

    private static boolean samePlaceholders(String source, String target) {
        java.util.Set<String> a = new java.util.TreeSet<>();
        java.util.Set<String> b = new java.util.TreeSet<>();
        Matcher ma = PLACEHOLDER.matcher(source);
        while (ma.find()) {
            a.add(ma.group(1));
        }
        Matcher mb = PLACEHOLDER.matcher(target);
        while (mb.find()) {
            b.add(mb.group(1));
        }
        return a.equals(b);
    }

    // ---------------- 公共工具 ----------------

    private static JsonNode readJson(String content) {
        try {
            return MAPPER.readTree(content);
        } catch (IOException e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage());
        }
    }

    /**
     * 解析 XML，禁用 DTD 与外部实体。
     *
     * 语言包来自外部，允许 DOCTYPE 就等于允许读本机文件（XXE）；
     * 这里一律拒绝带 DOCTYPE 的文档。
     */
    private static Document parseXml(String content) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder builder = f.newDocumentBuilder();
            // 屏蔽解析错误输出到 stderr
            builder.setErrorHandler(null);
            return builder.parse(new InputSource(new ByteArrayInputStream(
                    content.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) {
            throw new IllegalArgumentException("XML 解析失败: " + e.getMessage());
        }
    }

    /** 取元素文本的便捷方法，供测试与调试用 */
    static String text(Node node) {
        return node == null ? "" : node.getTextContent();
    }
}
