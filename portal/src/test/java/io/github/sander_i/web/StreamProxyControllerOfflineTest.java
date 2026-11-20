/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.domain.Game;
import io.github.sander_i.repo.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 串流代理在会话停止时的行为。
 *
 * <p>背景：游玩页 iframe 在会话停止后仍会每 2 秒重连一次。此前上游端口没监听时
 * 抛出的 {@code ConnectException} 被当成普通 IO 错误记 WARN 并返回 502，
 * 门户日志一天刷 156 条（2026-11-20 实测）。语义上「端口没监听」就是「会话没在跑」，
 * 应与 {@code resolvePort} 返回 -1 时一致：404 + 只留 debug。
 */
class StreamProxyControllerOfflineTest {

    @TempDir Path root;
    GameRepository games;
    StreamProxyController controller;
    int deadPort;

    @BeforeEach
    void setUp() throws Exception {
        games = mock(GameRepository.class);
        controller = new StreamProxyController(games, new PathConfig(root.toString()));
        // 先占用再释放，拿到一个「确定没人在听」的端口号，避免撞上真在跑的服务
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
    }

    private void gameOnPort(int port) {
        Game g = new Game();
        g.setId("offline-game");
        g.setPort(port);
        when(games.findById("offline-game")).thenReturn(Optional.of(g));
    }

    @Test
    void 端口未监听时返回404而不是502() throws Exception {
        gameOnPort(deadPort);
        var req = new MockHttpServletRequest("GET", "/stream/offline-game/");
        ResponseEntity<byte[]> res = controller.proxy("offline-game", req);
        assertEquals(404, res.getStatusCode().value(),
                "端口没监听 = 会话没在跑，应与 resolvePort 的「没启动」同语义返回 404");
    }

    @Test
    void 端口号超出区间时返回404() throws Exception {
        gameOnPort(1234);
        var req = new MockHttpServletRequest("GET", "/stream/offline-game/");
        ResponseEntity<byte[]> res = controller.proxy("offline-game", req);
        assertEquals(404, res.getStatusCode().value());
    }

    @Test
    void 未知游戏返回404() throws Exception {
        when(games.findById("nope")).thenReturn(Optional.empty());
        var req = new MockHttpServletRequest("GET", "/stream/nope/");
        ResponseEntity<byte[]> res = controller.proxy("nope", req);
        assertEquals(404, res.getStatusCode().value());
    }
}
