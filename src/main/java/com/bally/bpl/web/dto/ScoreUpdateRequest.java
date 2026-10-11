package com.bally.bpl.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record ScoreUpdateRequest(
        @NotBlank @Pattern(regexp = Patterns.ID, message = "must be 1-32 chars: letters, digits, '_' or '-'") String teamId,
        @NotNull @Min(0) @Max(1000) Integer runs,
        @NotNull @Min(0) @Max(10) Integer wickets,
        @NotNull @DecimalMin("0.0") @DecimalMax("20.0") @Digits(integer = 2, fraction = 1) BigDecimal overs) {
}
