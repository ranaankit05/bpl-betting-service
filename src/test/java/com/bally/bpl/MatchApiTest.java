package com.bally.bpl;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MatchApiTest extends ApiTestSupport {

    // ------------------------------------------------------------ authentication / authorisation

    @Test
    void requestWithoutApiKeyIsRejectedWith401() throws Exception {
        mvc.perform(get("/matches"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("ApiKey")))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void requestWithUnknownApiKeyIsRejectedWith401() throws Exception {
        mvc.perform(get("/matches").header("X-API-Key", "not-a-real-key-at-all"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void userCannotCreateOrUpdateMatches() throws Exception {
        mvc.perform(put("/matches/{id}", newMatchId())
                        .header("X-API-Key", U1_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(matchBody("SCHEDULED", inHours(2)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void userCanReadMatches() throws Exception {
        String id = createMatch("SCHEDULED", inHours(2));
        mvc.perform(get("/matches/{id}", id).header("X-API-Key", U1_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchId").value(id));
    }

    // ------------------------------------------------------------ match upsert

    @Test
    void putCreatesThenUpdatesMatch() throws Exception {
        String id = newMatchId();
        putMatch(id, matchBody("SCHEDULED", inHours(2)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/matches/" + id))
                .andExpect(jsonPath("$.matchId").value(id))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.teams", hasSize(2)))
                .andExpect(jsonPath("$.scores", hasSize(0)));

        Map<String, Object> changed = matchBody("SCHEDULED", inHours(3));
        changed.put("name", "Renamed fixture");
        putMatch(id, changed)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed fixture"));
    }

    @Test
    void identicalPutIsIdempotent() throws Exception {
        String id = newMatchId();
        var body = matchBody("SCHEDULED", inHours(2));
        putMatch(id, body).andExpect(status().isCreated());
        putMatch(id, body).andExpect(status().isOk());
        putMatch(id, body).andExpect(status().isOk());
    }

    @Test
    void matchWithOneTeamIsRejected() throws Exception {
        var body = matchBody("SCHEDULED", inHours(2));
        body.put("teams", java.util.List.of(Map.of("teamId", "MUM", "name", "Mumbai Mavericks")));
        putMatch(newMatchId(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void matchWithTwoIdenticalTeamsIsRejected() throws Exception {
        var body = matchBody("SCHEDULED", inHours(2));
        body.put("teams", java.util.List.of(
                Map.of("teamId", "MUM", "name", "Mumbai Mavericks"),
                Map.of("teamId", "MUM", "name", "Mumbai Again")));
        putMatch(newMatchId(), body)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_TEAM"));
    }

    @Test
    void unknownStatusValueIsRejected() throws Exception {
        var body = matchBody("SCHEDULED", inHours(2));
        body.put("status", "POSTPONED");
        putMatch(newMatchId(), body).andExpect(status().isBadRequest());
    }

    @Test
    void invalidMatchIdInPathIsRejected() throws Exception {
        putMatch("bad id with spaces", matchBody("SCHEDULED", inHours(2)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statusCannotMoveBackwards() throws Exception {
        String id = createMatch("LIVE", inHours(0));
        putMatch(id, matchBody("SCHEDULED", inHours(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void teamsCannotBeReplacedOnExistingMatch() throws Exception {
        String id = createMatch("SCHEDULED", inHours(2));
        var body = matchBody("SCHEDULED", inHours(2));
        body.put("teams", java.util.List.of(
                Map.of("teamId", "MUM", "name", "Mumbai Mavericks"),
                Map.of("teamId", "DEL", "name", "Delhi Dynamos")));
        putMatch(id, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEAMS_IMMUTABLE"));
    }

    @Test
    void completedMatchCannotBeModifiedButIdenticalReplayIsAccepted() throws Exception {
        String id = newMatchId();
        var start = inHours(2);
        var completed = matchBody("COMPLETED", start);
        putMatch(id, completed).andExpect(status().isCreated());
        putMatch(id, completed).andExpect(status().isOk());      // replay

        var tampered = matchBody("COMPLETED", start);
        tampered.put("name", "Something else");
        putMatch(id, tampered)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATCH_COMPLETED"));
    }

    @Test
    void winnerOnlyAllowedWhenCompletedAndMustBeAParticipant() throws Exception {
        var live = matchBody("LIVE", inHours(0));
        live.put("winnerTeamId", "MUM");
        putMatch(newMatchId(), live)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WINNER_NOT_ALLOWED"));

        var stranger = matchBody("COMPLETED", inHours(0));
        stranger.put("winnerTeamId", "XYZ");
        putMatch(newMatchId(), stranger)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_WINNER"));
    }

    // ------------------------------------------------------------ queries

    @Test
    void getUnknownMatchReturns404() throws Exception {
        mvc.perform(get("/matches/{id}", "NOPE-1").header("X-API-Key", U1_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MATCH_NOT_FOUND"));
    }

    @Test
    void listFiltersByStatus() throws Exception {
        String scheduled = createMatch("SCHEDULED", inHours(5));
        String live = createMatch("LIVE", inHours(0));

        mvc.perform(get("/matches").param("status", "LIVE").param("size", "500").header("X-API-Key", U1_KEY))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Total-Count"))
                .andExpect(jsonPath("$[?(@.matchId=='%s')]", live).exists())
                .andExpect(jsonPath("$[?(@.matchId=='%s')]", scheduled).isEmpty());

        mvc.perform(get("/matches").param("size", "500").header("X-API-Key", U1_KEY))
                .andExpect(jsonPath("$[?(@.matchId=='%s')]", live).exists())
                .andExpect(jsonPath("$[?(@.matchId=='%s')]", scheduled).exists());
    }

    @Test
    void invalidStatusFilterIsRejected() throws Exception {
        mvc.perform(get("/matches").param("status", "FINISHED").header("X-API-Key", U1_KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidPageSizeIsRejected() throws Exception {
        mvc.perform(get("/matches").param("size", "0").header("X-API-Key", U1_KEY))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ live scores

    @Test
    void scoreCanBeUpdatedWhileLiveAndIsReturnedWithTheMatch() throws Exception {
        String id = createMatch("LIVE", inHours(0));
        putScore(id, "MUM", 152, 4, 17.3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scores", hasSize(1)))
                .andExpect(jsonPath("$.scores[0].teamId").value("MUM"))
                .andExpect(jsonPath("$.scores[0].runs").value(152))
                .andExpect(jsonPath("$.scores[0].wickets").value(4))
                .andExpect(jsonPath("$.scores[0].overs").value(17.3));

        // a later update overwrites the previous one instead of adding a second row
        putScore(id, "MUM", 160, 5, 18.2).andExpect(status().isOk()).andExpect(jsonPath("$.scores", hasSize(1)));
        putScore(id, "CHE", 10, 0, 1.0).andExpect(jsonPath("$.scores", hasSize(2)));

        mvc.perform(get("/matches/{id}", id).header("X-API-Key", U1_KEY))
                .andExpect(jsonPath("$.scores", hasSize(2)));
    }

    @Test
    void scoreRequiresLiveMatch() throws Exception {
        String id = createMatch("SCHEDULED", inHours(2));
        putScore(id, "MUM", 1, 0, 0.1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATCH_NOT_LIVE"));
    }

    @Test
    void scoreForUnknownMatchReturns404() throws Exception {
        putScore("NOPE-1", "MUM", 1, 0, 0.1).andExpect(status().isNotFound());
    }

    @Test
    void scoreForTeamOutsideTheMatchIsRejected() throws Exception {
        String id = createMatch("LIVE", inHours(0));
        putScore(id, "DEL", 1, 0, 0.1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TEAM"));
    }

    @Test
    void invalidScoreValuesAreRejected() throws Exception {
        String id = createMatch("LIVE", inHours(0));
        putScore(id, "MUM", -1, 0, 1.0).andExpect(status().isBadRequest());     // negative runs
        putScore(id, "MUM", 10, 11, 1.0).andExpect(status().isBadRequest());    // 11 wickets
        putScore(id, "MUM", 10, 1, 20.5).andExpect(status().isBadRequest());    // beyond 20 overs
        putScore(id, "MUM", 10, 1, 17.25).andExpect(status().isBadRequest());   // two decimals
        putScore(id, "MUM", 10, 1, 17.7)                                        // 7th ball of an over
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_OVERS"));
    }

    @Test
    void scoreCannotBeUpdatedAfterCompletion() throws Exception {
        String id = createMatch("LIVE", inHours(0));
        putScore(id, "MUM", 100, 3, 10.0).andExpect(status().isOk());
        putMatch(id, matchBody("COMPLETED", inHours(0))).andExpect(status().isOk());
        putScore(id, "MUM", 120, 4, 12.0).andExpect(status().isConflict());
    }
}
