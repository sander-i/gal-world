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

class SettingsServiceHeroSizeTest {

    private SettingsService service(SettingRepository repo) {
        return new SettingsService(repo, e -> {});
    }

    @Test
    void defaultsToMedium() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        assertEquals("medium", service(repo).homeHeroSize());
    }

    @Test
    void acceptsOff() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findById("homeHeroSize"))
                .thenReturn(Optional.of(new Setting("homeHeroSize", "off")));
        assertEquals("off", service(repo).homeHeroSize());
    }

    @Test
    void fallsBackOnUnknownValue() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findById("homeHeroSize"))
                .thenReturn(Optional.of(new Setting("homeHeroSize", "huge")));
        assertEquals("medium", service(repo).homeHeroSize());
    }

    @Test
    void updateRejectsUnknownValue() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class,
                () -> service(repo).update(Map.of("homeHeroSize", "large")));
    }
}
