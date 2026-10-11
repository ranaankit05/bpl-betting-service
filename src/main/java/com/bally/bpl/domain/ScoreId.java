package com.bally.bpl.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;

@Embeddable
public record ScoreId(
        @Column(name = "match_id", length = 64, nullable = false) String matchId,
        @Column(name = "team_id", length = 32, nullable = false) String teamId) implements Serializable {
}
