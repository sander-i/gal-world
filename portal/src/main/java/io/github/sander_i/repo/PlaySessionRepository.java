/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.repo;

import io.github.sander_i.domain.PlaySession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
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

    /**
     * 批量聚合：每款游戏的最后一次结束时间、已结束总秒数、已结束场次。
     *
     * 列表页/首页一次取全部，替换「每游戏 5 条聚合查询」的 N+1。
     * 返回行：[gameId, max(endedAt), sum(seconds), count(s)]。
     */
    @Query("select s.gameId, max(s.endedAt), coalesce(sum(s.seconds), 0), count(s) "
            + "from PlaySession s where s.gameId in :gameIds and s.endedAt is not null "
            + "group by s.gameId")
    List<Object[]> closedStatsByGame(@Param("gameIds") Collection<String> gameIds);

    /** 进行中的记录（批量）：时长按「现在 - 开始」实时计算，不落库 */
    List<PlaySession> findByGameIdInAndEndedAtIsNull(Collection<String> gameIds);

    /** 某时间点之后结束的记录（批量），用于按自然日统计今日时长 */
    @Query("select s from PlaySession s where s.gameId in :gameIds and s.endedAt >= :since")
    List<PlaySession> findEndedSince(@Param("gameIds") Collection<String> gameIds,
                                     @Param("since") Instant since);

    /** 删除游戏时一并清掉游玩历史 */
    void deleteByGameId(String gameId);
}
