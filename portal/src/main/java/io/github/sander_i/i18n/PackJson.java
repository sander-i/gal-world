/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 语言包 JSON 的读写。
 *
 * 格式刻意扁平：
 *
 * <pre>
 * {
 *   "id": "ja",
 *   "name": "日本語",
 *   "messages": { "nav.library": "ゲームライブラリ", "error.notFound": "見つかりません: {0}" }
 * }
 * </pre>
 *
 * 不用 properties：语言包里全是非 ASCII，properties 要么转义成 \\uXXXX（没法人工编辑），
 * 要么依赖 UTF-8 读取（JDK 9 之前默认 ISO-8859-1）。JSON 与前端语言包同格式，一份心智模型。
 */
final class PackJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PackJson() {
    }

    /** 读 classpath 下的 i18n/*.json；jar 与开发目录都能取到 */
    static Map<String, Map<String, String>> loadClasspathPacks() {
        Map<String, Map<String, String>> out = new TreeMap<>();
        // 不依赖 ClassLoader 的目录枚举（jar 里拿不到），内置包清单写死在这里，
        // 新增内置语言时同时加一行。新增语言不改代码的目标靠外部目录实现。
        for (String id : new String[]{"en", "zh-CN", "zh-TW", "ja"}) {
            String res = "i18n/" + id + ".json";
            try (InputStream in = PackJson.class.getClassLoader().getResourceAsStream(res)) {
                if (in == null) {
                    continue;
                }
                out.put(id, parseMessages(in.readAllBytes()));
            } catch (IOException ignored) {
                // 内置包读不出来时保持缺失，翻译会退回键名
            }
        }
        return out;
    }

    static Map<String, String> parseMessages(byte[] json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode msgs = root.path("messages");
        if (!msgs.isObject()) {
            throw new IOException("语言包缺少 messages 对象");
        }
        Map<String, String> out = new LinkedHashMap<>();
        msgs.fields().forEachRemaining(e -> {
            if (e.getValue().isTextual()) {
                out.put(e.getKey(), e.getValue().asText());
            }
        });
        return out;
    }

    /** 从 JSON 读完整语言包；id 缺失时用文件名推断 */
    static LocalePack parse(byte[] json, String fallbackId, String source, boolean builtin)
            throws IOException {
        JsonNode root = MAPPER.readTree(json);
        String id = root.path("id").asText("").trim();
        if (id.isEmpty()) {
            id = fallbackId;
        }
        if (!LocalePack.isValidId(id)) {
            throw new IOException("语言包 id 非法: " + id);
        }
        // 名称缺失时用该语言的自称，不用裸 id：.po / CSV 导入与手工放文件
        // 都可能不带 name，按钮上显示「ko」对用户没有意义
        String name = root.path("name").asText("").trim();
        if (name.isEmpty()) {
            name = LocaleNames.of(id);
        }
        String nativeName = root.path("nativeName").asText("").trim();
        if (nativeName.isEmpty()) {
            nativeName = name;
        }
        return new LocalePack(LocalePack.normalize(id), name, nativeName, builtin, source,
                parseMessages(json));
    }

    /** 写回磁盘：字段顺序固定，方便用 git / 文本工具对比 */
    static void write(Path file, String id, String name, String nativeName,
                      Map<String, String> messages) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", id);
        root.put("name", name);
        if (nativeName != null && !nativeName.isBlank()) {
            root.put("nativeName", nativeName);
        }
        ObjectNode msgs = root.putObject("messages");
        new TreeMap<>(messages).forEach(msgs::put);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                + "\n", StandardCharsets.UTF_8);
    }

    /** 枚举目录下的 *.json；目录不存在时返回空 */
    static java.util.List<Path> listFiles(Path dir) {
        java.util.List<Path> out = new java.util.ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            ds.forEach(out::add);
        } catch (IOException ignored) {
            // 读不到就当没有外部包
        }
        out.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    static String uriOf(Path p) {
        return URI.create("file:" + p.toAbsolutePath()).toString();
    }
}
