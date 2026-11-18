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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 设置快照缓存：列表页靠它把每游戏单键查询降成每请求一次 */
class SettingsServiceCacheTest {

    private SettingsService service(SettingRepository repo) {
        return new SettingsService(repo, e -> { }, mock(io.github.sander_i.i18n.I18nService.class));
    }

    @Test
    void repeatedReadsHitCache() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findAll()).thenReturn(List.of(new Setting("switchMode", "tab")));
        SettingsService s = service(repo);

        assertEquals("tab", s.get("switchMode"));
        assertEquals("tab", s.get("switchMode"));
        s.coverRotationMode();
        s.cgRotationMode();

        verify(repo, times(1)).findAll();
    }

    @Test
    void updateClearsSnapshot() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findAll()).thenReturn(List.of(new Setting("switchMode", "tab")));
        SettingsService s = service(repo);
        assertEquals("tab", s.get("switchMode"));

        // 模拟保存后库里的新值
        when(repo.findAll()).thenReturn(List.of(new Setting("switchMode", "page")));
        s.update(Map.of("switchMode", "page"));

        assertEquals("page", s.get("switchMode"), "保存后必须立刻读到新值");
    }

    @Test
    void unknownKeyFallsBackToDefault() {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        SettingsService s = service(repo);
        assertNull(s.get("不存在的键"));
    }
}
