/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.domain.Company;
import io.github.sander_i.repo.CompanyRepository;
import io.github.sander_i.repo.GameCompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class CompanyServiceTest {

    private CompanyRepository companies;
    private GameCompanyRepository links;
    private CompanyService service;

    @BeforeEach
    void setUp() {
        companies = mock(CompanyRepository.class);
        links = mock(GameCompanyRepository.class);
        when(companies.save(any(Company.class))).thenAnswer(inv -> {
            Company c = inv.getArgument(0);
            if (c.getId() == null) c.setId(1L);
            return c;
        });
        service = new CompanyService(companies, links);
    }

    @Test
    void createRejectsBlankName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create("  ", "别名", "example.com", "简介"));
        assertEquals("公司名不能为空", e.getMessage());
    }

    @Test
    void createNormalizesAliasWebsiteAndDescription() {
        when(companies.findByNameAndAliasAndWebsite("Key", "KEY", "https://key.example.com"))
                .thenReturn(Optional.empty());

        Company c = service.create(" Key ", " KEY ", "key.example.com", " 老牌公司 ");

        assertEquals("Key", c.getName());
        assertEquals("KEY", c.getAlias());
        assertEquals("https://key.example.com", c.getWebsite());
        assertEquals("老牌公司", c.getDescription());
    }

    @Test
    void createLooksUpIdentityByAliasToo() {
        when(companies.findByNameAndAliasAndWebsite("Key", "OTHER", "https://key.example.com"))
                .thenReturn(Optional.empty());

        service.create("Key", "OTHER", "https://key.example.com", "");

        verify(companies).findByNameAndAliasAndWebsite("Key", "OTHER", "https://key.example.com");
        verify(companies).save(argThat(c ->
                "Key".equals(c.getName())
                        && "OTHER".equals(c.getAlias())
                        && "https://key.example.com".equals(c.getWebsite())));
    }

    @Test
    void updateRejectsDuplicateIdentity() {
        Company existing = company(1L, "Key", "KEY", "https://key.example.com");
        Company other = company(2L, "Key", "OTHER", "https://key.example.com");
        when(companies.findById(1L)).thenReturn(Optional.of(existing));
        when(companies.findByNameAndAliasAndWebsite("Key", "OTHER", "https://key.example.com"))
                .thenReturn(Optional.of(other));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.update(1L, "Key", "OTHER", "https://key.example.com", ""));
        assertEquals("公司名 + 别名 + 网址已存在", e.getMessage());
    }

    @Test
    void setForGameReusesExistingCompanyAndDoesNotClearDescription() {
        Company existing = company(7L, "Key", "KEY", "https://key.example.com");
        existing.setDescription("已有简介");
        when(companies.findById(7L)).thenReturn(Optional.of(existing));

        service.setForGame("game-a", List.of(
                new CompanyService.CompanyRef(7L, "Key", "KEY", "https://key.example.com", "")));

        assertEquals("已有简介", existing.getDescription());
        verify(links).save(argThat(l -> l.getCompanyId() == 7L));
    }

    @Test
    void setForGameCreatesByThreeFieldIdentityWithDescription() {
        when(companies.findByNameAndAliasAndWebsite("Key", "KEY", "https://key.example.com"))
                .thenReturn(Optional.empty());

        service.setForGame("game-a", List.of(
                new CompanyService.CompanyRef(null, " Key ", " KEY ", "key.example.com", "新简介")));

        verify(companies).save(argThat(c ->
                "Key".equals(c.getName())
                        && "KEY".equals(c.getAlias())
                        && "https://key.example.com".equals(c.getWebsite())
                        && "新简介".equals(c.getDescription())));
    }

    @Test
    void reorderAssignsSequentialOrder() {
        Company a = company(1L, "A", "", "");
        Company b = company(2L, "B", "", "");
        when(companies.findAll()).thenReturn(List.of(a, b));

        service.reorder(List.of(2L, 1L));

        assertEquals(1, b.getSortOrder());
        assertEquals(2, a.getSortOrder());
        verify(companies).saveAll(List.of(b, a));
    }

    @Test
    void ofGameUsesPerGameOrderWhenCustomized() {
        Company a = company(1L, "A", "", "");
        Company b = company(2L, "B", "", "");
        when(companies.findAllByOrderBySortOrderAscNameAscAliasAscWebsiteAsc())
                .thenReturn(List.of(a, b));
        when(links.findByGameId("g")).thenReturn(List.of(
                new io.github.sander_i.domain.GameCompany("g", 1L),
                new io.github.sander_i.domain.GameCompany("g", 2L)));
        when(links.findByGameId("g")).thenReturn(List.of(
                link("g", 1L, 2), link("g", 2L, 1)));

        assertEquals(List.of(b, a), service.ofGame("g"));
    }

    @Test
    void ofGameFallsBackToGlobalOrderWhenAllZero() {
        Company a = company(1L, "A", "", "");
        Company b = company(2L, "B", "", "");
        when(companies.findAllByOrderBySortOrderAscNameAscAliasAscWebsiteAsc())
                .thenReturn(List.of(b, a));
        when(links.findByGameId("g")).thenReturn(List.of(link("g", 1L, 0), link("g", 2L, 0)));

        assertEquals(List.of(b, a), service.ofGame("g"));
    }

    @Test
    void setForGamePreservesExistingPerGameOrder() {
        when(links.findByGameId("g")).thenReturn(List.of(link("g", 7L, 3)));
        Company existing = company(7L, "Key", "KEY", "https://key.example.com");
        when(companies.findById(7L)).thenReturn(Optional.of(existing));

        service.setForGame("g", List.of(
                new CompanyService.CompanyRef(7L, "Key", "KEY", "https://key.example.com", "")));

        verify(links).save(argThat(l -> l.getCompanyId() == 7L && l.getSortOrder() == 3));
    }

    private io.github.sander_i.domain.GameCompany link(String gameId, Long companyId, int order) {
        io.github.sander_i.domain.GameCompany l = new io.github.sander_i.domain.GameCompany(gameId, companyId);
        l.setSortOrder(order);
        return l;
    }

    private Company company(long id, String name, String alias, String website) {
        Company c = new Company();
        c.setId(id);
        c.setName(name);
        c.setAlias(alias);
        c.setWebsite(website);
        return c;
    }
}
