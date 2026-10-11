package com.bally.bpl.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TeamDto(
        @NotBlank @Pattern(regexp = Patterns.ID, message = "must be 1-32 chars: letters, digits, '_' or '-'") String teamId,
        @NotBlank @Size(max = 100) String name) {
}
