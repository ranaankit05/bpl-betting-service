package com.bally.bpl.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "bets")
public class Bet {

    @Id
    @Column(name = "bet_id", length = 32, nullable = false, updatable = false)
    private String betId;

    @Column(name = "user_id", length = 64, nullable = false, updatable = false)
    private String userId;

    @Column(name = "match_id", length = 64, nullable = false, updatable = false)
    private String matchId;

    @Column(name = "selected_team_id", length = 32, nullable = false, updatable = false)
    private String selectedTeamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BetStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 16)
    private BetResult result;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    protected Bet() {
        // for JPA
    }

    public Bet(String betId, String userId, String matchId, String selectedTeamId, Instant placedAt) {
        this.betId = betId;
        this.userId = userId;
        this.matchId = matchId;
        this.selectedTeamId = selectedTeamId;
        this.status = BetStatus.OPEN;
        this.placedAt = placedAt;
    }

    public String getBetId() { return betId; }
    public String getUserId() { return userId; }
    public String getMatchId() { return matchId; }
    public String getSelectedTeamId() { return selectedTeamId; }
    public BetStatus getStatus() { return status; }
    public BetResult getResult() { return result; }
    public Instant getPlacedAt() { return placedAt; }
    public Instant getSettledAt() { return settledAt; }
}
