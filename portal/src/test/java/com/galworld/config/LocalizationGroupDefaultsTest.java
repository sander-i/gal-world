/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.config;

import com.galworld.domain.LocalizationGroup;
import com.galworld.repo.LocalizationGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LocalizationGroupDefaultsTest {

    private LocalizationGroupRepository groups;
    private LocalizationGroupDefaults defaults;

    @BeforeEach
    void setUp() {
        groups = mock(LocalizationGroupRepository.class);
        defaults = new LocalizationGroupDefaults(groups);
    }

    @Test
    void everySeedHasNameAndDescription() {
        assertFalse(LocalizationGroupDefaults.DEFAULTS.isEmpty());
        for (LocalizationGroupDefaults.Seed s : LocalizationGroupDefaults.DEFAULTS) {
            assertFalse(s.name().isBlank(), "汉化组名不能为空");
            assertFalse(s.description().isBlank(), "简介不能为空: " + s.name());
        }
    }

    @Test
    void insertsMissingGroups() {
        when(groups.findByNameAndAliasAndWebsite(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        defaults.run(null);

        verify(groups, times(LocalizationGroupDefaults.DEFAULTS.size()))
                .save(any(LocalizationGroup.class));
    }

    @Test
    void skipsGroupsThatAlreadyExist() {
        when(groups.findByNameAndAliasAndWebsite(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new LocalizationGroup()));

        defaults.run(null);

        verify(groups, never()).save(any(LocalizationGroup.class));
    }
}
