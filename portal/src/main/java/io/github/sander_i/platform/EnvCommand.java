/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.platform;

/**
 * 一条只做展示、不会自动执行的命令。
 *
 * @param shell 命令语言：{@code bash} / {@code powershell}，前端据此显示标签与高亮
 */
public record EnvCommand(String title, String command, String note, String shell) {

    public EnvCommand(String title, String command, String note) {
        this(title, command, note, "bash");
    }

    public static EnvCommand powershell(String title, String command, String note) {
        return new EnvCommand(title, command, note, "powershell");
    }
}
