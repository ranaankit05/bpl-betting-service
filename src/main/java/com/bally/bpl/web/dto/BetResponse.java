package com.bally.bpl.web.dto;

import com.bally.bpl.domain.Bet;
import com.bally.bpl.domain.BetResult;
import com.bally.bpl.domain.BetStatus;

import java.time.Instant;

/** {@code result} and {@code settledAt} are omitted while the bet is OPEN. */
public record BetResponse(
        String betId,
        String userId,
        String matchId,
        String selectedTeamId,
        BetStatus status,
        BetResult result,
        Instant placedAt,
        Instant settledAt) {

    public static BetResponse from(Bet bet) {
        return new BetResponse(bet.getBetId(), bet.getUserId(), bet.getMatchId(), bet.getSelectedTeamId(),
                bet.getStatus(), bet.getResult(), bet.getPlacedAt(), bet.getSettledAt());
    }
}
