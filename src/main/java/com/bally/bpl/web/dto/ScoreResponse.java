package com.bally.bpl.web.dto;

import java.math.BigDecimal;

public record ScoreResponse(String teamId, int runs, int wickets, BigDecimal overs) {
}
