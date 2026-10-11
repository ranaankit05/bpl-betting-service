package com.bally.bpl.web.dto;

import com.bally.bpl.domain.MatchStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record MatchUpsertRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull @Size(min = 2, max = 2, message = "must contain exactly two teams") List<@NotNull @Valid TeamDto> teams,
        @NotNull Instant startTime,
        @NotNull MatchStatus status,
        /* Optional. Lets the operator record the result of a rain-affected match; otherwise the winner is
           derived from the final scores. */
        @Pattern(regexp = Patterns.ID, message = "must be 1-32 chars: letters, digits, '_' or '-'") String winnerTeamId) {
}
