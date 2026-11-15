/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import com.galworld.config.PathConfig;
import com.galworld.domain.Game;
import com.galworld.repo.GameCompanyRepository;
import com.galworld.repo.GameLocalizationRepository;
import com.galworld.repo.GameMediaRepository;
import com.galworld.repo.GameRepository;
import com.galworld.repo.GameTagRepository;
import com.galworld.repo.PlaySessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 删除一个游戏的注册信息。
 *
 * 删除范围：
 *  - 数据库里的 Game 行、标签/公司关联、媒体记录、游玩历史
 *  - games/&lt;id&gt;.meta（封面、图标、宣传 CG）
 *  - games/&lt;id&gt;/game.env（注册文件）
 *
 * 保留 games/&lt;id&gt;/ 里的游戏本体，之后仍可用 galworld add 重新注册。
 * 删除 game.env 是必须的：扫描器只认 env 文件，留着它下次列表刷新就会把游戏加回来。
 */
@Service
public class GameDeletionService {

    private static final Logger log = LoggerFactory.getLogger(GameDeletionService.class);

    private final GameRepository games;
    private final GameTagRepository tagLinks;
    private final GameCompanyRepository companyLinks;
    private final GameLocalizationRepository localizationLinks;
    private final GameMediaRepository media;
    private final PlaySessionRepository sessions;
    private final SessionService sessionService;
    private final PlaytimeService playtime;
    private final PathConfig paths;

    public GameDeletionService(GameRepository games, GameTagRepository tagLinks,
                               GameCompanyRepository companyLinks,
                               GameLocalizationRepository localizationLinks,
                               GameMediaRepository media, PlaySessionRepository sessions,
                               SessionService sessionService,
                               PlaytimeService playtime, PathConfig paths) {
        this.games = games;
        this.tagLinks = tagLinks;
        this.companyLinks = companyLinks;
        this.localizationLinks = localizationLinks;
        this.media = media;
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.playtime = playtime;
        this.paths = paths;
    }

    @Transactional
    public void delete(String gameId) throws IOException {
        Game game = games.findById(gameId).orElseThrow(
                () -> new IllegalArgumentException("没有这个游戏: " + gameId));

        // 运行中的会话先停掉，否则删除注册信息后还剩一个野进程
        var status = sessionService.status(gameId, game.getPort());
        if (status.state() == SessionService.State.RUNNING) {
            log.info("删除游戏 {} 前先停止会话", gameId);
            sessionService.stop(gameId, game.getPort());
        }
        playtime.closeOpen(gameId);

        // 先删注册文件与媒体：扫描器不会再把游戏加回来
        Files.deleteIfExists(paths.gameEnv(gameId));
        deleteRecursively(paths.gameMetaDir(gameId));
        deleteMarkers(gameId);

        // 再清数据库
        tagLinks.deleteByGameId(gameId);
        companyLinks.deleteByGameId(gameId);
        localizationLinks.deleteByGameId(gameId);
        media.deleteByGameId(gameId);
        sessions.deleteByGameId(gameId);
        games.delete(game);

        log.info("游戏已删除（保留本体目录）: {}", gameId);
    }

    private void deleteMarkers(String gameId) throws IOException {
        Path dir = paths.dataDir().resolve("session-intent");
        Files.deleteIfExists(dir.resolve(gameId + ".started"));
        Files.deleteIfExists(dir.resolve(gameId + ".stopped"));
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
