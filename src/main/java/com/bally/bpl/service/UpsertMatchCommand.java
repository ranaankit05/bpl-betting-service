package com.bally.bpl.service;

import com.bally.bpl.domain.MatchStatus;

import java.time.Instant;

public record UpsertMatchCommand(
        String matchId,
        String name,
        String team1Id,
        String team1Name,
        String team2Id,
        String team2Name,
        Instant startTime,
        MatchStatus status,
        /** Optional explicit winner (e.g. rain-affected result). Only valid when status is COMPLETED. */
        String winnerTeamId) {
}
