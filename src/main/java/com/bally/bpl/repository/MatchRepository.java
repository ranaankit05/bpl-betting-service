package com.bally.bpl.repository;

import com.bally.bpl.domain.Match;
import com.bally.bpl.domain.MatchStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MatchRepository extends JpaRepository<Match, String> {

    /**
     * Loads the match with {@code SELECT ... FOR UPDATE}. Every write that depends on the state of a
     * match (placing a bet, updating a score, changing status, settlement) goes through this method, so
     * those operations are serialised per match by the database itself.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Match m where m.matchId = :matchId")
    Optional<Match> findByIdForUpdate(@Param("matchId") String matchId);

    Page<Match> findByStatus(MatchStatus status, Pageable pageable);
}
