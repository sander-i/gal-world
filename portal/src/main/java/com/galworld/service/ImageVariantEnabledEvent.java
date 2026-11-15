/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

/** 图片派生图开关变更事件；由 SettingsService 发布，回填任务监听。 */
public record ImageVariantEnabledEvent(boolean enabled) {
}
