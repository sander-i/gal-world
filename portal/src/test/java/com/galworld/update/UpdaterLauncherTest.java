/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.update;

import com.galworld.config.PathConfig;
import com.galworld.platform.OsKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UpdaterLauncherTest {

    @TempDir Path root;

    private UpdaterLauncher launcher(OsKind os, boolean tls) {
        return new UpdaterLauncher(new PathConfig(root.toString()), os, 8088, tls);
    }

    private static String valueAfter(List<String> cmd, String flag) {
        int i = cmd.indexOf(flag);
        assertTrue(i >= 0 && i + 1 < cmd.size(), "缺少参数 " + flag);
        return cmd.get(i + 1);
    }

    @Test
    void publicConstructorIsAutowired() throws Exception {
        // 类里还有一个包可见的测试构造器：不给公开构造器加 @Autowired，
        // Spring 6.1 会退回无参构造并启动失败（0.1.4 首次真机部署就是这样挂的）
        var ctor = UpdaterLauncher.class.getConstructor(PathConfig.class, int.class, boolean.class);
        assertNotNull(ctor.getAnnotation(org.springframework.beans.factory.annotation.Autowired.class),
                "公开构造器必须标注 @Autowired");
    }

    @Test
    void unsupportedPlatformIsRejected() {
        assertFalse(launcher(OsKind.OTHER, false).supported());
        assertThrows(UnsupportedOperationException.class,
                () -> launcher(OsKind.OTHER, false).launch("upd-1"));
    }

    @Test
    void linuxCommandUsesSystemdRun() throws Exception {
        Files.createDirectories(root.resolve("bin"));
        Files.writeString(root.resolve("bin/updater.sh"), "#!/bin/bash\n");
        List<String> cmd = launcher(OsKind.LINUX, false).builder("upd-1").command();
        assertEquals("systemd-run", cmd.get(0));
        assertTrue(cmd.contains("--user"));
        assertTrue(cmd.contains("--unit=galworld-updater-1"));
        assertEquals("/bin/bash", cmd.get(cmd.size() - 2));
        assertEquals(root.resolve("bin/updater.sh").toString(), cmd.get(cmd.size() - 1));
    }

    @Test
    void linuxFailsWhenUpdaterScriptMissing() {
        assertThrows(IllegalStateException.class,
                () -> launcher(OsKind.LINUX, false).builder("upd-1"));
    }

    @Test
    void windowsCommandPassesRootPortAndScheme() throws Exception {
        List<String> cmd = launcher(OsKind.WINDOWS, true).builder("upd-1").command();
        assertEquals("powershell", cmd.get(0));
        assertEquals(root.resolve("data/update/update-portal.ps1").toString(),
                valueAfter(cmd, "-File"));
        assertEquals(root.toString(), valueAfter(cmd, "-Root"));
        assertEquals("8088", valueAfter(cmd, "-Port"));
        assertEquals("https", valueAfter(cmd, "-Scheme"));
    }

    @Test
    void windowsExtractsPackagedScriptWithBom() throws Exception {
        launcher(OsKind.WINDOWS, false).builder("upd-1");
        byte[] bytes = Files.readAllBytes(root.resolve("data/update/update-portal.ps1"));
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("GalWorldUpdater"));
    }
}
