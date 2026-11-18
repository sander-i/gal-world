/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.domain.Setting;
import io.github.sander_i.repo.SettingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SettingsServiceCanvasModeTest {

    private SettingsService service(SettingRepository repo) {
        return new SettingsService(repo, e -> { }, i18n());
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

    /** 语言服务：测试只关心消息模板，translate 原样返回键 */
    private static io.github.sander_i.i18n.I18nService i18n() {
        io.github.sander_i.i18n.I18nService s =
                org.mockito.Mockito.mock(io.github.sander_i.i18n.I18nService.class);
        org.mockito.Mockito.when(s.exists(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
        org.mockito.Mockito.when(s.translate(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenAnswer(inv -> inv.getArgument(1));
        return s;
    }
}
