package com.bally.bpl.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PlaceBetRequest(
        @NotBlank @Pattern(regexp = Patterns.USER_ID, message = "invalid user id") String userId,
        @NotBlank @Pattern(regexp = Patterns.MATCH_ID, message = "invalid match id") String matchId,
        @NotBlank @Pattern(regexp = Patterns.ID, message = "invalid team id") String selectedTeamId) {
}
