/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.deploy;

import io.github.sander_i.config.PathConfig;
import io.github.sander_i.platform.CommandRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeployUninstallProbeTest {

    @TempDir
    Path root;

    @AfterEach
    void clearProperty() {
        System.clearProperty("galworld.deploy.unit-dir");
    }

    @Test
    void linUninstallIsReadyOnlyWhenRuntimeFilesAreGone() throws Exception {
        Path units = root.resolve("units");
        System.setProperty("galworld.deploy.unit-dir", units.toString());
        DeployProbes probes = new DeployProbes(new PathConfig(root.toString()), new CommandRunner());

        assertEquals(ProbeState.READY, probes.probe("lin.uninstall", Map.of()));

        Files.createDirectories(root.resolve("venv"));
        assertEquals(ProbeState.NOT_READY, probes.probe("lin.uninstall", Map.of()));
        Files.delete(root.resolve("venv"));

        Files.createDirectories(root.resolve(".local/wine"));
        assertEquals(ProbeState.NOT_READY, probes.probe("lin.uninstall", Map.of()));
        Files.delete(root.resolve(".local/wine"));

        Files.createDirectories(root.resolve("bin"));
        Files.writeString(root.resolve("bin/pulse-daemon.sh"), "x");
        assertEquals(ProbeState.NOT_READY, probes.probe("lin.uninstall", Map.of()));
        Files.delete(root.resolve("bin/pulse-daemon.sh"));

        Files.createDirectories(units);
        Files.writeString(units.resolve("galworld-game@.service"), "x");
        assertEquals(ProbeState.NOT_READY, probes.probe("lin.uninstall", Map.of()));
        Files.delete(units.resolve("galworld-game@.service"));

        assertEquals(ProbeState.READY, probes.probe("lin.uninstall", Map.of()));
    }
}
