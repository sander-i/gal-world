# Gal World 图片加载优化方案（实施交接版）

> 状态：**已定稿，可直接实施**
> 编写日期：2026-11-03
> 技术选型：**`net.coobird:thumbnailator:0.4.8`**（缩放）+ **JDK `ImageIO`**（渐进式编码 / PNG）
> 范围：本次实施包含渐进式 JPEG 与 EXIF 方向矫正（原「可选增强」已并入主流程）

---

## 0. 设计决策一览

| 决策 | 内容 |
|---|---|
| 不引入 `bedrock` / `fortify` | 只为图片压缩，两者分别缺编码器 / 依赖过重；`thumbnailator` 零传递依赖、Maven Central 可直取 |
| 派生图而非替换原图 | 上传时生成 `s640` / `s1280` 两档，原图保留不动 |
| 相对原方案的关键修正 | 「小图先显示、原图后替换」改为前端 `srcset` 单请求选档（避免双倍流量与替换闪烁） |
| 编码 | JPEG 一律 **progressive（渐进式）**，q0.82；alpha 源出 PNG |
| 开关 | 设置项 `imageVariantEnabled`，默认 `true`；关闭不删已生成文件 |

---

## 1. 现状核对（代码事实）

### 1.1 上传与存储

- `MediaService.save()`（`portal/src/main/java/com/galworld/service/MediaService.java:76`）：嗅探文件头 → **流式落盘**，不做解码/转码。
- 落盘：`games/<id>.meta/media/<kind>-<seq>.<ext>`（`PathConfig.gameMediaDir`）。
- URL：`/media/<id>/<kind>-<seq>.<ext>?v=<sha256 前 8 位>`（`MediaService.java:145`）。
- 单图上限：`imageMaxMb`，默认 8 MB，上限 64 MB；multipart 512 MB。
- `deleteFile`、`renumberBatch`（`:163`、`:189`）按 `文件名.startsWith(base + ".")` 匹配 → **同目录同前缀的派生图会被自动连带改名/删除**（对本方案有利）。

### 1.2 托管与缓存

`MediaController.get()`（`portal/src/main/java/com/galworld/web/MediaController.java:31`）：

- 带 `?v=` → `public, max-age=31536000, immutable`
- 不带 `?v=` → `no-cache`
- Content-Type 来自 `Files.probeContentType(file)`

→ 派生图必须复用原图 `?v=`；且 `probeContentType` 对 `.s640` 后缀返回 `null`，需改为文件头嗅探。

### 1.3 前端当前行为

- 库页卡片：`.grid { grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)) }`，封面 `aspect-ratio: 16/9`（`styles.css:112`、`:135`）→ 实际显示 250~420 CSS px，DPR2 最多 ~840 px。
- 详情页 hero：半栏 700~900 CSS px；库页 hero 全宽。
- `MediaRotator.vue:86`：首屏只渲染第 0 项，其余项空闲时用 `new Image()` **按原始尺寸全量预加载**。
- `<img>` 无 `srcset` / `sizes` / `fetchpriority`。

### 1.4 依赖事实（已实测）

`net.coobird:thumbnailator:0.4.8`：

- 本机 `~/.m2` 已存在；`mvn -o dependency:get -Dartifact=net.coobird:thumbnailator:0.4.8` → **BUILD SUCCESS**
- Maven Central 公共坐标，**无需**额外 `<repositories>`
- 零传递依赖，约 100 KB，Java 6+ 字节码，JDK 17 可运行

0.4.8 API 限制（写代码前必读）：

| 事项 | 事实 |
|---|---|
| `toByteArray()` | **0.4.8 没有**（新版才有）。用 `toFile` / `toOutputStream` / `asBufferedImage` |
| `toFile()` | 默认拒绝覆盖，需 `.allowOverwrite(true)`；源与目标不能同一文件 |
| `size(w,h)` | 等比装入边界框、不裁切；**但 0.4.8 会放大小图**（实测 400×300 + `size(1920,1920)` → 1920×1440） |
| `scale(s)` | 按比例缩放，不会自动保护小图 → 调用前自行判断源宽 |
| `outputQuality` | 仅 JPEG 生效；PNG 走无损，参数被忽略 |
| alpha | `asBufferedImage()` 保留 ARGB（实测 `hasAlpha=true`） |
| 不支持格式 | WebP 抛 `net.coobird.thumbnailator.tasks.UnsupportedFormatException: No suitable ImageReader found for source data.` |

