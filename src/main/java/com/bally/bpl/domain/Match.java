package com.bally.bpl.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "matches")
public class Match {

    @Id
    @Column(name = "match_id", length = 64, nullable = false, updatable = false)
    private String matchId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "team1_id", nullable = false, length = 32, updatable = false)
    private String team1Id;

    @Column(name = "team1_name", nullable = false, length = 100)
    private String team1Name;

    @Column(name = "team2_id", nullable = false, length = 32, updatable = false)
    private String team2Id;

    @Column(name = "team2_name", nullable = false, length = 100)
    private String team2Name;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MatchStatus status;

    @Column(name = "winner_team_id", length = 32)
    private String winnerTeamId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Match() {
        // for JPA
    }

    public Match(String matchId, String name, String team1Id, String team1Name,
                 String team2Id, String team2Name, Instant startTime, MatchStatus status, Instant now) {
        this.matchId = matchId;
        this.name = name;
        this.team1Id = team1Id;
        this.team1Name = team1Name;
        this.team2Id = team2Id;
        this.team2Name = team2Name;
        this.startTime = startTime;
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean hasTeam(String teamId) {
        return team1Id.equals(teamId) || team2Id.equals(teamId);
    }

    public List<String> teamIds() {
        return List.of(team1Id, team2Id);
    }

    public void rename(String newName, String newTeam1Name, String newTeam2Name) {
        this.name = newName;
        this.team1Name = newTeam1Name;
        this.team2Name = newTeam2Name;
    }

    public void reschedule(Instant newStartTime) {
        this.startTime = newStartTime;
    }

    public void changeStatus(MatchStatus newStatus) {
        this.status = newStatus;
    }

    public void declareWinner(String teamId) {
        this.winnerTeamId = teamId;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public String getMatchId() { return matchId; }
    public String getName() { return name; }
    public String getTeam1Id() { return team1Id; }
    public String getTeam1Name() { return team1Name; }
    public String getTeam2Id() { return team2Id; }
    public String getTeam2Name() { return team2Name; }
    public Instant getStartTime() { return startTime; }
    public MatchStatus getStatus() { return status; }
    public String getWinnerTeamId() { return winnerTeamId; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
