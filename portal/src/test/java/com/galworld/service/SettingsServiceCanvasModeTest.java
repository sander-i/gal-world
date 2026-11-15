/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import com.galworld.domain.Setting;
import com.galworld.repo.SettingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SettingsServiceCanvasModeTest {

    private SettingsService service(SettingRepository repo) {
        return new SettingsService(repo, e -> {});
    }

    @Test
    void defaultsToAuto() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        assertTrue(service(repo).canvasAutoCalibrate());
    }

    @Test
    void manualDisablesAuto() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findById("canvasCalibrateMode"))
                .thenReturn(Optional.of(new Setting("canvasCalibrateMode", "manual")));
        assertFalse(service(repo).canvasAutoCalibrate());
    }

    @Test
    void updateRejectsUnknownValue() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class,
                () -> service(repo).update(Map.of("canvasCalibrateMode", "always")));
    }
}
