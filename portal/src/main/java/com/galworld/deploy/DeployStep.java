/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.deploy;

import com.galworld.platform.OsKind;

import java.time.Duration;
import java.util.List;

/**
 * 一个部署步骤的不可变定义。
 *
 * 关键约束：命令来自代码/资源文件，前端只能传 {@code id}。
 * 任何来自前端的命令文本都必须拒绝，否则部署 API 等于远程命令执行。
 *
 * @param id             唯一 id，如 {@code win.sunshine}
 * @param kind           动作类型，决定执行方式
 * @param runAs          需要的权限级别
 * @param destructive    是否危险操作（需二次确认）
 * @param artifactId     kind=DOWNLOAD/EXEC 需要元件时填写，占位符 ${artifact:xxx} 会指向它
 * @param command        固定命令或脚本片段（占位符白名单替换后执行）
 * @param requiresReboot 执行后是否需要重启才能继续
 */
public record DeployStep(
        String id,
        String title,
        String summary,
        OsKind os,
        List<String> requires,
        Kind kind,
        RunAs runAs,
        boolean destructive,
        Duration timeout,
        String artifactId,
        List<String> command,
        boolean requiresReboot,
        String successHint,
        String probe) {

    public enum Kind {
        /** 只检查，不改系统 */
        PREFLIGHT,
        /** 下载元件到本地缓存 */
        DOWNLOAD,
        /** 执行命令/脚本 */
        EXEC,
        /** 写配置文件（内容来自步骤表，非用户输入） */
        CONFIG,
        /** 执行检查脚本并解析退出码 */
        CHECK
    }

    public enum RunAs {
        USER,
        /** Linux: sudo；Windows: 需管理员上下文 */
        ADMIN
    }
}