体积实测（1920×1080 源，JPEG q0.9 / 794 KB；PNG 3.0 MB）：

| 操作 | 输出 | 尺寸 |
|---|---|---|
| `.scale(1.0).outputQuality(0.85)` JPEG | 688 KB（86%） | 1920×1080 |
| `.scale(1.0).outputQuality(0.80)` JPEG | 549 KB（69%） | 1920×1080 |
| PNG `.outputQuality(0.85)`（无损，参数无效） | 2.55 MB（82%） | 1920×1080 |
| PNG → JPEG q0.85 | 590 KB（19%） | 等比 |
| **缩到 1280w + 渐进式 q0.82** | **134 KB** | 1280×720 |
| 缩到 1280w + 基线式 q0.82 | 156 KB | 1280×720 |

结论：按显示尺寸缩放的收益远大于同尺寸重编码；**渐进式编码同时更小（-13.7%）且先出模糊全图**。

---

## 2. 派生图规格

| 档位 | 目标宽度 | 用途 |
|---|---|---|
| `s640` | 640 | 库页卡片（覆盖 DPR2） |
| `s1280` | 1280 | 详情页 hero、库页 hero、编辑页预览 |

规则：

- 只缩不放：源宽 ≤ 目标宽 → 不生成该档。
- 等比缩放，不裁切。
- 源带 alpha（`ImageIO.read` 后 `getColorModel().hasAlpha()`）→ 输出 PNG。
- 源为 `webp` / `gif` / 视频 → 跳过（`gif` 出首帧会破坏动画；`webp` JDK 无解码器）。
- JPEG 输出一律 **progressive**，质量 0.82。
- 失败只记日志，**上传照常成功**，该图回退原图。

存储增量：约 +8~12%。

---

## 3. 生成实现

### 3.1 新增 `ImageVariantService`

`portal/src/main/java/com/galworld/service/ImageVariantService.java`：

