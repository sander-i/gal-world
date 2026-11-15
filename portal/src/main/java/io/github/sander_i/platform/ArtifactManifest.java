/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.platform;

import java.util.List;

/**
 * {@code manifest.json} 的结构。Portal 只信这里的 {@code sha256}，
 * {@code upstream.url} 仅用于镜像回退与仓库重建。
 */
public record ArtifactManifest(int schema, String generatedAt, List<Entry> artifacts) {

    public record Entry(
            String id,
            String platform,
            String version,
            String file,      // 仓库内相对路径，如 windows/sunshine/2026.../xxx.msi
            String sha256,
            long size,
            Upstream upstream,
            String note) {

        public record Upstream(String url, String license) {
        }
    }

    public Entry find(String id) {
        return artifacts.stream()
                .filter(a -> a.id().equals(id))
                .findFirst()
                .orElse(null);
    }
}
