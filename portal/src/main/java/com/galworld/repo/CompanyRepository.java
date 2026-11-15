/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.repo;

import com.galworld.domain.Company;
import com.galworld.domain.GameCompany;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {
    Optional<Company> findByNameAndAliasAndWebsite(String name, String alias, String website);
    List<Company> findByName(String name);
    List<Company> findAllByOrderBySortOrderAscNameAscAliasAscWebsiteAsc();
}
