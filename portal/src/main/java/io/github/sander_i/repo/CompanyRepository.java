/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.repo;

import io.github.sander_i.domain.Company;
import io.github.sander_i.domain.GameCompany;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {
    Optional<Company> findByNameAndAliasAndWebsite(String name, String alias, String website);
    List<Company> findByName(String name);
    List<Company> findAllByOrderBySortOrderAscNameAscAliasAscWebsiteAsc();
}
