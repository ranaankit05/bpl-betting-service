package com.bally.bpl.web.dto;

import com.bally.bpl.domain.Match;
import com.bally.bpl.domain.MatchScore;
import com.bally.bpl.domain.MatchStatus;
import com.bally.bpl.service.MatchView;

import java.time.Instant;
import java.util.List;

public record MatchResponse(
        String matchId,
        String name,
        List<TeamDto> teams,
        Instant startTime,
        MatchStatus status,
        List<ScoreResponse> scores,
        /* Present only once the match is COMPLETED with a result; absent for a tie / no result. */
        String winnerTeamId) {

    public static MatchResponse from(MatchView view) {
        Match m = view.match();
        return new MatchResponse(
                m.getMatchId(),
                m.getName(),
                List.of(new TeamDto(m.getTeam1Id(), m.getTeam1Name()), new TeamDto(m.getTeam2Id(), m.getTeam2Name())),
                m.getStartTime(),
                m.getStatus(),
                view.scores().stream().map(MatchResponse::score).toList(),
                m.getWinnerTeamId());
    }

    private static ScoreResponse score(MatchScore s) {
        return new ScoreResponse(s.getTeamId(), s.getRuns(), s.getWickets(), s.getOvers());
    }
}
