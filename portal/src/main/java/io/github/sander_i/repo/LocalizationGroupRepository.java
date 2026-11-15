/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.repo;

import io.github.sander_i.domain.LocalizationGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LocalizationGroupRepository extends JpaRepository<LocalizationGroup, Long> {
    Optional<LocalizationGroup> findByNameAndAliasAndWebsite(String name, String alias, String website);
    List<LocalizationGroup> findByName(String name);
    List<LocalizationGroup> findAllByOrderBySortOrderAscNameAscAliasAscWebsiteAsc();
}