```java
package com.galworld.service;

import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.*;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;

/** 派生显示图：按显示尺寸缩放 + 渐进式 JPEG 编码，原图保持不动。 */
@Service
public class ImageVariantService {

    private static final Logger log = LoggerFactory.getLogger(ImageVariantService.class);
    /** 超过则不解码，避免一次 64 MB / 超大像素图打爆堆 */
    private static final long MAX_PIXELS = 40_000_000L;
    private static final int[] TARGET_WIDTHS = {640, 1280};
    private static final float JPEG_QUALITY = 0.82f;

    /** cover-1.jpg -> cover-1.jpg.s640 / cover-1.jpg.s1280（同目录，复用现有删除/重排逻辑） */
    public Path variantPath(Path original, int width) {
        return original.resolveSibling(original.getFileName() + ".s" + width);
    }

    /** 上传后同步调用；任何异常都吞掉，返回是否至少生成了一档 */
    public boolean generate(Path original) {
        String name = original.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg"))) {
            return false;                          // gif / webp / 视频一律跳过
        }
        try {
            int srcW, srcH;
            try (ImageInputStream iis = ImageIO.createImageInputStream(original.toFile())) {
                Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
                if (!it.hasNext()) return false;    // 无 reader（webp）
                ImageReader r = it.next();
                r.setInput(iis);
                srcW = r.getWidth(0);
                srcH = r.getHeight(0);
            }
            if ((long) srcW * srcH > MAX_PIXELS) {
                log.warn("图片像素超限，跳过派生图: {} ({}x{})", original, srcW, srcH);
                return false;
            }

            BufferedImage src = ImageIO.read(original.toFile());
            if (src == null) return false;
            // EXIF 方向必须在缩放前应用，否则派生图与浏览器显示方向不一致
            src = applyExifOrientation(src, exifOrientation(original));
            boolean alpha = src.getColorModel().hasAlpha();
            srcW = src.getWidth();

            boolean any = false;
            for (int target : TARGET_WIDTHS) {
                if (srcW <= target) continue;       // 不放大小图
                BufferedImage scaled = Thumbnails.of(src)
                        .scale((double) target / srcW)
                        .asBufferedImage();
                Path out = variantPath(original, target);
                if (alpha) {
                    writePng(scaled, out);
                } else {
                    writeProgressiveJpeg(scaled, out, JPEG_QUALITY);
                }
                any = true;
                log.info("派生图已生成 {} ({} bytes)", out, Files.size(out));
            }
            return any;
        } catch (Exception e) {
            log.warn("派生图生成失败，回退原图: {}", original, e);
            return false;
        }
    }

    // ---------------- 编码 ----------------

    private void writeProgressiveJpeg(BufferedImage img, Path out, float quality) throws IOException {
        // JPEG 不支持 alpha，调用方已保证只有非 alpha 源走这里
        BufferedImage rgb = img.getType() == BufferedImage.TYPE_INT_RGB
                ? img
                : toRgb(img);
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality);
        p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);   // ← 渐进式
        Files.createDirectories(out.getParent());
        try (ImageOutputStream os = new FileImageOutputStream(out.toFile())) {
            w.setOutput(os);
            w.write(null, new IIOImage(rgb, null, null), p);
        } finally {
            w.dispose();
        }
    }

    private BufferedImage toRgb(BufferedImage img) {
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.drawImage(img, 0, 0, java.awt.Color.BLACK, null);
        g.dispose();
        return out;
    }

    private void writePng(BufferedImage img, Path out) throws IOException {
        Files.createDirectories(out.getParent());
        if (!ImageIO.write(img, "png", out.toFile())) {
            throw new IOException("没有可用的 PNG 编码器");
        }
    }

    // ---------------- EXIF 方向 ----------------

    /** 读 JPEG APP1 里的 EXIF Orientation；非 JPEG / 解析失败返回 1 */
    int exifOrientation(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] b = in.readNBytes(64 * 1024);     // EXIF 通常在文件头 64 KB 内
            if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return 1;
            int i = 2;
            while (i + 4 <= b.length) {
                if ((b[i] & 0xFF) != 0xFF) { i++; continue; }
                int marker = b[i + 1] & 0xFF;
                if (marker == 0xDA || marker == 0xD9) return 1;   // 进入扫描数据
                int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
                if (len < 2) return 1;
                if (marker == 0xE1 && len > 8
                        && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i' && b[i + 7] == 'f') {
                    return parseTiffOrientation(b, i + 10, i + 2 + len);
                }
                i += 2 + len;
            }
        } catch (IOException ignore) {
            // 读失败按无方向处理
        }
        return 1;
    }

    private int parseTiffOrientation(byte[] b, int start, int end) {
        if (start + 8 > end) return 1;
        boolean little = b[start] == 'I' && b[start + 1] == 'I';
        boolean big = b[start] == 'M' && b[start + 1] == 'M';
        if (!little && !big) return 1;
        int ifd = start + readInt(b, start + 4, little);
        if (ifd + 2 > end) return 1;
        int count = readShort(b, ifd, little);
        for (int k = 0; k < count; k++) {
            int entry = ifd + 2 + k * 12;
            if (entry + 12 > end) return 1;
            int tag = readShort(b, entry, little);
            if (tag == 0x0112) {
                int v = readShort(b, entry + 8, little);
                return (v >= 1 && v <= 8) ? v : 1;
            }
        }
        return 1;
    }

    private int readShort(byte[] b, int pos, boolean little) {
        int a = b[pos] & 0xFF, c = b[pos + 1] & 0xFF;
        return little ? (c << 8) | a : (a << 8) | c;
    }

    private int readInt(byte[] b, int pos, boolean little) {
        int a = b[pos] & 0xFF, c = b[pos + 1] & 0xFF, d = b[pos + 2] & 0xFF, e = b[pos + 3] & 0xFF;
        return little ? (e << 24) | (d << 16) | (c << 8) | a : (a << 24) | (c << 16) | (d << 8) | e;
    }

    /** 按 EXIF Orientation(1~8) 把像素摆正；5~8 会交换宽高 */
    BufferedImage applyExifOrientation(BufferedImage img, int o) {
        if (o <= 1 || o > 8) return img;
        int w = img.getWidth(), h = img.getHeight();
        boolean swap = o >= 5;
        AffineTransform t = new AffineTransform();
        switch (o) {
            case 2 -> { t.scale(-1, 1); t.translate(-w, 0); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.scale(1, -1); t.translate(0, -h); }
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.translate(h, w); t.rotate(Math.PI / 2); t.scale(-1, 1); }
            case 8 -> { t.translate(0, w); t.rotate(-Math.PI / 2); }
            default -> { return img; }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h,
                img.getType() == 0 ? BufferedImage.TYPE_INT_ARGB : img.getType());
        var g = out.createGraphics();
        g.drawImage(img, t, null);
        g.dispose();
        return out;
    }
}
```

