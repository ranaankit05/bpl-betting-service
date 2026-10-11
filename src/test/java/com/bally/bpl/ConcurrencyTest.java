package com.bally.bpl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Proves the database-level guarantees under real contention: row locks serialise writers on a match,
 * idempotency keys collapse retries, and the unique constraint holds.
 */
class ConcurrencyTest extends ApiTestSupport {

    private static final int THREADS = 12;

    @Test
    void concurrentRetriesWithTheSameIdempotencyKeyCreateExactlyOneBet() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));

        List<MvcResult> results = runConcurrently(THREADS,
                () -> placeBet(U1_KEY, "U1001", match, "MUM", "concurrent-key-1").andReturn());

        int created = 0;
        int replayed = 0;
        Set<String> betIds = new HashSet<>();
        for (MvcResult r : results) {
            switch (r.getResponse().getStatus()) {
                case 201 -> created++;
                case 200 -> replayed++;
                default -> fail("Unexpected status " + r.getResponse().getStatus() + ": " + r.getResponse().getContentAsString());
            }
            betIds.add(betIdOf(r));
        }

        assertThat(created).isEqualTo(1);
        assertThat(replayed).isEqualTo(THREADS - 1);
        assertThat(betIds).hasSize(1);
        assertThat(body(matchBets(match, "").andReturn())).hasSize(1);
    }

    @Test
    void concurrentDuplicateBetsWithoutAKeyYieldExactlyOneBet() throws Exception {
        String match = createMatch("SCHEDULED", inHours(2));

        List<MvcResult> results = runConcurrently(THREADS,
                () -> placeBet(U2_KEY, "U1002", match, "CHE", null).andReturn());

        long created = results.stream().filter(r -> r.getResponse().getStatus() == 201).count();
        long conflicts = results.stream().filter(r -> r.getResponse().getStatus() == 409).count();
        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(THREADS - 1);
        assertThat(body(matchBets(match, "").andReturn())).hasSize(1);
    }

    /**
     * A bet racing with match completion must end up in exactly one of two states: rejected (match no
     * longer open) or placed and settled. It can never be left OPEN on a COMPLETED match.
     */
    @Test
    void betRacingWithCompletionIsNeverLeftOpen() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 15; i++) {
                String match = createMatch("SCHEDULED", inHours(2));
                var completed = matchBody("COMPLETED", inHours(2));
                completed.put("winnerTeamId", "MUM");

                CountDownLatch gate = new CountDownLatch(1);
                Future<MvcResult> bet = pool.submit(() -> {
                    gate.await();
                    return placeBet(U1_KEY, "U1001", match, "MUM", null).andReturn();
                });
                Future<MvcResult> completion = pool.submit(() -> {
                    gate.await();
                    return putMatch(match, completed).andReturn();
                });
                gate.countDown();

                int betStatus = bet.get(30, TimeUnit.SECONDS).getResponse().getStatus();
                int completionStatus = completion.get(30, TimeUnit.SECONDS).getResponse().getStatus();

                assertThat(completionStatus).as("completion, iteration %d", i).isEqualTo(200);
                JsonNode bets = body(matchBets(match, "").andReturn());
                assertThat(body(matchBets(match, "?status=OPEN").andReturn())).as("open bets, iteration %d", i).isEmpty();
                if (betStatus == 201) {
                    assertThat(bets).hasSize(1);
                    assertThat(bets.get(0).get("status").asText()).isEqualTo("SETTLED");
                    assertThat(bets.get(0).get("result").asText()).isEqualTo("WON");
                } else {
                    assertThat(betStatus).as("rejected bet, iteration %d", i).isEqualTo(409);
                    assertThat(bets).isEmpty();
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCreatesOfTheSameMatchConvergeOnOneMatch() throws Exception {
        String id = newMatchId();
        var body = matchBody("SCHEDULED", inHours(2));

        List<MvcResult> results = runConcurrently(8, () -> putMatch(id, body).andReturn());

        long created = results.stream().filter(r -> r.getResponse().getStatus() == 201).count();
        long updated = results.stream().filter(r -> r.getResponse().getStatus() == 200).count();
        assertThat(created).isEqualTo(1);
        assertThat(updated).isEqualTo(7);
    }

    // ------------------------------------------------------------ helper

    private <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.call();
                }));
            }
            gate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
