package com.bally.bpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Shared fixtures. The in-memory database lives for the whole test run, so every test creates matches with
 * unique ids instead of relying on a clean database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
abstract class ApiTestSupport {

    static final String ADMIN_KEY = "dev-admin-key-change-me";
    static final String U1_KEY = "dev-user-u1001-key-change-me";
    static final String U2_KEY = "dev-user-u1002-key-change-me";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(TestClockConfig.BASE);
    }

    protected static String newMatchId() {
        return "M-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected Instant inHours(long hours) {
        return clock.instant().plus(Duration.ofHours(hours));
    }

    protected static Map<String, Object> matchBody(String status, Instant start) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Mumbai Mavericks vs Chennai Chargers");
        body.put("teams", List.of(
                Map.of("teamId", "MUM", "name", "Mumbai Mavericks"),
                Map.of("teamId", "CHE", "name", "Chennai Chargers")));
        body.put("startTime", start.toString());
        body.put("status", status);
        return body;
    }

    protected ResultActions putMatch(String matchId, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/matches/{id}", matchId)
                .header("X-API-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    /** Creates a match through the API and returns its id. */
    protected String createMatch(String status, Instant start) throws Exception {
        String id = newMatchId();
        putMatch(id, matchBody(status, start)).andReturn();
        return id;
    }

    protected ResultActions putScore(String matchId, String teamId, int runs, int wickets, double overs) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamId", teamId);
        body.put("runs", runs);
        body.put("wickets", wickets);
        body.put("overs", overs);
        return mvc.perform(put("/matches/{id}/score", matchId)
                .header("X-API-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions placeBet(String apiKey, String userId, String matchId, String teamId, String idempotencyKey)
            throws Exception {
        var request = post("/bets")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", userId, "matchId", matchId, "selectedTeamId", teamId)));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    protected ResultActions getBet(String apiKey, String betId) throws Exception {
        return mvc.perform(get("/bets/{id}", betId).header("X-API-Key", apiKey));
    }

    protected ResultActions matchBets(String matchId, String query) throws Exception {
        return mvc.perform(get("/matches/{id}/bets" + query, matchId).header("X-API-Key", ADMIN_KEY));
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    protected String betIdOf(MvcResult result) throws Exception {
        return body(result).get("betId").asText();
    }
}
