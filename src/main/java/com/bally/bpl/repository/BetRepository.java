package com.bally.bpl.repository;

import com.bally.bpl.domain.Bet;
import com.bally.bpl.domain.BetResult;
import com.bally.bpl.domain.BetStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface BetRepository extends JpaRepository<Bet, String> {

    boolean existsByUserIdAndMatchId(String userId, String matchId);

    Page<Bet> findByUserId(String userId, Pageable pageable);

    Page<Bet> findByUserIdAndStatus(String userId, BetStatus status, Pageable pageable);

    Page<Bet> findByMatchId(String matchId, Pageable pageable);

    Page<Bet> findByMatchIdAndStatus(String matchId, BetStatus status, Pageable pageable);

    /**
     * Bulk settlement. Callers must hold the match row lock so no new OPEN bet can appear while these
     * statements run. Each statement only touches OPEN bets, which makes settlement idempotent.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Bet b set b.status = :settled, b.result = :result, b.settledAt = :at
            where b.matchId = :matchId and b.status = :open and b.selectedTeamId = :teamId
            """)
    int settleSelecting(@Param("matchId") String matchId, @Param("teamId") String teamId,
                        @Param("result") BetResult result, @Param("at") Instant at,
                        @Param("open") BetStatus open, @Param("settled") BetStatus settled);

    @Modifying(flushAutomatically = true)
    @Query("""
            update Bet b set b.status = :settled, b.result = :result, b.settledAt = :at
            where b.matchId = :matchId and b.status = :open and b.selectedTeamId <> :teamId
            """)
    int settleNotSelecting(@Param("matchId") String matchId, @Param("teamId") String teamId,
                           @Param("result") BetResult result, @Param("at") Instant at,
                           @Param("open") BetStatus open, @Param("settled") BetStatus settled);

    @Modifying(flushAutomatically = true)
    @Query("""
            update Bet b set b.status = :settled, b.result = :result, b.settledAt = :at
            where b.matchId = :matchId and b.status = :open
            """)
    int settleAll(@Param("matchId") String matchId, @Param("result") BetResult result,
                  @Param("at") Instant at, @Param("open") BetStatus open, @Param("settled") BetStatus settled);
}
