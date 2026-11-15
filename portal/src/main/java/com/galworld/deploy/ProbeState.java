/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.deploy;

/** 步骤的幂等探测结果。 */
public enum ProbeState {
    /** 已满足，执行时直接 SUCCESS */
    READY,
    /** 未满足，需要执行 */
    NOT_READY,
    /** 无法判断，照常执行但日志提示 */
    UNKNOWN
}
