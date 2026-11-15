/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import com.galworld.domain.LocalizationGroup;
import com.galworld.repo.GameLocalizationRepository;
import com.galworld.repo.LocalizationGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class LocalizationGroupServiceTest {

    private LocalizationGroupRepository groups;
    private GameLocalizationRepository links;
    private LocalizationGroupService service;

    @BeforeEach
    void setUp() {
        groups = mock(LocalizationGroupRepository.class);
        links = mock(GameLocalizationRepository.class);
        when(groups.save(any(LocalizationGroup.class))).thenAnswer(inv -> {
            LocalizationGroup g = inv.getArgument(0);
            if (g.getId() == null) g.setId(1L);
            return g;
        });
        service = new LocalizationGroupService(groups, links);
    }

    @Test
    void createRejectsBlankName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create("  ", "别名", "example.com", "简介"));
        assertEquals("汉化组名不能为空", e.getMessage());
    }

    @Test
    void createNormalizesAliasWebsiteAndDescription() {
        when(groups.findByNameAndAliasAndWebsite("Key", "KEY", "https://key.example.com"))
                .thenReturn(Optional.empty());

        LocalizationGroup g = service.create(" Key ", " KEY ", "key.example.com", " 老牌汉化组 ");

        assertEquals("Key", g.getName());
        assertEquals("KEY", g.getAlias());
        assertEquals("https://key.example.com", g.getWebsite());
        assertEquals("老牌汉化组", g.getDescription());
    }

    @Test
    void createLooksUpIdentityByAliasToo() {
        when(groups.findByNameAndAliasAndWebsite("Key", "OTHER", "https://key.example.com"))
                .thenReturn(Optional.empty());

        service.create("Key", "OTHER", "https://key.example.com", "");

        verify(groups).findByNameAndAliasAndWebsite("Key", "OTHER", "https://key.example.com");
        verify(groups).save(argThat(g -> "OTHER".equals(g.getAlias())));
    }

    @Test
    void updateRejectsDuplicateIdentity() {
        LocalizationGroup existing = group(1L, "Key", "KEY", "https://key.example.com");
        LocalizationGroup other = group(2L, "Key", "OTHER", "https://key.example.com");
        when(groups.findById(1L)).thenReturn(Optional.of(existing));
        when(groups.findByNameAndAliasAndWebsite("Key", "OTHER", "https://key.example.com"))
                .thenReturn(Optional.of(other));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.update(1L, "Key", "OTHER", "https://key.example.com", ""));
        assertEquals("汉化组名 + 别名 + 网址已存在", e.getMessage());
    }

    @Test
    void setForGameReusesExistingGroupAndDoesNotClearDescription() {
        LocalizationGroup existing = group(7L, "Key", "KEY", "https://key.example.com");
        existing.setDescription("已有简介");
        when(groups.findById(7L)).thenReturn(Optional.of(existing));

        service.setForGame("game-a", List.of(
                new LocalizationGroupService.GroupRef(7L, "Key", "KEY", "https://key.example.com", "")));

        assertEquals("已有简介", existing.getDescription());
        verify(links).save(argThat(l -> l.getGroupId() == 7L));
    }

    @Test
    void setForGameCreatesByThreeFieldIdentityWithDescription() {
        when(groups.findByNameAndAliasAndWebsite("Key", "KEY", "https://key.example.com"))
                .thenReturn(Optional.empty());

        service.setForGame("game-a", List.of(
                new LocalizationGroupService.GroupRef(null, " Key ", " KEY ", "key.example.com", "新简介")));

        verify(groups).save(argThat(g -> "Key".equals(g.getName())
                && "KEY".equals(g.getAlias())
                && "https://key.example.com".equals(g.getWebsite())
                && "新简介".equals(g.getDescription())));
    }

    private LocalizationGroup group(long id, String name, String alias, String website) {
        LocalizationGroup g = new LocalizationGroup();
        g.setId(id);
        g.setName(name);
        g.setAlias(alias);
        g.setWebsite(website);
        return g;
    }
}
