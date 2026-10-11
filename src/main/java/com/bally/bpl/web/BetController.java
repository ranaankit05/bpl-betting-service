package com.bally.bpl.web;

import com.bally.bpl.config.PaginationProperties;
import com.bally.bpl.domain.BetStatus;
import com.bally.bpl.security.ApiPrincipal;
import com.bally.bpl.service.BetService;
import com.bally.bpl.service.PageResult;
import com.bally.bpl.service.PlaceBetCommand;
import com.bally.bpl.service.PlacedBet;
import com.bally.bpl.web.dto.BetResponse;
import com.bally.bpl.web.dto.PlaceBetRequest;
import com.bally.bpl.web.dto.PlaceBetResponse;
import com.bally.bpl.web.dto.Patterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
public class BetController {

    private final BetService betService;
    private final PaginationProperties pagination;

    public BetController(BetService betService, PaginationProperties pagination) {
        this.betService = betService;
        this.pagination = pagination;
    }

    /**
     * Places a bet. Send an {@code Idempotency-Key} header to make retries safe: a repeat of the same
     * request returns the original bet with 200 and {@code Idempotent-Replayed: true} instead of 201.
     */
    @PostMapping("/bets")
    public ResponseEntity<PlaceBetResponse> placeBet(
            @AuthenticationPrincipal ApiPrincipal principal,
            @RequestHeader(name = Headers.IDEMPOTENCY_KEY, required = false)
            @Pattern(regexp = Patterns.IDEMPOTENCY_KEY, message = "must be 8-128 chars: letters, digits, '_', '-', '.', ':'")
            String idempotencyKey,
            @Valid @RequestBody PlaceBetRequest request) {
        PlacedBet placed = betService.place(principal, new PlaceBetCommand(
                request.userId(), request.matchId(), request.selectedTeamId(), idempotencyKey));
        PlaceBetResponse body = PlaceBetResponse.from(placed.bet());
        if (placed.replayed()) {
            return ResponseEntity.ok().header(Headers.IDEMPOTENT_REPLAYED, "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/bets/" + body.betId()))
                .body(body);
    }

    @GetMapping("/bets/{betId}")
    public BetResponse getBet(
            @AuthenticationPrincipal ApiPrincipal principal,
            @PathVariable @Pattern(regexp = "^B-[A-Za-z0-9]{1,30}$", message = "invalid bet id") String betId) {
        return BetResponse.from(betService.get(principal, betId));
    }

    @GetMapping("/users/{userId}/bets")
    public ResponseEntity<List<BetResponse>> userBets(
            @AuthenticationPrincipal ApiPrincipal principal,
            @PathVariable @Pattern(regexp = Patterns.USER_ID, message = "invalid user id") String userId,
            @RequestParam(required = false) BetStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false) @Min(1) Integer size) {
        PageResult<BetResponse> result = betService
                .listForUser(principal, userId, status, page, effectiveSize(size))
                .map(BetResponse::from);
        return page(result);
    }

    @GetMapping("/matches/{matchId}/bets")
    public ResponseEntity<List<BetResponse>> matchBets(
            @PathVariable @Pattern(regexp = Patterns.MATCH_ID, message = "invalid match id") String matchId,
            @RequestParam(required = false) BetStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false) @Min(1) Integer size) {
        PageResult<BetResponse> result = betService
                .listForMatch(matchId, status, page, effectiveSize(size))
                .map(BetResponse::from);
        return page(result);
    }

    private static ResponseEntity<List<BetResponse>> page(PageResult<BetResponse> result) {
        return ResponseEntity.ok()
                .header(Headers.TOTAL_COUNT, String.valueOf(result.total()))
                .body(result.items());
    }

    private int effectiveSize(Integer requested) {
        int size = requested == null ? pagination.defaultSize() : requested;
        return Math.min(size, pagination.maxSize());
    }
}
