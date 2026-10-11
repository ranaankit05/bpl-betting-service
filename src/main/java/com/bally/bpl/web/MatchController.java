package com.bally.bpl.web;

import com.bally.bpl.config.PaginationProperties;
import com.bally.bpl.domain.MatchStatus;
import com.bally.bpl.service.MatchService;
import com.bally.bpl.service.PageResult;
import com.bally.bpl.service.UpsertMatchCommand;
import com.bally.bpl.service.UpsertResult;
import com.bally.bpl.web.dto.MatchResponse;
import com.bally.bpl.web.dto.MatchUpsertRequest;
import com.bally.bpl.web.dto.Patterns;
import com.bally.bpl.web.dto.ScoreUpdateRequest;
import com.bally.bpl.web.dto.TeamDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
public class MatchController {

    private final MatchService matchService;
    private final PaginationProperties pagination;

    public MatchController(MatchService matchService, PaginationProperties pagination) {
        this.matchService = matchService;
        this.pagination = pagination;
    }

    /** Idempotent create-or-update. 201 when the match was created, 200 when it already existed. */
    @PutMapping("/matches/{matchId}")
    public ResponseEntity<MatchResponse> upsertMatch(
            @PathVariable @Pattern(regexp = Patterns.MATCH_ID, message = "invalid match id") String matchId,
            @Valid @RequestBody MatchUpsertRequest request) {
        TeamDto t1 = request.teams().get(0);
        TeamDto t2 = request.teams().get(1);
        UpsertResult result = matchService.upsert(new UpsertMatchCommand(
                matchId, request.name().strip(), t1.teamId(), t1.name().strip(), t2.teamId(), t2.name().strip(),
                request.startTime(), request.status(), request.winnerTeamId()));
        MatchResponse body = MatchResponse.from(result.view());
        if (result.created()) {
            return ResponseEntity.created(URI.create("/matches/" + matchId)).body(body);
        }
        return ResponseEntity.ok(body);
    }

    @PutMapping("/matches/{matchId}/score")
    public MatchResponse updateScore(
            @PathVariable @Pattern(regexp = Patterns.MATCH_ID, message = "invalid match id") String matchId,
            @Valid @RequestBody ScoreUpdateRequest request) {
        return MatchResponse.from(matchService.updateScore(
                matchId, request.teamId(), request.runs(), request.wickets(), request.overs()));
    }

    @GetMapping("/matches")
    public ResponseEntity<List<MatchResponse>> listMatches(
            @RequestParam(required = false) MatchStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false) @Min(1) Integer size) {
        int effectiveSize = effectiveSize(size);
        PageResult<MatchResponse> result = matchService.list(status, page, effectiveSize).map(MatchResponse::from);
        return ResponseEntity.ok()
                .header(Headers.TOTAL_COUNT, String.valueOf(result.total()))
                .body(result.items());
    }

    @GetMapping("/matches/{matchId}")
    public MatchResponse getMatch(
            @PathVariable @Pattern(regexp = Patterns.MATCH_ID, message = "invalid match id") String matchId) {
        return MatchResponse.from(matchService.get(matchId));
    }

    private int effectiveSize(Integer requested) {
        int size = requested == null ? pagination.defaultSize() : requested;
        return Math.min(size, pagination.maxSize());
    }
}
