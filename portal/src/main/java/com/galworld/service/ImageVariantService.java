/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** 派生显示图：按显示尺寸缩放 + 渐进式 JPEG 编码，原图保持不动。 */
@Service
public class ImageVariantService {

    private static final Logger log = LoggerFactory.getLogger(ImageVariantService.class);
    /** 超过则不解码，避免一次 64 MB / 超大像素图打爆堆 */
    private static final long MAX_PIXELS = 40_000_000L;
    /** 高清档：源宽超过此值才压缩到该宽度 */
    private static final int HD_WIDTH = 1024;
    /** 缩略档：只在源宽超过高清档时生成，供列表卡片使用 */
    private static final int THUMB_WIDTH = 640;
    private static final float JPEG_QUALITY = 0.82f;

    /**
     * 派生图目标宽度。
     *
     * 源宽 > 1024：生成 640 缩略档 + 1024 高清档；
     * 源宽 ≤ 1024：只生成一档，宽度等于原图 —— 不再降分辨率。
     */
    static java.util.List<Integer> targetWidths(int srcW) {
        if (srcW > HD_WIDTH) {
            return java.util.List.of(THUMB_WIDTH, HD_WIDTH);
        }
        return java.util.List.of(srcW);
    }

    /** cover-1.jpg -> cover-1.jpg.s640 / cover-1.jpg.s1280（同目录，复用现有删除/重排逻辑） */
    public Path variantPath(Path original, int width) {
        return original.resolveSibling(original.getFileName() + ".s" + width);
    }

    /** 派生图文件后缀：cover-1.jpg.s640 */
    private static final java.util.regex.Pattern VARIANT_SUFFIX =
            java.util.regex.Pattern.compile("\\.s\\d+$");

    /** 一个已生成的派生图文件：绝对路径 + 字节数 */
    public record VariantFile(String path, long bytes) {
    }

    /** 派生图扫描/清理结果 */
    public record VariantScan(int count, long bytes, List<VariantFile> files) {
    }

    /** 列出所有已生成的派生图（只读，不修改任何文件） */
    public VariantScan scan(Path gamesDir) {
        List<VariantFile> files = new ArrayList<>();
        long total = 0;
        for (Path p : collectVariants(gamesDir)) {
            try {
                long size = Files.size(p);
                files.add(new VariantFile(p.toAbsolutePath().toString(), size));
                total += size;
            } catch (IOException e) {
                log.debug("跳过无法读取的派生图 {}: {}", p, e.toString());
            }
        }
        files.sort(java.util.Comparator.comparing(VariantFile::path));
        return new VariantScan(files.size(), total, files);
    }

    /**
     * 删除所有派生图，原图一律不动。
     * 只删文件名匹配 {@code \.s\d+$} 的文件，且必须位于 {@code games/<id>.meta/media/} 下。
     */
    public VariantScan clear(Path gamesDir) {
        List<VariantFile> deleted = new ArrayList<>();
        long total = 0;
        for (Path p : collectVariants(gamesDir)) {
            try {
                long size = Files.size(p);
                if (Files.deleteIfExists(p)) {
                    deleted.add(new VariantFile(p.toAbsolutePath().toString(), size));
                    total += size;
                }
            } catch (IOException e) {
                log.warn("删除派生图失败 {}: {}", p, e.toString());
            }
        }
        log.info("派生图清理完成，删除 {} 个，释放 {} 字节", deleted.size(), total);
        return new VariantScan(deleted.size(), total, deleted);
    }

    /** 遍历 games/<id>.meta/media/ 下所有派生图文件 */
    private List<Path> collectVariants(Path gamesDir) {
        List<Path> out = new ArrayList<>();
        if (gamesDir == null || !Files.isDirectory(gamesDir)) {
            return out;
        }
        try (var metas = Files.list(gamesDir)) {
            for (Path meta : metas.filter(p -> Files.isDirectory(p)
                    && p.getFileName().toString().endsWith(".meta")).toList()) {
                Path mediaDir = meta.resolve("media");
                if (!Files.isDirectory(mediaDir)) {
                    continue;
                }
                try (var files = Files.list(mediaDir)) {
                    out.addAll(files.filter(p -> Files.isRegularFile(p)
                                    && VARIANT_SUFFIX.matcher(p.getFileName().toString()).find())
                            .toList());
                }
            }
        } catch (IOException e) {
            log.warn("扫描派生图失败: {}", e.toString());
        }
        return out;
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
                if (iis == null) return false;
                Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
                if (!it.hasNext()) return false;    // 无 reader（webp）
                ImageReader r = it.next();
                r.setInput(iis);
                srcW = r.getWidth(0);
                srcH = r.getHeight(0);
                r.dispose();
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
            for (int target : targetWidths(srcW)) {
                BufferedImage outImage = target < srcW
                        ? Thumbnails.of(src).scale((double) target / srcW).asBufferedImage()
                        : src;                     // 同尺寸档不缩放，只重新编码
                Path out = variantPath(original, target);
                if (alpha) {
                    writePng(outImage, out);
                } else {
                    writeProgressiveJpeg(outImage, out, JPEG_QUALITY);
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
        Files.createDirectories(out.getParent());
        Path tmp = tempFor(out);
        boolean done = false;
        ImageWriter w = null;
        try {
            w = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);   // ← 渐进式
            try (ImageOutputStream os = new FileImageOutputStream(tmp.toFile())) {
                w.setOutput(os);
                w.write(null, new IIOImage(rgb, null, null), p);
            }
            commit(tmp, out);
            done = true;
        } finally {
            if (w != null) {
                w.dispose();
            }
            if (!done) {
                deleteQuietly(tmp);
            }
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
        Path tmp = tempFor(out);
        boolean done = false;
        try {
            if (!ImageIO.write(img, "png", tmp.toFile())) {
                throw new IOException("没有可用的 PNG 编码器");
            }
            commit(tmp, out);
            done = true;
        } finally {
            if (!done) {
                deleteQuietly(tmp);
            }
        }
    }

    // ---------------- 原子写 ----------------

    /**
     * 同目录临时文件名。回填在服务已接客后运行，直接写目标路径会让客户端
     * 看到截断的 JPEG（曾抓到 20 字节的 .s1280），必须先写临时文件再原子改名。
     */
    private Path tempFor(Path out) {
        return out.resolveSibling(out.getFileName() + ".tmp-" + System.nanoTime());
    }

    /** 临时文件 → 目标文件；不支持原子改名（跨卷等）时退化为普通覆盖 */
    private void commit(Path tmp, Path out) throws IOException {
        try {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignore) {
            // 残留临时文件不影响正确性，下次回填会重新生成
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
