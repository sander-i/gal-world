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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 元件仓库地址的默认值来自配置（GALWORLD_ARTIFACT_NEXUS），不是写死的 127.0.0.1。
 *
 * 2026-11-09 真机事故：页面上显示默认值，用户「原样保存」把它写进库，
 * 于是设置覆盖了环境变量，一键安装在全新机器上去连 127.0.0.1:8081 失败。
 */
class SettingsServiceArtifactDefaultTest {

    private static final String NEXUS = "http://127.0.0.1:8081/repository/galworld-artifacts";

    private SettingsService service(SettingRepository repo) {
        SettingsService s = new SettingsService(repo, e -> { });
        s.setConfiguredNexusBase(NEXUS);
        return s;
    }

    @Test
    void 未设置时默认值取配置里的仓库地址() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(anyString())).thenReturn(Optional.empty());

        assertEquals(NEXUS, service(repo).get(SettingsService.KEY_ARTIFACT_NEXUS));
        assertEquals(NEXUS, service(repo).all().get(SettingsService.KEY_ARTIFACT_NEXUS));
    }

    @Test
    void 保存与默认值相同的值等于清除该设置() {
        SettingRepository repo = mock(SettingRepository.class);
        Setting stored = new Setting(SettingsService.KEY_ARTIFACT_NEXUS, "http://old.example/repo");
        when(repo.findById(SettingsService.KEY_ARTIFACT_NEXUS)).thenReturn(Optional.of(stored));
        when(repo.findAll()).thenReturn(List.of());

        service(repo).update(Map.of(SettingsService.KEY_ARTIFACT_NEXUS, NEXUS));

        verify(repo).delete(stored);
        verify(repo, never()).save(any(Setting.class));
    }

    @Test
    void 保存不同的地址会落库() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findById(SettingsService.KEY_ARTIFACT_NEXUS)).thenReturn(Optional.empty());
        when(repo.findAll()).thenReturn(List.of());

        service(repo).update(Map.of(SettingsService.KEY_ARTIFACT_NEXUS, "http://other.example/repo"));

        verify(repo).save(any(Setting.class));
    }
}