要点：

- 一次 `ImageIO.read` 拿到像素，Thumbnailator 用 `of(BufferedImage)` 复用，避免重复解码。
- 不用 Thumbnailator 的 `outputQuality` / `outputFormat`：编码统一走 JDK `ImageWriter`，才能开 progressive。
- `toRgb` 只在 alpha 源误走 JPEG 时兜底（正常不会发生）。
- `exifOrientation` 只读 JPEG APP1；无效/非 JPEG 返回 1，零副作用。

**本段代码已实测**（JDK 17 + thumbnailator 0.4.8，复制出类后编译并运行）：

- 1920×1080 → `s640` = 640×360 / 13.5 KB，`s1280` = 1280×720 / 87 KB
- 两份输出均含 `SOF2` 标记（progressive 生效）
- 注入 EXIF Orientation=6 后 → `s640` = **640×1138**（宽高交换，方向正确）
- `.webp` 输入 → `generate()` 返回 `false`，无派生图

### 3.2 挂钩到 `MediaService.save()`

在**旧文件清理之后、返回 URL 之前**：

```java
log.info("媒体已保存 {} {}/{} ({} bytes)", gameId, kind, filename, total);
if (!video && settings.imageVariantEnabled()) {
    variants.generate(target);      // target = 刚写入的原图 Path
}
return new Saved("/media/" + gameId + "/" + filename + "?v=" + versionOf(md), kind, total);
```

顺序原因：`save()` 会删除同 `base` 前缀的旧文件（含旧派生图），提前生成会被删掉。

### 3.3 生命周期一致

| 操作 | 现有逻辑 | 派生图 |
|---|---|---|
| 覆盖上传同序号 | 删 `base + "."` 前缀旧文件 | ✅ 派生图被删 → 必须在其后生成 |
| `deleteFile` | 删 `base + "."` 前缀 | ✅ 连带删除 |
| `renumberBatch` | 按 `base` 前缀链式改名 | ✅ 连带改名 |
| `GameMediaService.reconcile` | 未知文件建新媒体记录（`GameMediaService.java:222`） | ❌ **必须过滤 `.s640/.s1280`** |
| `GameMetaController.fileList` | 列目录做「服务器路径」展示（`GameMetaController.java:274`） | ❌ 过滤 |
| `MediaService.listFiles` | 上面两处都调它 | ✅ 在此统一过滤最省事 |
| 删游戏 | 整目录删除 | ✅ 连带删除 |

过滤实现（`MediaService.listFiles`）：

```java
private static final java.util.regex.Pattern VARIANT =
        java.util.regex.Pattern.compile("\\.s\\d+$");

// in listFiles(new DirectoryStream.Filter / predicate):
if (VARIANT.matcher(n).find()) return false;
```

### 3.4 Content-Type

`MediaController` 对 `cover-1.jpg.s640` 的 `Files.probeContentType` 返回 `null` → 浏览器收到 `application/octet-stream`。改为读文件头嗅探（原图与派生图共用）：

