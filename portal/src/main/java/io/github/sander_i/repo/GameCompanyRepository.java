/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.repo;

import io.github.sander_i.domain.GameCompany;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface GameCompanyRepository extends JpaRepository<GameCompany, GameCompany.Key> {
    List<GameCompany> findByGameId(String gameId);
    List<GameCompany> findByGameIdIn(Collection<String> gameIds);
    void deleteByGameId(String gameId);
}
