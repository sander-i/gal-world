/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.platform.AccountInfo;
import io.github.sander_i.platform.EnvProbe;
import io.github.sander_i.platform.EnvScheme;
import io.github.sander_i.platform.LinuxEnvProbe;
import io.github.sander_i.platform.OsKind;
import io.github.sander_i.platform.PlatformInfo;
import io.github.sander_i.platform.WindowsEnvProbe;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 设置页「环境信息」的数据源。
 *
 * 按当前操作系统分发到对应的探测实现：
 * Linux 给出 Selkies / Docker / xpra / Windows VM，Windows 给出 Sunshine / VNC / RDP / WSL。
 * {@code ?os=linux|windows} 可预览另一平台的方案：只返回静态说明与命令，
 * 组件状态与权限一律不可探测，避免把本机探测结果冒充成另一系统。
 */
@RestController
public class EnvironmentController {

    private final LinuxEnvProbe linux;
    private final WindowsEnvProbe windows;

    public EnvironmentController(LinuxEnvProbe linux, WindowsEnvProbe windows) {
        this.linux = linux;
        this.windows = windows;
    }

    @GetMapping("/api/environment")
    public Map<String, Object> environment(@RequestParam(value = "os", required = false) String requested) {
        OsKind current = OsKind.current();
        OsKind want = parseOs(requested);
        boolean preview = want != null && want != current;
        EnvProbe probe = probeFor(preview ? want : current);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currentOs", current.name());
        out.put("preview", preview);
        if (preview) {
            // 预览另一平台：不读取本机账号与组件状态，只保留方案说明与命令。
            AccountInfo neutral = new AccountInfo("不可探测", false, List.of(), List.of());
            List<EnvScheme> schemes = probe.schemes(neutral).stream()
                    .map(EnvironmentController::withoutLiveState).toList();
            out.put("os", previewOs(want));
            out.put("schemes", schemes);
            return out;
        }

        AccountInfo account = probe.account();
        List<EnvScheme> schemes = probe.schemes(account);
        PlatformInfo os = probe.os();

        out.put("os", osMap(os));
        out.put("account", accountMap(account));
        out.put("schemes", schemes);
        return out;
    }

    private EnvProbe probeFor(OsKind kind) {
        return kind == OsKind.WINDOWS ? windows : linux;
    }

    /** 只接受 LINUX / WINDOWS；空值或未知值按「当前系统」处理。 */
    static OsKind parseOs(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String v = requested.trim().toUpperCase(Locale.ROOT);
        if (v.equals("LINUX")) {
            return OsKind.LINUX;
        }
        return v.equals("WINDOWS") ? OsKind.WINDOWS : null;
    }

    /**
     * 预览用的方案：丢掉 groups 与 permissions。
     * 这两块来自本机探测，在另一平台上没有意义，留着会显示成错误的「未安装」。
     */
    static EnvScheme withoutLiveState(EnvScheme s) {
        return new EnvScheme(s.key(), s.name(), s.status(), s.tagline(), s.description(),
                s.pros(), s.cons(), s.requirements(), List.of(), s.commands(), List.of(),
                s.elevate(), s.restore());
    }

    private Map<String, Object> previewOs(OsKind kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind.name());
        m.put("name", kind == OsKind.WINDOWS ? "Windows" : "Linux");
        m.put("version", "非当前系统");
        m.put("arch", "—");
        m.put("host", "—");
        return m;
    }

    private Map<String, Object> osMap(PlatformInfo os) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", os.kind().name());
        m.put("name", os.name());
        m.put("version", os.version());
        m.put("arch", os.arch());
        m.put("host", os.host());
        return m;
    }

    private Map<String, Object> accountMap(AccountInfo account) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", account.user());
        m.put("elevated", account.elevated());
        m.put("groups", account.groups());
        m.put("checks", account.checks());
        return m;
    }
}
