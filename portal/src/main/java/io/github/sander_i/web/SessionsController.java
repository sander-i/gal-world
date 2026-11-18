/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.domain.Game;
import io.github.sander_i.platform.CommandRunner;
import io.github.sander_i.platform.GameWindowProbe;
import io.github.sander_i.platform.OsKind;
import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.service.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 批量会话状态：首页一次轮询拿全部游戏，替代每游戏一个 /api/games/{id}/session。
 *
 * <p>Linux 上一次 systemctl 拿全部单元，TCP 端口与窗口探测仍逐游戏做
 * （它们只对 RUNNING 的游戏有意义）。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionsController {

    /** 单次请求上限，防止有人拿它当全库扫描接口 */
    private static final int MAX_IDS = 200;

    private final GameRepository games;
    private final SessionService sessions;
    private final CommandRunner runner;
    private final GameWindowProbe windowProbe;

    public SessionsController(GameRepository games, SessionService sessions,
                              CommandRunner runner, GameWindowProbe windowProbe) {
        this.games = games;
        this.sessions = sessions;
        this.runner = runner;
        this.windowProbe = windowProbe;
    }

    @GetMapping
    public Map<String, SessionController.SessionStatusView> statuses(
            @RequestParam(name = "ids") String ids) {
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String part : ids.split(",")) {
            String id = part.strip();
            if (!id.isEmpty()) {
                wanted.add(id);
            }
            if (wanted.size() >= MAX_IDS) {
                break;
            }
        }
        Map<String, SessionController.SessionStatusView> out = new LinkedHashMap<>();
        if (wanted.isEmpty()) {
            return out;
        }
        List<Game> found = new ArrayList<>();
        for (Game g : games.findAllById(wanted)) {
            found.add(g);
        }
        var states = sessions.statuses(found);
        for (Game g : found) {
            var st = states.get(g.getId());
            if (st == null) {
                continue;
            }
            boolean portOk = g.getPort() >= 9000 && g.getPort() <= 9100
                    && runner.portOpen(g.getPort());
            boolean streamReady = st.streamUrl() != null
                    || OsKind.current() != OsKind.LINUX
                    || portOk;
            out.put(g.getId(), new SessionController.SessionStatusView(
                    g.getId(), st.state().name(), st.crashed(), st.streamUrl(),
                    streamReady,
                    // 窗口探测是 fork xwininfo，只对运行中的会话做
                    st.state() == SessionService.State.RUNNING
                            ? windowProbe.present(g.getDisplayNum()) : null));
        }
        return out;
    }
}
