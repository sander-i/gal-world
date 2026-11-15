/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.repo;

import io.github.sander_i.domain.GameTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface GameTagRepository extends JpaRepository<GameTag, GameTag.Key> {
    List<GameTag> findByGameId(String gameId);
    List<GameTag> findByGameIdIn(Collection<String> gameIds);
    void deleteByGameId(String gameId);
    void deleteByTagId(Long tagId);
}