```java
private MediaType mediaTypeOf(Path file) throws IOException {
    byte[] head = new byte[12];
    try (var in = Files.newInputStream(file)) {
        int n = in.readNBytes(head, 0, head.length);
        if (n >= 4 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) {
            return MediaType.IMAGE_JPEG;
        }
        if (n >= 4 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return MediaType.IMAGE_PNG;
        }
        if (n >= 4 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F') {
            return MediaType.IMAGE_GIF;
        }
        if (n >= 12 && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return MediaType.parseMediaType("image/webp");
        }
        if (n >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            return MediaType.parseMediaType("video/mp4");
        }
    }
    return MediaType.APPLICATION_OCTET_STREAM;
}
```

缓存头、路径穿越校验、ETag 不动。

### 3.5 服务端下发

`GameController.java:173`：`covers` 从 `List<String>` 改为对象数组：

```json
"covers": [
  {
    "original": "/media/sample-game/cover-1.jpg?v=1a2b3c4d",
    "variants": [
      {"w": 640,  "url": "/media/sample-game/cover-1.jpg.s640?v=1a2b3c4d"},
      {"w": 1280, "url": "/media/sample-game/cover-1.jpg.s1280?v=1a2b3c4d"}
    ]
  }
]
```

- `variants` 用 `Files.exists(mediaDir.resolve(name + ".s" + w))` 现算。
- 开关关闭 → `variants` 恒 `[]`。
- `?v=` 复用原图版本号：原图变 → 派生图重生 → 缓存同步失效。
- `cgs` 同样处理；视频项 `variants` 恒空。
- ⚠️ 破坏性 API 变更，前后端必须同版本发布。

### 3.6 前端

`MediaRotator.vue`：

```vue
<img
  v-else
  :key="active.original"
  :src="active.original"
  :srcset="(active.variants || []).map(v => `${v.url} ${v.w}w`).join(', ')"
  sizes="(max-width: 700px) 100vw, (max-width: 1200px) 45vw, 420px"
  :alt="name"
  decoding="async"
  :fetchpriority="current === 0 ? 'high' : 'low'"
  class="rotator-media"
  @error="markFailed(current)"
/>
```

- `items` 从 `Array<string>` 改为对象数组；`isVideo()` 改读 `item.original`。
- `preloadOne()` 改为预加载 `s640`（存在时），否则原图；不再整份预加载原图。
- 详情页 hero 的 `sizes` 传 `(max-width: 900px) 100vw, 45vw`。
- 渐进式 JPEG 让首帧即出模糊全图，无需额外模糊占位。

### 3.7 全局开关

- 新 key `imageVariantEnabled`，默认 `true`。
- `SettingsService`：`DEFAULTS` 加项、`update()` 校验 `true/false`、加 `imageVariantEnabled()`。
- `SettingsView.vue`：复用现有两按钮 `.seg` 结构（参考 `SettingsView.vue:374`「轮换功能」）。

文案：

> 图片预加载优化：按显示尺寸生成派生图，先出画面再补清晰度。
> 代价：磁盘多占用约 10%；极端情况下首帧清晰度略低于原图。关闭后立即回退原图，已生成的派生图不删除。

### 3.8 存量回填

`ImageVariantBackfill`（`@Order(100)`），幂等可中断：

1. 扫 `games/*.meta/media/{cover,cg}-*`。
2. 跳过已有对应派生图的文件。
3. 调 `ImageVariantService.generate()`。
4. 进度写 `data/portal/variant-backfill.state`，重启续跑。
5. 开关关闭时不启动。

执行方式（已实现）：

- **异步**：启动只投递任务到单线程守护线程 `image-variant-backfill`，不拖慢启动，也不与请求线程争抢。
- **开关联动**：`SettingsService.update()` 在事务 **afterCommit** 后发布 `ImageVariantEnabledEvent(true)`；回填监听后投递任务。
- **关闭即停**：每个文件处理前复查开关，关闭则中止当前批次；**已生成的派生图不删除**。
- **补跑**：运行中再次触发只置 `rerunRequested`，当前批次结束后若开关仍开则自动补跑一次。

