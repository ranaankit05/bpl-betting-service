package com.bally.bpl.service;

import com.bally.bpl.domain.Bet;
import com.bally.bpl.domain.BetStatus;
import com.bally.bpl.domain.IdempotencyId;
import com.bally.bpl.domain.IdempotencyRecord;
import com.bally.bpl.domain.Match;
import com.bally.bpl.domain.MatchStatus;
import com.bally.bpl.repository.BetRepository;
import com.bally.bpl.repository.IdempotencyRepository;
import com.bally.bpl.repository.MatchRepository;
import com.bally.bpl.security.ApiPrincipal;
import com.bally.bpl.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Bet placement and queries.
 *
 * <p>Placement is protected by three layers:
 * <ol>
 *   <li><b>Row lock</b>: the match row is locked {@code FOR UPDATE}, serialising placement against
 *       other placements on the same match and against status changes / settlement.</li>
 *   <li><b>Idempotency</b>: an optional {@code Idempotency-Key} makes retries safe; the stored request
 *       fingerprint detects a key being re-used for a different request.</li>
 *   <li><b>Constraints</b>: the unique (user, match) key and the idempotency primary key are the final
 *       safety net if the application-level checks are ever bypassed.</li>
 * </ol>
 */
@Service
public class BetService {

    private static final Logger log = LoggerFactory.getLogger(BetService.class);

    private final MatchRepository matches;
    private final BetRepository bets;
    private final IdempotencyRepository idempotency;
    private final Clock clock;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    public BetService(MatchRepository matches, BetRepository bets, IdempotencyRepository idempotency,
                      Clock clock, PlatformTransactionManager txManager) {
        this.matches = matches;
        this.bets = bets;
        this.idempotency = idempotency;
        this.clock = clock;
        this.writeTx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    // ------------------------------------------------------------------ place

    public PlacedBet place(ApiPrincipal principal, PlaceBetCommand cmd) {
        if (principal.role() != Role.USER || !cmd.userId().equals(principal.userId())) {
            throw ApiException.forbidden("USER_MISMATCH", "You can only place bets for your own user");
        }
        try {
            return writeTx.execute(status -> doPlace(cmd));
        } catch (DataIntegrityViolationException e) {
            if (cmd.idempotencyKey() == null) {
                throw e;
            }
            // The same idempotency key raced on another match's lock and lost on the primary key.
            // The winner has committed by now, so a second attempt takes the replay path.
            log.debug("Idempotency key race for user {}, retrying", cmd.userId());
            return writeTx.execute(status -> doPlace(cmd));
        }
    }

    private PlacedBet doPlace(PlaceBetCommand cmd) {
        Match match = matches.findByIdForUpdate(cmd.matchId())
                .orElseThrow(() -> ApiException.notFound("MATCH_NOT_FOUND", "Match " + cmd.matchId() + " not found"));
        String fingerprint = fingerprint(cmd);

        if (cmd.idempotencyKey() != null) {
            Optional<IdempotencyRecord> previous =
                    idempotency.findById(new IdempotencyId(cmd.userId(), cmd.idempotencyKey()));
            if (previous.isPresent()) {
                return replay(previous.get(), fingerprint);
            }
        }

        if (!match.hasTeam(cmd.selectedTeamId())) {
            throw ApiException.unprocessable("INVALID_TEAM",
                    "Team " + cmd.selectedTeamId() + " does not play in match " + cmd.matchId());
        }
        Instant now = now();
        if (match.getStatus() != MatchStatus.SCHEDULED || !now.isBefore(match.getStartTime())) {
            throw ApiException.conflict("BETTING_CLOSED", "Betting is closed for match " + cmd.matchId());
        }
        if (bets.existsByUserIdAndMatchId(cmd.userId(), cmd.matchId())) {
            throw ApiException.conflict("BET_ALREADY_PLACED",
                    "User " + cmd.userId() + " already has a bet on match " + cmd.matchId());
        }

        Bet bet = bets.saveAndFlush(new Bet(newBetId(), cmd.userId(), cmd.matchId(), cmd.selectedTeamId(), now));
        if (cmd.idempotencyKey() != null) {
            idempotency.saveAndFlush(new IdempotencyRecord(
                    new IdempotencyId(cmd.userId(), cmd.idempotencyKey()), fingerprint, bet.getBetId(), now));
        }
        log.info("Bet {} placed by {} on match {} for team {}", bet.getBetId(), cmd.userId(), cmd.matchId(), cmd.selectedTeamId());
        return new PlacedBet(bet, false);
    }

    private PlacedBet replay(IdempotencyRecord record, String fingerprint) {
        if (!record.getRequestHash().equals(fingerprint)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used with a different request");
        }
        Bet bet = bets.findById(record.getBetId())
                .orElseThrow(() -> new IllegalStateException("Idempotency record points at missing bet " + record.getBetId()));
        return new PlacedBet(bet, true);
    }

    // ------------------------------------------------------------------ queries

    public Bet get(ApiPrincipal principal, String betId) {
        return readTx.execute(status -> {
            Bet bet = bets.findById(betId).orElseThrow(() -> betNotFound(betId));
            if (!principal.isAdmin() && !bet.getUserId().equals(principal.userId())) {
                throw betNotFound(betId); // do not reveal that somebody else's bet exists
            }
            return bet;
        });
    }

    public PageResult<Bet> listForUser(ApiPrincipal principal, String userId, BetStatus status, int page, int size) {
        if (!principal.isAdmin() && !userId.equals(principal.userId())) {
            throw ApiException.forbidden("FORBIDDEN", "You can only view your own bets");
        }
        return readTx.execute(tx -> {
            Pageable pageable = pageable(page, size);
            var result = status == null
                    ? bets.findByUserId(userId, pageable)
                    : bets.findByUserIdAndStatus(userId, status, pageable);
            return new PageResult<>(result.getContent(), result.getTotalElements());
        });
    }

    public PageResult<Bet> listForMatch(String matchId, BetStatus status, int page, int size) {
        return readTx.execute(tx -> {
            if (!matches.existsById(matchId)) {
                throw ApiException.notFound("MATCH_NOT_FOUND", "Match " + matchId + " not found");
            }
            Pageable pageable = pageable(page, size);
            var result = status == null
                    ? bets.findByMatchId(matchId, pageable)
                    : bets.findByMatchIdAndStatus(matchId, status, pageable);
            return new PageResult<>(result.getContent(), result.getTotalElements());
        });
    }

    // ------------------------------------------------------------------ helpers

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "placedAt").and(Sort.by("betId")));
    }

    private static ApiException betNotFound(String betId) {
        return ApiException.notFound("BET_NOT_FOUND", "Bet " + betId + " not found");
    }

    private static String newBetId() {
        return "B-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String fingerprint(PlaceBetCommand cmd) {
        try {
            String canonical = cmd.userId() + '\n' + cmd.matchId() + '\n' + cmd.selectedTeamId();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }
}
