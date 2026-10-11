package com.bally.bpl.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** Latest score of one team in one match. Overwritten in place by every score update. */
@Entity
@Table(name = "match_scores")
public class MatchScore {

    @EmbeddedId
    private ScoreId id;

    @Column(name = "runs", nullable = false)
    private int runs;

    @Column(name = "wickets", nullable = false)
    private int wickets;

    @Column(name = "overs", nullable = false, precision = 3, scale = 1)
    private BigDecimal overs;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MatchScore() {
        // for JPA
    }

    public MatchScore(ScoreId id, int runs, int wickets, BigDecimal overs, Instant now) {
        this.id = id;
        update(runs, wickets, overs, now);
    }

    public void update(int runs, int wickets, BigDecimal overs, Instant now) {
        this.runs = runs;
        this.wickets = wickets;
        this.overs = overs;
        this.updatedAt = now;
    }

    public ScoreId getId() { return id; }
    public String getMatchId() { return id.matchId(); }
    public String getTeamId() { return id.teamId(); }
    public int getRuns() { return runs; }
    public int getWickets() { return wickets; }
    public BigDecimal getOvers() { return overs; }
    public Instant getUpdatedAt() { return updatedAt; }
}
