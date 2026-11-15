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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目仓库地址：默认值来自配置（GALWORLD_REPO_URL），设置里改过的值优先。
 *
 * 与元件仓库地址同样的坑：页面把默认值原样保存一次就会把它固化成显式设置。
 */
class SettingsServiceRepoUrlTest {

    private static final String REPO = "https://example.com/owner/gal-world";

    private SettingsService service(SettingRepository repo) {
        SettingsService s = new SettingsService(repo, e -> { });
        s.setConfiguredRepoUrl(REPO);
        return s;
    }

    @Test
    void 未设置时默认值取配置里的仓库地址() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(anyString())).thenReturn(Optional.empty());

        assertEquals(REPO, service(repo).get(SettingsService.KEY_REPO_URL));
        assertEquals(REPO, service(repo).repoUrl());
        assertEquals(REPO, service(repo).all().get(SettingsService.KEY_REPO_URL));
    }

    @Test
    void 默认值末尾的斜杠会被去掉() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        SettingsService s = new SettingsService(repo, e -> { });
        s.setConfiguredRepoUrl(REPO + "/");

        assertEquals(REPO, s.repoUrl());
    }

    @Test
    void 保存与默认值相同的地址等于清除该设置() {
        SettingRepository repo = mock(SettingRepository.class);
        Setting stored = new Setting(SettingsService.KEY_REPO_URL, "https://old.example/gal-world");
        when(repo.findById(SettingsService.KEY_REPO_URL)).thenReturn(Optional.of(stored));
        when(repo.findAll()).thenReturn(List.of());

        service(repo).update(Map.of(SettingsService.KEY_REPO_URL, REPO + "/"));

        verify(repo).delete(stored);
        verify(repo, never()).save(any(Setting.class));
    }

    @Test
    void 非http地址会被拒绝() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findById(SettingsService.KEY_REPO_URL)).thenReturn(Optional.empty());
        when(repo.findAll()).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> service(repo).update(Map.of(SettingsService.KEY_REPO_URL, "example.com/x")));
        verify(repo, never()).save(any(Setting.class));
    }

    @Test
    void 仓库地址属于运维设置项() {
        assertEquals(true, SettingsService.ADMIN_KEYS.contains(SettingsService.KEY_REPO_URL));
    }

    @Test
    void 默认公开仓库地址() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(anyString())).thenReturn(Optional.empty());

        assertEquals(true, service(repo).repoUrlPublic());
    }

    @Test
    void 关闭公开开关后生效且只接受布尔值() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        when(repo.findAll()).thenReturn(List.of());
        SettingsService s = service(repo);

        s.update(Map.of(SettingsService.KEY_REPO_URL_PUBLIC, "FALSE"));
        verify(repo).save(any(Setting.class));

        assertThrows(IllegalArgumentException.class,
                () -> s.update(Map.of(SettingsService.KEY_REPO_URL_PUBLIC, "yes")));
    }
}