估算：单张 1920×1080 约 80~150 ms，500 张 ~1 分钟。

### 3.9 存量 URL 版本号补齐

`MediaPathVersionMigrator`（`@Order(50)`，早于回填）：历史媒体 URL 缺乏 `?v=`，只能命中 `Cache-Control: no-cache`。

- 扫 `game` 的 `cover_path`/`icon_path`/`cg_path`/`cg_high_path`/`cg_low_path` 与 `game_media.path`；
- 已含 `?` 跳过（幂等）；
- 版本号 = 文件内容 SHA-256 前 4 字节 `%08x`，与 `MediaService.versionOf` 逐字节一致；
- 流式读文件计算摘要，不整份进堆；异常只记日志，不阻断启动。

### 3.10 派生图清理（设置页）

关闭「图片预加载优化」后，设置页展示清理区：列出所有已生成派生图的**绝对路径**与体积，并提供一次性清理。

- 接口（`ImageVariantAdminController`）：
  - `GET /api/settings/image-variants` → `{count, bytes, files:[{path, bytes}]}`
  - `DELETE /api/settings/image-variants` → 删除并返回 `{count, bytes, files}`
- 前置条件：开关**必须已关闭**，否则返回 `400 {"message":"请先关闭「图片预加载优化」再清理派生图"}`。
- 删除范围：仅 `games/<id>.meta/media/` 下文件名匹配 `\.s\d+$` 的文件；原图永不删除。
- 前端：`SettingsView` 在开关关闭时自动拉取并渲染列表，清理前走 `confirmAction` 二次确认，完成后刷新列表并提示释放空间。

### 3.11 游戏页展示策略（封面 / CG）

- 右上角切换按钮顺序为「封面 | CG」，文案统一用 **CG**。
- 偏好来源：`Game.mediaMode`（单游戏）> 全局设置 `defaultMediaMode`（`cover` / `cg`，默认 `cg`）。
- 展示规则：
  - 有封面时**先渲染封面**，右上角按钮高亮跟随「实际展示内容」（未就绪时显示选中「封面」）；
  - 偏好为 CG 且该游戏有 CG：CG 层后台挂载并隐藏，**首个 CG 媒体就绪**（图片 `load` / 视频 `canplay`）后淡入接管、封面卸载，按钮同步切到「CG」；
  - CG 只有图片没有视频 → 同样在图片加载完成后展示 CG；
  - 没有封面但有 CG → 直接展示 CG；
  - 偏好 CG 但没上传 CG → 保持封面；
  - 未就绪时用户手动点「CG」：内容区切到 CG 层并显示「CG 正在加载中…」+ 进度条（能算出 `buffered/duration` 时显示百分比，否则显示不确定态），就绪后自动隐藏；
  - CG 全部加载失败：自动回退封面。
- 轮换顺序：`MediaRotator` 在挂载时按 `mode` 选定起点（`random` 随机、`seq` 第一项），因此**刷新即可换一条 CG**；播放结束后仍按 `seq`/`random` 切下一条。

---

## 4. 改动清单

| 文件 | 改动 |
|---|---|
| `portal/pom.xml` | 加 `net.coobird:thumbnailator:0.4.8` |
| `service/ImageVariantService.java`（新） | §3.1 全文 |
| `service/MediaService.java` | `save()` 末尾调用；`listFiles()` 过滤 `.s\d+` |
| `service/GameMediaService.java:222` | `reconcile()` 过滤派生后缀 |
| `web/MediaController.java` | 文件头嗅探 Content-Type |
| `web/GameMetaController.java:274` | `fileList` 过滤（或依赖 `listFiles`） |
| `web/GameController.java:173` | `covers`/`cgs` 改为 `{original, variants}`（破坏性） |
| `service/SettingsService.java` | 新 key + 校验 + accessor |
| `frontend/src/views/SettingsView.vue` | 开关 UI |
| `frontend/src/components/MediaRotator.vue` | `srcset`、`fetchpriority`、结构适配 |
| `frontend/src/views/LibraryView.vue`、`GameView.vue` | 适配新结构、传 `sizes` |
| 回填任务（新） | 存量生成 |

