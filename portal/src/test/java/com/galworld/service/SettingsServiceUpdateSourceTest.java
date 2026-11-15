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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 远程更新源：默认值来自 GALWORLD_UPDATE_* 配置，管理页改过的值优先。
 *
 * 与项目仓库地址同一套规则：原样保存默认值等于清除设置，这样换镜像不用改库。
 */
class SettingsServiceUpdateSourceTest {

    private static final String API = "https://mirror.example/api/v1/repos";
    private static final String REPO = "mirror/gal-world";
    private static final String WEB = "https://mirror.example";

    private SettingsService service(SettingRepository repo) {
        SettingsService s = new SettingsService(repo, e -> { });
        s.setConfiguredUpdateSource(API, REPO, WEB);
        return s;
    }

    private SettingRepository emptyRepo() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        return repo;
    }

    @Test
    void 未设置时取配置里的更新源() {
        SettingsService s = service(emptyRepo());

        assertEquals(API, s.updateApiBase());
        assertEquals(REPO, s.updateRepo());
        assertEquals(WEB, s.updateWebBase());
        assertEquals(API, s.all().get(SettingsService.KEY_UPDATE_API_BASE));
        assertEquals(REPO, s.all().get(SettingsService.KEY_UPDATE_REPO));
        assertEquals(WEB, s.all().get(SettingsService.KEY_UPDATE_WEB_BASE));
    }

    @Test
    void 默认值里的斜杠会被归一化() {
        SettingRepository repo = emptyRepo();
        SettingsService s = new SettingsService(repo, e -> { });
        s.setConfiguredUpdateSource(API + "/", "/" + REPO + "/", WEB + "/");

        assertEquals(API, s.updateApiBase());
        assertEquals(REPO, s.updateRepo());
        assertEquals(WEB, s.updateWebBase());
    }

    @Test
    void 保存与默认值相同的更新源等于清除该设置() {
        SettingRepository repo = mock(SettingRepository.class);
        Setting stored = new Setting(SettingsService.KEY_UPDATE_API_BASE,
                "https://old.example/api/v5/repos");
        when(repo.findById(SettingsService.KEY_UPDATE_API_BASE)).thenReturn(Optional.of(stored));
        when(repo.findAll()).thenReturn(List.of());

        service(repo).update(Map.of(SettingsService.KEY_UPDATE_API_BASE, API + "/"));

        verify(repo).delete(stored);
        verify(repo, never()).save(any(Setting.class));
    }

    @Test
    void 非http接口地址会被拒绝() {
        SettingRepository repo = emptyRepo();

        assertThrows(IllegalArgumentException.class,
                () -> service(repo).update(
                        Map.of(SettingsService.KEY_UPDATE_API_BASE, "mirror.example/api")));
        verify(repo, never()).save(any(Setting.class));
    }

    @Test
    void 仓库路径必须是owner斜杠repo形式() {
        SettingRepository repo = emptyRepo();

        assertThrows(IllegalArgumentException.class,
                () -> service(repo).update(
                        Map.of(SettingsService.KEY_UPDATE_REPO, "not-a-path")));
        verify(repo, never()).save(any(Setting.class));
    }

    @Test
    void 更新源属于运维设置项() {
        assertEquals(true, SettingsService.ADMIN_KEYS.contains(SettingsService.KEY_UPDATE_API_BASE));
        assertEquals(true, SettingsService.ADMIN_KEYS.contains(SettingsService.KEY_UPDATE_REPO));
        assertEquals(true, SettingsService.ADMIN_KEYS.contains(SettingsService.KEY_UPDATE_WEB_BASE));
    }
}
