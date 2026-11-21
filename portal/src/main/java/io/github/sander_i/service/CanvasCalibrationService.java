/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.domain.Game;
import io.github.sander_i.i18n.I18n;
import io.github.sander_i.platform.CommandRunner;
import io.github.sander_i.repo.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 串流画布校准：读取游戏主窗口尺寸，把 WIDTH/HEIGHT 调到窗口附近并重启会话。
 *
 * 每次进入游玩页最多校准 4 轮；轮次用尽时回到「窗口面积最大」的那次画布。
 * 状态只放内存：后端重启或 10 分钟无操作后重新开始，避免长期留下脏状态。
 */
@Service
public class CanvasCalibrationService {

    public record Result(String status, int canvasWidth, int canvasHeight,
                         Integer windowWidth, Integer windowHeight,
                         int round, String message) {
    }

    private static final Logger log = LoggerFactory.getLogger(CanvasCalibrationService.class);
    private static final long STATE_TTL_MS = 10 * 60 * 1000L;

    private static final class State {
        int round;
        int bestW;
        int bestH;
        long bestArea;
        long at = System.currentTimeMillis();
        boolean done;
        /** 用户手动保存过分辨率：自动校准不再覆盖，直到用户点手动校准 */
        boolean pinned;
    }

    private final GameRepository games;
    private final SessionService sessions;
    private final GameScanService scan;
    private final SettingsService settings;
    private final CommandRunner runner;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public CanvasCalibrationService(GameRepository games, SessionService sessions,
                                    GameScanService scan, SettingsService settings,
                                    CommandRunner runner) {
        this.games = games;
        this.sessions = sessions;
        this.scan = scan;
        this.settings = settings;
        this.runner = runner;
    }

    /**
     * 校准入口。加锁：自动触发、手动按钮、多标签页可能同时进来，
     * 并发会各重启一次会话并丢失轮次/最佳画布状态。
     */
    public synchronized Result calibrate(String gameId, boolean manual) {
        Game game = games.findById(gameId).orElse(null);
        if (game == null) {
            return new Result("not-found", 0, 0, null, null, 0, I18n.t("没有这个游戏"));
        }
        if (!manual && !settings.canvasAutoCalibrate()) {
            return new Result("disabled", game.getWidth(), game.getHeight(),
                    null, null, 0, I18n.t("已关闭自动校准"));
        }

        State st = states.computeIfAbsent(gameId, k -> new State());
        long now = System.currentTimeMillis();
        if (now - st.at > STATE_TTL_MS) {
            // pinned 不随轮次 TTL 失效：用户手动指定要一直生效到点手动校准
            st.round = 0;
            st.bestW = 0;
            st.bestH = 0;
            st.bestArea = 0;
            st.done = false;
        }
        if (manual) {
            st.round = 0;
            st.bestW = 0;
            st.bestH = 0;
            st.bestArea = 0;
            st.done = false;
            st.pinned = false;
        }
        st.at = now;

        if (!manual && st.pinned) {
            return new Result("ok", game.getWidth(), game.getHeight(),
                    null, null, st.round,
                    I18n.t("当前画布是手动指定的，已跳过自动校准"));
        }

        if (sessions.status(gameId, game.getPort()).state() != SessionService.State.RUNNING) {
            return new Result("not-running", game.getWidth(), game.getHeight(),
                    null, null, st.round, I18n.t("游戏未运行，启动后才能测量窗口"));
        }

        WindowGeometry win = measure(game.getDisplayNum());
        if (win == null) {
            return new Result("no-window", game.getWidth(), game.getHeight(),
                    null, null, st.round, I18n.t("没有读到游戏窗口，请等画面出现后再试"));
        }

        long area = (long) win.width() * win.height();
        if (area > st.bestArea) {
            st.bestArea = area;
            st.bestW = game.getWidth();
            st.bestH = game.getHeight();
        }

        var plan = CanvasPlanner.plan(game.getWidth(), game.getHeight(),
                win.width(), win.height(), st.round, st.bestW, st.bestH, st.done);
        if (!plan.resize()) {
            st.done = true;
            // keep-large 时画布与窗口并不相等（窗口按游戏自身分辨率渲染），
            // 说成「已匹配」会让人以为画布被缩过，实际是刻意保留大画布。
            String msg = "keep-large".equals(plan.reason())
                    ? I18n.t("游戏按固定分辨率渲染（窗口 {2}×{3}），保留画布 {0}×{1}",
                            game.getWidth(), game.getHeight(), win.width(), win.height())
                    : I18n.t("画布 {0}×{1} 已匹配游戏窗口 {2}×{3}",
                            game.getWidth(), game.getHeight(), win.width(), win.height());
            return new Result("ok", game.getWidth(), game.getHeight(),
                    win.width(), win.height(), st.round, msg);
        }

        int oldW = game.getWidth();
        int oldH = game.getHeight();
        try {
            applyCanvas(game, plan.width(), plan.height());
        } catch (RuntimeException e) {
            log.warn("画布校准写入失败 {}: {}", gameId, e.toString());
            return new Result("failed", oldW, oldH, win.width(), win.height(), st.round,
                    I18n.t("保存画布失败：{0}", e.getMessage()));
        }
        st.round++;
        // 只有回退最佳画布时才终止；轮次上限由 planner 判，不能在这里提前 done，
        // 否则 restore-best 分支永远走不到。
        if ("restore-best".equals(plan.reason())) {
            st.done = true;
        }
        boolean restarted = restart(game);

        String message;
        if ("raise-floor".equals(plan.reason())) {
            // 画布被压到下限以下时抬回来，与窗口尺寸无关，说成「已匹配窗口」会误导
            message = I18n.t("画布 {0}×{1} 低于下限，提升到 {2}×{3}，{4}",
                    oldW, oldH, plan.width(), plan.height(),
                    restarted ? I18n.t("会话已重启") : I18n.t("会话重启失败，请手动重启"));
        } else {
            message = I18n.t("画布 {0}×{1} → {2}×{3}（窗口 {4}×{5}），{6}",
                    oldW, oldH, plan.width(), plan.height(), win.width(), win.height(),
                    restarted ? I18n.t("会话已重启") : I18n.t("会话重启失败，请手动重启"));
        }
        return new Result(restarted ? "restarted" : "failed",
                plan.width(), plan.height(), win.width(), win.height(), st.round, message);    }