---

## 5. 风险

| 风险 | 应对 |
|---|---|
| 大图解码 OOM | 像素上限 40 MP，超限跳过 |
| 小图被放大 | 先判 `srcW <= target` |
| PNG 透明转 JPEG 变黑 | alpha 源输出 PNG |
| WebP 抛异常 | 扩展名预过滤 + `catch Exception` |
| 派生图被 `reconcile` 当新媒体 | 过滤 `.s\d+` + 测试 |
| 同步生成拖慢上传 | 单张 +80~200 ms，可接受 |
| EXIF 方向映射写错 | 用 §6 第 11 条实测校验 |
| API 结构破坏 | 前后端同版本发布 |

---

## 6. 验收标准

1. 上传 1920×1080 JPEG → 出现 `.s640`、`.s1280`，`.s640` < 原图 15%。
2. `.s1280` 文件头含 `SOF2`（progressive 标记），浏览器 Network 缩略图呈现「先模糊后清晰」。
3. 库页卡片只下载一档派生图，不下载原图。
4. 关闭开关刷新 → 全部回退原图 URL，无 404；重开立即恢复。
5. 删除封面 → 三份文件同时消失，序号重排正确。
6. 编辑页「服务器路径」不含 `.s640/.s1280`。
7. `reconcile` 后 `GameMedia` 条目数不因派生图增加。
8. 上传 webp / gif → 成功、无派生图、页面正常。
9. 上传 64 MB / 8000×6000 图 → 不 OOM，原图可保存。
10. 透明 PNG 封面 → 派生图为 PNG，透明保留。
11. EXIF Orientation=6 的照片 → 派生图方向与浏览器原图显示一致（宽高互换）。

---

## 7. 实施顺序

| 阶段 | 内容 | 预估 |
|---|---|---|
| P1 | pom + `ImageVariantService` + `save()` 接入 | 0.5 天 |
| P2 | 派生后缀过滤 + Content-Type 嗅探 | 0.5 天 |
| P3 | API 结构改造 + 前端 `srcset` | 0.5 天 |
| P4 | 设置开关 + 文案 | 0.5 天 |
| P5 | 存量回填 | 0.5 天 |
| P6 | 回归（上传/删除/重排/轮换/缓存/EXIF） | 0.5 天 |

先做 P1+P3 最薄切片（只生成 `s1280` + 库页 `srcset`），看真实 Network 面板后再补 `s640` 与回填。

---

## 8. 交接硬约束

1. 只用 `net.coobird:thumbnailator:0.4.8` + JDK `ImageIO`。不引 bedrock/fortify。
2. 0.4.8 **没有 `toByteArray()`**，用 `toFile` / `toOutputStream` / `asBufferedImage`。
3. `size()` 会放大小图；本方案统一用 `scale(target / srcW)` 并先判源宽。
4. **JPEG 编码必须走 JDK `ImageWriter` + `setProgressiveMode(MODE_DEFAULT)`**；Thumbnailator 不暴露 progressive。
5. **PNG 的 `outputQuality` 无效**；alpha 源直接写 PNG。
6. **EXIF 必须在缩放前应用**，否则派生图方向错误。
7. **派生图生成必须在 `save()` 旧文件清理之后**执行。
8. **`.s\d+` 过滤是硬性要求**（`listFiles` / `reconcile` / `fileList`）。
9. 前后端同版本发布（`covers` 结构变更）。
10. 构建环境（本机默认 `JAVA_HOME` 是 JDK 8，直接 `mvn` 失败）：

```bash
export JAVA_HOME="/g/Program Files/Java/corretto-17.0.14"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -o -DskipTests compile
```

11. 验收按 §6 逐条实测，不凭代码观感。

---

## 9. 明确不实施

- **WebP / AVIF 输出**：JDK `ImageIO` 无对应编码器，需额外依赖，收益不足以引入。
- **原图重编码为渐进式**：原图按设计保持字节不变（可下载原质量）。
