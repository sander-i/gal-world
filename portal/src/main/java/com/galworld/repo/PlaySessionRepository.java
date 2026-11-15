/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.repo;

import com.galworld.domain.PlaySession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PlaySessionRepository extends JpaRepository<PlaySession, Long> {

    Optional<PlaySession> findFirstByGameIdAndEndedAtIsNullOrderByStartedAtDesc(String gameId);

    List<PlaySession> findByGameIdAndEndedAtIsNull(String gameId);

    List<PlaySession> findByGameIdOrderByStartedAtDesc(String gameId);

    /** 已结束记录的累计秒数 */
    @Query("select coalesce(sum(s.seconds), 0) from PlaySession s where s.gameId = :gameId and s.endedAt is not null")
    long sumClosedSeconds(@Param("gameId") String gameId);

    /** 已结束记录的累计秒数（所有游戏） */
    @Query("select coalesce(sum(s.seconds), 0) from PlaySession s where s.endedAt is not null")
    long sumAllClosedSeconds();

    long countByGameIdAndEndedAtIsNotNull(String gameId);

    /** 最近一次结束的游玩时间 */
    Optional<PlaySession> findFirstByGameIdAndEndedAtIsNotNullOrderByEndedAtDesc(String gameId);

    @Query("select max(s.lastBeatAt) from PlaySession s where s.gameId = :gameId")
    Instant lastBeatOf(@Param("gameId") String gameId);

    /** 删除游戏时一并清掉游玩历史 */
    void deleteByGameId(String gameId);
}