    /** 用户手动改分辨率：记录 pin，自动校准不再覆盖；手动按钮会清除 pin */
    public synchronized void reset(String gameId) {
        State st = states.computeIfAbsent(gameId, k -> new State());
        st.pinned = true;
        st.round = 0;
        st.bestW = 0;
        st.bestH = 0;
        st.bestArea = 0;
        st.done = false;
        st.at = System.currentTimeMillis();
    }

    private void applyCanvas(Game game, int width, int height) {
        int oldW = game.getWidth();
        int oldH = game.getHeight();
        game.setWidth(width);
        game.setHeight(height);
        try {
            // 先写 env 再写 DB：env 失败时字段回滚，不会留下 DB 与串流画布不一致
            scan.writeEnv(game);
            games.save(game);
        } catch (IOException e) {
            game.setWidth(oldW);
            game.setHeight(oldH);
            throw new IllegalStateException(I18n.t("写入 game.env 失败: {0}", e.getMessage()), e);
        }
    }

    private boolean restart(Game game) {
        try {
            sessions.stop(game.getId(), game.getPort());
            var st = sessions.start(game.getId(), game.getPort());
            // 会话管理器不抛异常，失败只体现在状态里
            return st != null && (st.state() == SessionService.State.RUNNING
                    || st.state() == SessionService.State.UNKNOWN);
        } catch (RuntimeException e) {
            log.warn("画布校准后重启会话失败 {}: {}", game.getId(), e.toString());
            return false;
        }
    }

    private WindowGeometry measure(int displayNum) {
        CommandRunner.Result r = runner.exec(
                List.of("xwininfo", "-display", ":" + displayNum, "-root", "-tree"),
                Duration.ofSeconds(3));
        return r == null || !r.ok() ? null : WindowGeometry.largest(r.output());
    }
}
