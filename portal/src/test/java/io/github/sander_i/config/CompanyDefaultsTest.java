/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.config;

import io.github.sander_i.domain.Company;
import io.github.sander_i.repo.CompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CompanyDefaultsTest {

    private CompanyRepository companies;
    private CompanyDefaults defaults;

    @BeforeEach
    void setUp() {
        companies = mock(CompanyRepository.class);
        defaults = new CompanyDefaults(companies);
    }

    @Test
    void everySeedHasNameWebsiteAndDescription() {
        assertFalse(CompanyDefaults.DEFAULTS.isEmpty());
        for (CompanyDefaults.Seed s : CompanyDefaults.DEFAULTS) {
            assertFalse(s.name().isBlank(), "公司名不能为空");
            assertTrue(s.website().startsWith("https://"), "网址必须是 https: " + s.name());
            assertFalse(s.description().isBlank(), "简介不能为空: " + s.name());
        }
    }

    @Test
    void insertsMissingCompanies() {
        when(companies.findByNameAndAliasAndWebsite(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        defaults.run(null);

        verify(companies, times(CompanyDefaults.DEFAULTS.size())).save(any(Company.class));
    }

    @Test
    void skipsCompaniesThatAlreadyExist() {
        when(companies.findByNameAndAliasAndWebsite(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new Company()));

        defaults.run(null);

        verify(companies, never()).save(any(Company.class));
    }
}
