/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** 一次步骤执行的运行时状态。同一时刻只允许一个 RUNNING。 */
public final class DeployRun {

    public enum State {
        QUEUED,
        RUNNING,
        SUCCESS,
        FAILED,
        BLOCKED,
        NEED_ELEVATION,
        CANCELLED
    }

    private final String id;
    private final String stepId;
    private final long startedAt = System.currentTimeMillis();
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final List<SseEmitter> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
    private volatile long finishedAt;
    private volatile int exit = -1;
    private volatile String error = "";
    private volatile Process process;
    /** 本次执行的一次性 sudo 密码；执行结束立即清零 */
    private volatile char[] sudo;

    public DeployRun(String id, String stepId) {
        this.id = id;
        this.stepId = stepId;
    }

    public String id() { return id; }

    public String stepId() { return stepId; }

    public long startedAt() { return startedAt; }

    public long finishedAt() { return finishedAt; }

    public int exit() { return exit; }

    public String error() { return error; }

    public State state() { return state.get(); }

    public List<String> lines() { return lines; }

    public Process process() { return process; }

    public void process(Process p) { this.process = p; }

    public boolean terminal() {
        State s = state.get();
        return s == State.SUCCESS || s == State.FAILED || s == State.CANCELLED
                || s == State.BLOCKED || s == State.NEED_ELEVATION;
    }

    public void state(State s) {
        state.set(s);
        if (terminal()) {
            finishedAt = System.currentTimeMillis();
            listeners.forEach(DeployRun::completeQuietly);
            listeners.clear();
        }
    }

    public void exit(int code) { this.exit = code; }

    public void error(String msg) { this.error = msg == null ? "" : msg; }

    public char[] sudo() { return sudo; }

    public void sudo(char[] value) { this.sudo = value; }

    /** 执行结束清零密码，任何分支都要调用 */
    public void clearSudo() {
        char[] p = sudo;
        if (p != null) {
            java.util.Arrays.fill(p, '\0');
        }
        sudo = null;
    }

    public void addListener(SseEmitter emitter) {
        for (String line : lines) {
            sendQuietly(emitter, "log", line);
        }
        if (terminal()) {
            completeQuietly(emitter);
            return;
        }
        listeners.add(emitter);
    }

    /** 追加一行日志，同时推给所有在线 SSE 客户端 */
    public void log(String line) {
        lines.add(line);
        event("log", line);
    }

    /** 推送一个命名 SSE 事件（如 step / done）；没有监听者时静默 */
    public void event(String name, String data) {
        for (SseEmitter e : listeners) {
            sendQuietly(e, name, data);
        }
    }

    private static void sendQuietly(SseEmitter e, String name, String data) {
        try {
            e.send(SseEmitter.event().name(name).data(data));
        } catch (Exception ignore) {
            // 客户端断开：忽略，日志仍保留在 lines 里
        }
    }

    private static void completeQuietly(SseEmitter e) {
        try {
            e.send(SseEmitter.event().name("state").data("done"));
            e.complete();
        } catch (Exception ignore) {
            // 已断开
        }
    }
}
