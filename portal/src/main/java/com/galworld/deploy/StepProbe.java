/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.deploy;

import java.util.Map;

/**
 * 幂等探测：给定步骤 id，判断当前系统是否已满足该步骤的目标状态。
 *
 * 实现直接复用 {@code platform} 包里已有的检测逻辑（文件存在、端口监听、
 * 服务状态、命令可用），避免「页面显示已就绪」与「实际没装」两套判断漂移。
 *
 * 探测必须只读：不改系统、不下载、不启动服务。
 */
@FunctionalInterface
public interface StepProbe {

    ProbeState probe(String stepId, Map<String, String> ctx);

    /** 未注册探测的步骤一律 UNKNOWN，执行时照常跑。 */
    static StepProbe none() {
        return (id, ctx) -> ProbeState.UNKNOWN;
    }
}
