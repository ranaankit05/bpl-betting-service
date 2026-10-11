package com.bally.bpl.web.dto;

import com.bally.bpl.domain.Bet;
import com.bally.bpl.domain.BetStatus;

import java.time.Instant;

public record PlaceBetResponse(String betId, BetStatus status, Instant placedAt) {

    public static PlaceBetResponse from(Bet bet) {
        return new PlaceBetResponse(bet.getBetId(), bet.getStatus(), bet.getPlacedAt());
    }
}
