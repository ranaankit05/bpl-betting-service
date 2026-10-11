package com.bally.bpl.service;

import com.bally.bpl.domain.BetResult;
import com.bally.bpl.domain.BetStatus;
import com.bally.bpl.domain.Match;
import com.bally.bpl.domain.MatchScore;
import com.bally.bpl.domain.MatchStatus;
import com.bally.bpl.domain.ScoreId;
import com.bally.bpl.repository.BetRepository;
import com.bally.bpl.repository.MatchRepository;
import com.bally.bpl.repository.MatchScoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Match lifecycle, live scores and bet settlement.
 *
 * <p>Concurrency model: every mutation locks the match row with {@code SELECT ... FOR UPDATE}
 * ({@link MatchRepository#findByIdForUpdate}). Bet placement uses the same lock, so a bet can never be
 * inserted while the match is being completed and settled: it either commits before settlement (and is
 * settled with the rest) or is rejected because the match is no longer open for betting.
 */
@Service
public class MatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchService.class);

    private final MatchRepository matches;
    private final MatchScoreRepository scores;
    private final BetRepository bets;
    private final Clock clock;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    public MatchService(MatchRepository matches, MatchScoreRepository scores, BetRepository bets,
                        Clock clock, PlatformTransactionManager txManager) {
        this.matches = matches;
        this.scores = scores;
        this.bets = bets;
        this.clock = clock;
        this.writeTx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    // ------------------------------------------------------------------ writes

    public UpsertResult upsert(UpsertMatchCommand cmd) {
        validate(cmd);
        try {
            return writeTx.execute(status -> doUpsert(cmd));
        } catch (DataIntegrityViolationException e) {
            // Two concurrent PUTs created the same match; the loser hit the primary key. Retry once, it
            // will now find the committed row and take the update path.
            log.debug("Concurrent create of match {} detected, retrying as update", cmd.matchId());
            return writeTx.execute(status -> doUpsert(cmd));
        }
    }

    public MatchView updateScore(String matchId, String teamId, int runs, int wickets, BigDecimal overs) {
        validateOvers(overs);
        return writeTx.execute(status -> {
            Match match = lockMatch(matchId);
            if (match.getStatus() != MatchStatus.LIVE) {
                throw ApiException.conflict("MATCH_NOT_LIVE",
                        "Scores can only be updated while the match is LIVE (current status: " + match.getStatus() + ")");
            }
            if (!match.hasTeam(teamId)) {
                throw ApiException.unprocessable("INVALID_TEAM",
                        "Team " + teamId + " does not play in match " + matchId);
            }
            Instant now = now();
            ScoreId id = new ScoreId(matchId, teamId);
            MatchScore score = scores.findById(id).orElse(null);
            BigDecimal normalised = overs.setScale(1);
            if (score == null) {
                scores.saveAndFlush(new MatchScore(id, runs, wickets, normalised, now));
            } else {
                score.update(runs, wickets, normalised, now);
                scores.saveAndFlush(score);
            }
            match.touch(now);
            matches.saveAndFlush(match);
            return new MatchView(match, scores.findByMatchId(matchId));
        });
    }

    // ------------------------------------------------------------------ reads

    public MatchView get(String matchId) {
        return readTx.execute(status -> {
            Match match = matches.findById(matchId).orElseThrow(() -> notFound(matchId));
            return new MatchView(match, scores.findByMatchId(matchId));
        });
    }

    public PageResult<MatchView> list(MatchStatus status, int page, int size) {
        return readTx.execute(tx -> {
            Pageable pageable = PageRequest.of(page, size, Sort.by("startTime").ascending().and(Sort.by("matchId")));
            Page<Match> result = status == null ? matches.findAll(pageable) : matches.findByStatus(status, pageable);
            List<String> ids = result.getContent().stream().map(Match::getMatchId).toList();
            Map<String, List<MatchScore>> byMatch = ids.isEmpty()
                    ? Map.of()
                    : scores.findByMatchIds(ids).stream().collect(Collectors.groupingBy(MatchScore::getMatchId));
            List<MatchView> views = result.getContent().stream()
                    .map(m -> new MatchView(m, byMatch.getOrDefault(m.getMatchId(), List.of())))
                    .toList();
            return new PageResult<>(views, result.getTotalElements());
        });
    }

    // ------------------------------------------------------------------ internals

    private UpsertResult doUpsert(UpsertMatchCommand cmd) {
        Instant now = now();
        Optional<Match> existing = matches.findByIdForUpdate(cmd.matchId());
        if (existing.isEmpty()) {
            Match match = new Match(cmd.matchId(), cmd.name(), cmd.team1Id(), cmd.team1Name(),
                    cmd.team2Id(), cmd.team2Name(), cmd.startTime(), cmd.status(), now);
            if (cmd.status() == MatchStatus.COMPLETED) {
                match.declareWinner(cmd.winnerTeamId());
            }
            matches.saveAndFlush(match);
            return new UpsertResult(new MatchView(match, List.of()), true);
        }
        return new UpsertResult(update(existing.get(), cmd, now), false);
    }

    private MatchView update(Match match, UpsertMatchCommand cmd, Instant now) {
        if (match.getStatus() == MatchStatus.COMPLETED) {
            if (isUnchanged(match, cmd)) {
                return new MatchView(match, scores.findByMatchId(match.getMatchId())); // idempotent replay
            }
            throw ApiException.conflict("MATCH_COMPLETED", "Match " + match.getMatchId() + " is completed and cannot be modified");
        }
        Set<String> requestedTeams = new HashSet<>(List.of(cmd.team1Id(), cmd.team2Id()));
        if (!requestedTeams.equals(new HashSet<>(match.teamIds()))) {
            throw ApiException.conflict("TEAMS_IMMUTABLE", "The teams of an existing match cannot be changed");
        }
        if (!match.getStatus().canMoveTo(cmd.status())) {
            throw ApiException.conflict("INVALID_STATUS_TRANSITION",
                    "Cannot move match from " + match.getStatus() + " to " + cmd.status());
        }
        Map<String, String> names = Map.of(cmd.team1Id(), cmd.team1Name(), cmd.team2Id(), cmd.team2Name());
        match.rename(cmd.name(), names.get(match.getTeam1Id()), names.get(match.getTeam2Id()));
        match.reschedule(cmd.startTime());
        match.touch(now);

        boolean completing = cmd.status() == MatchStatus.COMPLETED;
        String winner = null;
        if (completing) {
            winner = cmd.winnerTeamId() != null ? cmd.winnerTeamId() : deriveWinner(match);
            match.declareWinner(winner);
        }
        match.changeStatus(cmd.status());
        // Persist the match before the bulk settlement statements; do not touch the entity afterwards.
        matches.saveAndFlush(match);
        if (completing) {
            settle(match.getMatchId(), winner, now);
        }
        return new MatchView(match, scores.findByMatchId(match.getMatchId()));
    }

    /** Higher runs wins; a tie or missing score for either team means no result (null). */
    private String deriveWinner(Match match) {
        Map<String, Integer> runs = scores.findByMatchId(match.getMatchId()).stream()
                .collect(Collectors.toMap(MatchScore::getTeamId, MatchScore::getRuns));
        if (runs.size() != 2) {
            return null;
        }
        int r1 = runs.get(match.getTeam1Id());
        int r2 = runs.get(match.getTeam2Id());
        if (r1 == r2) {
            return null;
        }
        return r1 > r2 ? match.getTeam1Id() : match.getTeam2Id();
    }

    private void settle(String matchId, String winnerTeamId, Instant now) {
        int won = 0;
        int lost = 0;
        int voided = 0;
        if (winnerTeamId == null) {
            voided = bets.settleAll(matchId, BetResult.VOID, now, BetStatus.OPEN, BetStatus.SETTLED);
        } else {
            won = bets.settleSelecting(matchId, winnerTeamId, BetResult.WON, now, BetStatus.OPEN, BetStatus.SETTLED);
            lost = bets.settleNotSelecting(matchId, winnerTeamId, BetResult.LOST, now, BetStatus.OPEN, BetStatus.SETTLED);
        }
        log.info("Settled match {} winner={} won={} lost={} void={}", matchId, winnerTeamId, won, lost, voided);
    }

    private boolean isUnchanged(Match match, UpsertMatchCommand cmd) {
        Map<String, String> requested = Map.of(cmd.team1Id(), cmd.team1Name(), cmd.team2Id(), cmd.team2Name());
        return cmd.status() == MatchStatus.COMPLETED
                && match.getName().equals(cmd.name())
                && match.getStartTime().equals(cmd.startTime())
                && new HashSet<>(match.teamIds()).equals(requested.keySet())
                && match.getTeam1Name().equals(requested.get(match.getTeam1Id()))
                && match.getTeam2Name().equals(requested.get(match.getTeam2Id()))
                && (cmd.winnerTeamId() == null || cmd.winnerTeamId().equals(match.getWinnerTeamId()));
    }

    private void validate(UpsertMatchCommand cmd) {
        if (cmd.team1Id().equals(cmd.team2Id())) {
            throw ApiException.unprocessable("DUPLICATE_TEAM", "A match needs two different teams");
        }
        if (cmd.winnerTeamId() != null) {
            if (cmd.status() != MatchStatus.COMPLETED) {
                throw ApiException.unprocessable("WINNER_NOT_ALLOWED", "winnerTeamId is only allowed when status is COMPLETED");
            }
            if (!cmd.winnerTeamId().equals(cmd.team1Id()) && !cmd.winnerTeamId().equals(cmd.team2Id())) {
                throw ApiException.unprocessable("INVALID_WINNER", "winnerTeamId must be one of the two teams");
            }
        }
    }

    /** Overs are written as {@code overs.balls}; a ball digit above 5 is not a valid cricket over. */
    private void validateOvers(BigDecimal overs) {
        BigDecimal stripped = overs.stripTrailingZeros();
        if (stripped.scale() > 1) {
            throw ApiException.unprocessable("INVALID_OVERS", "overs must have at most one decimal digit (e.g. 17.3)");
        }
        int balls = overs.remainder(BigDecimal.ONE).movePointRight(1).intValue();
        if (balls > 5) {
            throw ApiException.unprocessable("INVALID_OVERS", "An over has 6 balls; the digit after the decimal point must be 0-5");
        }
    }

    private Match lockMatch(String matchId) {
        return matches.findByIdForUpdate(matchId).orElseThrow(() -> notFound(matchId));
    }

    private static ApiException notFound(String matchId) {
        return ApiException.notFound("MATCH_NOT_FOUND", "Match " + matchId + " not found");
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }
}
