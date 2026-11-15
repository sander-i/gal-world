/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.repo;

import com.galworld.domain.GameLocalization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface GameLocalizationRepository extends JpaRepository<GameLocalization, GameLocalization.Key> {
    List<GameLocalization> findAll();
    List<GameLocalization> findByGameId(String gameId);
    List<GameLocalization> findByGameIdIn(Collection<String> gameIds);
    void deleteByGameId(String gameId);
}
