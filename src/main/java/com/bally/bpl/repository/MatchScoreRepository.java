package com.bally.bpl.repository;

import com.bally.bpl.domain.MatchScore;
import com.bally.bpl.domain.ScoreId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface MatchScoreRepository extends JpaRepository<MatchScore, ScoreId> {

    @Query("select s from MatchScore s where s.id.matchId = :matchId order by s.id.teamId")
    List<MatchScore> findByMatchId(@Param("matchId") String matchId);

    /** Batch fetch used by list endpoints to avoid one query per match. */
    @Query("select s from MatchScore s where s.id.matchId in :matchIds order by s.id.matchId, s.id.teamId")
    List<MatchScore> findByMatchIds(@Param("matchIds") Collection<String> matchIds);
}
