/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.web;

import com.galworld.domain.Game;
import com.galworld.domain.Tag;
import com.galworld.service.GameMediaService;
import com.galworld.service.SessionService;

import java.util.List;

/** 返回给前端的游戏视图：注册信息 + 会话状态 + 时长 + 标签。 */
public record GameView(
        String id,
        String name,
        String description,
        String coverPath,
        String iconPath,
        String cgPath,
        String cgHighPath,
        String cgLowPath,
        int width,
        int height,
        int port,
        String streamUrl,
        String state,
        boolean crashed,
        long totalSeconds,
        List<String> tags,
        List<CompanyView> companies,
        List<GroupView> localizations,
        List<GameMediaService.MediaItem> covers,
        List<GameMediaService.MediaItem> cgs,
        String rotationMode,
        int rotationInterval,
        String coverRotationMode,
        int coverRotationInterval,
        String cgRotationMode,
        String cgRotationOrder,
        int cgRotationInterval,
        String mediaMode) {

    /** 公司展示项：名称 + 别名 + 网址共同构成公司身份，简介仅展示 */
    public record CompanyView(Long id, String name, String alias, String website, String description) {
    }

    /** 汉化组展示项：与公司同构 */
    public record GroupView(Long id, String name, String alias, String website, String description) {
    }

    public static GameView of(Game g, SessionService.SessionStatus status, long totalSeconds,
                              List<Tag> tags, List<CompanyView> companies,
                              List<GroupView> localizations,
                              List<GameMediaService.MediaItem> covers,
                              List<GameMediaService.MediaItem> cgs,
                              String rotationMode, int rotationInterval,
                              String coverRotationMode, int coverRotationInterval,
                              String cgRotationMode, String cgRotationOrder,
                              int cgRotationInterval) {
        return new GameView(
                g.getId(),
                g.getName(),
                g.getDescription(),
                g.getCoverPath(),
                g.getIconPath(),
                g.getCgPath(),
                g.getCgHighPath(),
                g.getCgLowPath(),
                g.getWidth(),
                g.getHeight(),
                g.getPort(),
                status == null ? null : status.streamUrl(),
                status == null ? "UNKNOWN" : status.state().name(),
                status != null && status.crashed(),
                totalSeconds,
                tags == null ? List.of() : tags.stream().map(Tag::getName).toList(),
                companies == null ? List.of() : companies,
                localizations == null ? List.of() : localizations,
                covers == null ? List.of() : covers,
                cgs == null ? List.of() : cgs,
                rotationMode,
                rotationInterval,
                coverRotationMode,
                coverRotationInterval,
                cgRotationMode,
                cgRotationOrder,
                cgRotationInterval,
                g.getMediaMode());
    }
}
