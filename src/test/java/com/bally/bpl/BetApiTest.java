package com.bally.bpl;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BetApiTest extends ApiTestSupport {

    // ------------------------------------------------------------ placing

    @Test
    void userPlacesAnOpenBet() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));

        MvcResult placed = placeBet(U1_KEY, "U1001", match, "MUM", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.betId", startsWith("B-")))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.placedAt").value(TestClockConfig.BASE.toString()))
                .andReturn();

        getBet(U1_KEY, betIdOf(placed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("U1001"))
                .andExpect(jsonPath("$.matchId").value(match))
                .andExpect(jsonPath("$.selectedTeamId").value("MUM"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.settledAt").doesNotExist());
    }

    @Test
    void anonymousUserAndAdminCannotPlaceBets() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));
        placeBet("garbage-key-garbage-key", "U1001", match, "MUM", null).andExpect(status().isUnauthorized());
        placeBet(ADMIN_KEY, "U1001", match, "MUM", null).andExpect(status().isForbidden());
    }

    @Test
    void userCannotBetOnBehalfOfSomeoneElse() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));
        placeBet(U1_KEY, "U1002", match, "MUM", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_MISMATCH"));
    }

    @Test
    void betOnUnknownMatchReturns404() throws Exception {
        placeBet(U1_KEY, "U1001", "NOPE-1", "MUM", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MATCH_NOT_FOUND"));
    }

    @Test
    void betOnTeamOutsideTheMatchIsRejected() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));
        placeBet(U1_KEY, "U1001", match, "DEL", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TEAM"));
    }

    @Test
    void malformedBetRequestIsRejected() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/bets")
                        .header("X-API-Key", U1_KEY)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("userId", "U1001"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void bettingClosesWhenTheMatchStarts() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));
        clock.set(TestClockConfig.BASE.plus(Duration.ofHours(2)));          // exactly at the start time
        placeBet(U1_KEY, "U1001", match, "MUM", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BETTING_CLOSED"));
    }

    @Test
    void bettingIsClosedOnceTheMatchIsLiveEvenIfStartTimeIsInTheFuture() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));
        putMatch(match, matchBody("LIVE", inHours(2))).andExpect(status().isOk());
        placeBet(U1_KEY, "U1001", match, "MUM", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BETTING_CLOSED"));
    }

    @Test
    void secondBetByTheSameUserOnTheSameMatchIsRejected() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));
        placeBet(U1_KEY, "U1001", match, "MUM", null).andExpect(status().isCreated());
        placeBet(U1_KEY, "U1001", match, "CHE", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BET_ALREADY_PLACED"));
    }

    // ------------------------------------------------------------ idempotency

    @Test
    void retryWithSameIdempotencyKeyReturnsTheOriginalBet() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));

        MvcResult first = placeBet(U1_KEY, "U1001", match, "MUM", "retry-key-0001")
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = placeBet(U1_KEY, "U1001", match, "MUM", "retry-key-0001")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(betIdOf(second)).isEqualTo(betIdOf(first));
        matchBets(match, "").andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void replayStillWorksAfterBettingClosed() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));
        MvcResult first = placeBet(U1_KEY, "U1001", match, "MUM", "retry-key-0002").andReturn();

        putMatch(match, matchBody("LIVE", inHours(2))).andExpect(status().isOk());

        MvcResult replay = placeBet(U1_KEY, "U1001", match, "MUM", "retry-key-0002")
                .andExpect(status().isOk())
                .andReturn();
        assertThat(betIdOf(replay)).isEqualTo(betIdOf(first));
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));
        placeBet(U1_KEY, "U1001", match, "MUM", "retry-key-0003").andExpect(status().isCreated());
        placeBet(U1_KEY, "U1001", match, "CHE", "retry-key-0003")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void idempotencyKeysAreScopedPerUser() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));
        MvcResult a = placeBet(U1_KEY, "U1001", match, "MUM", "shared-key-0004").andExpect(status().isCreated()).andReturn();
        MvcResult b = placeBet(U2_KEY, "U1002", match, "MUM", "shared-key-0004").andExpect(status().isCreated()).andReturn();
        assertThat(betIdOf(a)).isNotEqualTo(betIdOf(b));
    }

    @Test
    void malformedIdempotencyKeyIsRejected() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));
        placeBet(U1_KEY, "U1001", match, "MUM", "short").andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ settlement

    @Test
    void betsAreSettledWhenTheMatchCompletesAndWinnerIsDerivedFromScores() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));
        String winningBet = betIdOf(placeBet(U1_KEY, "U1001", match, "MUM", null).andReturn());
        String losingBet = betIdOf(placeBet(U2_KEY, "U1002", match, "CHE", null).andReturn());

        putMatch(match, matchBody("LIVE", inHours(2))).andExpect(status().isOk());
        putScore(match, "MUM", 182, 5, 20.0).andExpect(status().isOk());
        putScore(match, "CHE", 175, 8, 20.0).andExpect(status().isOk());

        matchBets(match, "?status=OPEN").andExpect(jsonPath("$", hasSize(2)));
        getBet(U1_KEY, winningBet).andExpect(jsonPath("$.status").value("OPEN"));

        putMatch(match, matchBody("COMPLETED", inHours(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winnerTeamId").value("MUM"));

        getBet(U1_KEY, winningBet)
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.result").value("WON"))
                .andExpect(jsonPath("$.settledAt").exists());
        getBet(U2_KEY, losingBet)
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.result").value("LOST"))
                .andExpect(jsonPath("$.settledAt").exists());

        matchBets(match, "?status=OPEN").andExpect(jsonPath("$", hasSize(0)));
        matchBets(match, "?status=SETTLED").andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void tiedMatchVoidsAllBets() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));
        String bet1 = betIdOf(placeBet(U1_KEY, "U1001", match, "MUM", null).andReturn());
        String bet2 = betIdOf(placeBet(U2_KEY, "U1002", match, "CHE", null).andReturn());

        putMatch(match, matchBody("LIVE", inHours(2))).andExpect(status().isOk());
        putScore(match, "MUM", 150, 6, 20.0);
        putScore(match, "CHE", 150, 7, 20.0);
        putMatch(match, matchBody("COMPLETED", inHours(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winnerTeamId").doesNotExist());

        getBet(U1_KEY, bet1).andExpect(jsonPath("$.status").value("SETTLED")).andExpect(jsonPath("$.result").value("VOID"));
        getBet(U2_KEY, bet2).andExpect(jsonPath("$.status").value("SETTLED")).andExpect(jsonPath("$.result").value("VOID"));
    }

    @Test
    void explicitWinnerOverridesScores() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));
        String bet1 = betIdOf(placeBet(U1_KEY, "U1001", match, "MUM", null).andReturn());
        String bet2 = betIdOf(placeBet(U2_KEY, "U1002", match, "CHE", null).andReturn());

        putMatch(match, matchBody("LIVE", inHours(2)));
        putScore(match, "MUM", 150, 6, 20.0);
        putScore(match, "CHE", 120, 7, 16.0);       // rain: CHE awarded the win on DLS
        var completed = matchBody("COMPLETED", inHours(2));
        completed.put("winnerTeamId", "CHE");
        putMatch(match, completed).andExpect(status().isOk()).andExpect(jsonPath("$.winnerTeamId").value("CHE"));

        getBet(U1_KEY, bet1).andExpect(jsonPath("$.result").value("LOST"));
        getBet(U2_KEY, bet2).andExpect(jsonPath("$.result").value("WON"));
    }

    @Test
    void repeatingTheCompletionDoesNotSettleTwice() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));
        String bet = betIdOf(placeBet(U1_KEY, "U1001", match, "MUM", null).andReturn());
        var completed = matchBody("COMPLETED", inHours(2));
        completed.put("winnerTeamId", "MUM");

        putMatch(match, completed).andExpect(status().isOk());
        String settledAt = body(getBet(U1_KEY, bet).andReturn()).get("settledAt").asText();

        clock.set(TestClockConfig.BASE.plus(Duration.ofHours(5)));
        putMatch(match, completed).andExpect(status().isOk());
        assertThat(body(getBet(U1_KEY, bet).andReturn()).get("settledAt").asText()).isEqualTo(settledAt);
    }

    // ------------------------------------------------------------ queries and visibility

    @Test
    void userSeesOpenAndSettledBetsFilteredByStatus() throws Exception {
        String settledMatch = createMatch("SCHEDULED", inHours(2));
        String openMatch = createMatch("SCHEDULED", inHours(3));
        String settledBet = betIdOf(placeBet(U2_KEY, "U1002", settledMatch, "MUM", null).andReturn());
        String openBet = betIdOf(placeBet(U2_KEY, "U1002", openMatch, "MUM", null).andReturn());
        var completed = matchBody("COMPLETED", inHours(2));
        completed.put("winnerTeamId", "MUM");
        putMatch(settledMatch, completed).andExpect(status().isOk());

        mvc.perform(get("/users/{id}/bets", "U1002").param("status", "OPEN").param("size", "500").header("X-API-Key", U2_KEY))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Total-Count"))
                .andExpect(jsonPath("$[?(@.betId=='%s')]", openBet).exists())
                .andExpect(jsonPath("$[?(@.betId=='%s')]", settledBet).isEmpty());
        mvc.perform(get("/users/{id}/bets", "U1002").param("status", "SETTLED").param("size", "500").header("X-API-Key", U2_KEY))
                .andExpect(jsonPath("$[?(@.betId=='%s')]", settledBet).exists())
                .andExpect(jsonPath("$[?(@.betId=='%s')]", openBet).isEmpty());
        mvc.perform(get("/users/{id}/bets", "U1002").param("size", "500").header("X-API-Key", U2_KEY))
                .andExpect(jsonPath("$[?(@.betId=='%s')]", settledBet).exists())
                .andExpect(jsonPath("$[?(@.betId=='%s')]", openBet).exists());
    }

    @Test
    void aUserCannotSeeAnotherUsersBets() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));
        String bet = betIdOf(placeBet(U1_KEY, "U1001", match, "MUM", null).andReturn());

        getBet(U2_KEY, bet).andExpect(status().isNotFound());                     // existence is not revealed
        mvc.perform(get("/users/{id}/bets", "U1001").header("X-API-Key", U2_KEY))
                .andExpect(status().isForbidden());
        getBet(ADMIN_KEY, bet).andExpect(status().isOk());                        // ops can see everything
        mvc.perform(get("/users/{id}/bets", "U1001").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void onlyAdminsCanListAllBetsOnAMatch() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));
        placeBet(U1_KEY, "U1001", match, "MUM", null);
        mvc.perform(get("/matches/{id}/bets", match).header("X-API-Key", U1_KEY)).andExpect(status().isForbidden());
        matchBets(match, "").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void listingBetsOfAnUnknownMatchReturns404() throws Exception {
        matchBets("NOPE-1", "").andExpect(status().isNotFound());
    }

    @Test
    void unknownBetReturns404() throws Exception {
        getBet(U1_KEY, "B-doesnotexist").andExpect(status().isNotFound());
    }

    @Test
    void invalidBetStatusFilterIsRejected() throws Exception {
        mvc.perform(get("/users/{id}/bets", "U1001").param("status", "WON").header("X-API-Key", U1_KEY))
                .andExpect(status().isBadRequest());
    }
}
