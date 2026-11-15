/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.repo;

import com.galworld.domain.GameMedia;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface GameMediaRepository extends JpaRepository<GameMedia, Long> {
    List<GameMedia> findByGameIdOrderByKindAscSortOrderAsc(String gameId);
    List<GameMedia> findByGameIdAndKindOrderBySortOrderAsc(String gameId, String kind);
    List<GameMedia> findByGameIdInOrderByKindAscSortOrderAsc(Collection<String> gameIds);
    long countByGameIdAndKind(String gameId, String kind);
    void deleteByGameId(String gameId);
}
