/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.platform;

import java.util.List;

/** 按操作系统实现的环境探测。Linux 与 Windows 各一份，控制器只负责分发。 */
public interface EnvProbe {

    PlatformInfo os();

    AccountInfo account();

    List<EnvScheme> schemes(AccountInfo account);
}
