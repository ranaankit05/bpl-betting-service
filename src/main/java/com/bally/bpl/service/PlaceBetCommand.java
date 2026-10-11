package com.bally.bpl.service;

public record PlaceBetCommand(String userId, String matchId, String selectedTeamId, String idempotencyKey) {
}
