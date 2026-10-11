# Bally Premium League – Match Betting Service

Backend for the BPL betting app: operators publish matches and live scores, users place a bet on the
winner of a match, and bets are settled automatically when the match completes.

**Stack:** Java 17 · Spring Boot 3.5 · Spring Security (API keys) · Spring Data JPA / Hibernate ·
H2 in-memory database · Flyway · JUnit 5 + MockMvc.

---

## How to run

Prerequisites: **JDK 17 or newer** and **Maven 3.9+** (no other infrastructure; the database is in-memory).

```bash
# build + run all tests (unit, API integration and concurrency tests)
mvn clean verify

# run the service on http://localhost:8080
mvn spring-boot:run
# or
java -jar target/bpl-betting-service-1.0.0.jar
```

Health check (unauthenticated): `GET /actuator/health`.

### H2 console (dev only)

The in-memory database can be inspected in a browser. It is off by default (and blocked by the security
rules); enable it with the `dev` profile:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Then open http://localhost:8080/h2-console and log in with driver `org.h2.Driver`, JDBC URL `jdbc:h2:mem:bpl`,
user `sa`, empty password. In IntelliJ set *Active profiles* to `dev` in the run configuration.

**IDE note (IntelliJ):** if you see `records are not supported in -source 15`, the IDE is using a language level
below 17. Set *File > Project Structure > Project SDK* to a JDK 17+ and *Language level* to 17, then
*Maven > Reload project*. Spring Boot 3.x requires Java 17 as a minimum, so JDK 15 or older will not work.

### Authentication

Every endpoint except `/actuator/health` requires an `X-API-Key` header. Development keys are defined in
`src/main/resources/application.yml`:

| Caller      | Role    | Key                            | Bound user |
|-------------|---------|--------------------------------|------------|
| ops / feed  | `ADMIN` | `dev-admin-key-change-me`      | –          |
| app user 1  | `USER`  | `dev-user-u1001-key-change-me` | `U1001`    |
| app user 2  | `USER`  | `dev-user-u1002-key-change-me` | `U1002`    |

In a real deployment override them with environment variables, e.g.
`BPL_SECURITY_APIKEYS_0_KEY=<secret>` (see *Security* below).

### Quick walkthrough

```bash
ADMIN=dev-admin-key-change-me; U1=dev-user-u1001-key-change-me; U2=dev-user-u1002-key-change-me
H='Content-Type: application/json'

# 1. operator creates a match (start time must be in the future for betting to be open)
curl -X PUT localhost:8080/matches/BPL-2026-M01 -H "X-API-Key: $ADMIN" -H "$H" -d '{
  "name": "Mumbai Mavericks vs Chennai Chargers",
  "teams": [{"teamId":"MUM","name":"Mumbai Mavericks"},{"teamId":"CHE","name":"Chennai Chargers"}],
  "startTime": "2030-11-02T14:00:00Z", "status": "SCHEDULED" }'

# 2. users bet (Idempotency-Key makes retries safe)
curl -X POST localhost:8080/bets -H "X-API-Key: $U1" -H "$H" -H 'Idempotency-Key: 6f1c2d0e-0001' \
  -d '{"userId":"U1001","matchId":"BPL-2026-M01","selectedTeamId":"MUM"}'
curl -X POST localhost:8080/bets -H "X-API-Key: $U2" -H "$H" \
  -d '{"userId":"U1002","matchId":"BPL-2026-M01","selectedTeamId":"CHE"}'

# 3. match goes live, scores come in
curl -X PUT localhost:8080/matches/BPL-2026-M01 -H "X-API-Key: $ADMIN" -H "$H" -d '{
  "name":"Mumbai Mavericks vs Chennai Chargers",
  "teams":[{"teamId":"MUM","name":"Mumbai Mavericks"},{"teamId":"CHE","name":"Chennai Chargers"}],
  "startTime":"2030-11-02T14:00:00Z","status":"LIVE"}'
curl -X PUT localhost:8080/matches/BPL-2026-M01/score -H "X-API-Key: $ADMIN" -H "$H" \
  -d '{"teamId":"MUM","runs":182,"wickets":5,"overs":20.0}'
curl -X PUT localhost:8080/matches/BPL-2026-M01/score -H "X-API-Key: $ADMIN" -H "$H" \
  -d '{"teamId":"CHE","runs":175,"wickets":8,"overs":20.0}'

# 4. match completes -> bets are settled in the same transaction
curl -X PUT localhost:8080/matches/BPL-2026-M01 -H "X-API-Key: $ADMIN" -H "$H" -d '{
  "name":"Mumbai Mavericks vs Chennai Chargers",
  "teams":[{"teamId":"MUM","name":"Mumbai Mavericks"},{"teamId":"CHE","name":"Chennai Chargers"}],
  "startTime":"2030-11-02T14:00:00Z","status":"COMPLETED"}'

# 5. users check results
curl localhost:8080/users/U1001/bets -H "X-API-Key: $U1"
curl 'localhost:8080/users/U1002/bets?status=SETTLED' -H "X-API-Key: $U2"
```

---

## API summary

| # | Endpoint | Who | Success | Notes |
|---|----------|-----|---------|-------|
| 1 | `PUT /matches/{matchId}` | ADMIN | `201` created / `200` updated | Idempotent upsert. Returns the match. |
| 2 | `PUT /matches/{matchId}/score` | ADMIN | `200` | Match must be `LIVE`. Returns the match with scores. |
| 3 | `GET /matches?status=` | any key | `200` | Optional `page`, `size`; `X-Total-Count` header. |
| 4 | `GET /matches/{matchId}` | any key | `200` | |
| 5 | `POST /bets` | USER (self) | `201`, or `200` on idempotent replay | Optional `Idempotency-Key` header. |
| 6 | `GET /bets/{betId}` | owner or ADMIN | `200` | Someone else's bet is a `404`. |
| 7 | `GET /users/{userId}/bets?status=` | that user or ADMIN | `200` | `status` = `OPEN` \| `SETTLED`. |
| 8 | `GET /matches/{matchId}/bets?status=` | ADMIN | `200` | |

Extra fields beyond the brief (all additive): `winnerTeamId` on a match (request and response);
`Idempotency-Key` / `Idempotent-Replayed` headers; `page`/`size` and `X-Total-Count` on list endpoints;
`result: "VOID"` as a third bet result.

Errors are RFC 7807 `application/problem+json` with a stable machine-readable `code`:

```json
{ "type": "about:blank", "title": "Conflict", "status": 409, "detail": "Betting is closed for match BPL-2026-M01",
  "code": "BETTING_CLOSED", "path": "/bets" }
```

| Status | When |
|--------|------|
| `400` | Bean validation / malformed JSON / bad enum / bad path or query value (`code=VALIDATION_FAILED`, with an `errors` list) |
| `401` | Missing or unknown API key |
| `403` | Wrong role, or acting for another user |
| `404` | Unknown match / bet (also another user's bet) |
| `409` | State conflict: betting closed, duplicate bet, match not live, completed match modified, backwards status change, … |
| `422` | Well-formed but semantically invalid: team not in match, invalid overs (`17.7`), idempotency key reused for a different request, … |
| `503` + `Retry-After` | Row lock could not be acquired within the lock timeout |

---

## Design

### Layers
`web` (controllers, DTOs, error mapping) → `service` (business rules, transactions) → `repository`
(Spring Data) → `domain` (JPA entities). The schema is owned by Flyway (`db/migration/V1__init.sql`),
including check constraints, foreign keys and unique constraints, so integrity does not depend on the
application alone.

### Concurrency: row locks at the database
Every operation whose correctness depends on the state of a match first locks that match row with
`SELECT … FOR UPDATE` (`MatchRepository#findByIdForUpdate`, JPA `PESSIMISTIC_WRITE`):

* **Place bet** – locks the match, then checks status/start time, then inserts. A bet therefore cannot
  slip in while the match is being completed.
* **Complete match / settle** – locks the match, flips the status, and settles all `OPEN` bets with bulk
  `UPDATE`s in the *same transaction*. A bet either commits before settlement (and is settled) or is
  rejected with `BETTING_CLOSED`; a bet can never be left `OPEN` on a `COMPLETED` match.
* **Score update / match update** – serialised by the same lock.

Lock order is always *match row → bet / idempotency rows*, so there are no lock-order cycles. Waiting is
bounded by H2's `LOCK_TIMEOUT` (5 s); beyond that the API answers `503` + `Retry-After`.
Locking is per match, so different matches never contend. `Match` also carries a `@Version` column as a
second line of defence.

### Idempotency
* `PUT /matches/{id}` and `PUT …/score` are idempotent by nature (full-state upserts). Re-sending the
  final `COMPLETED` payload returns `200` without re-settling.
* `POST /bets` supports an optional `Idempotency-Key` header (8–128 chars). The key is scoped per user and
  stored with a SHA-256 fingerprint of the request:
  * same key + same request → original bet, `200` + `Idempotent-Replayed: true`
    (even if betting has closed meanwhile),
  * same key + different request → `422 IDEMPOTENCY_KEY_REUSED`.
* Independently of the header, the unique constraint `(user_id, match_id)` guarantees a user can never hold
  two bets on one match, however requests are retried or raced.
* The replay check runs *after* the match lock is taken, so concurrent retries serialise and all but one
  take the replay path. The cross-match race (same key used for two matches) is caught by the key's
  primary key and retried once.
* Settlement is idempotent too: each bulk update only touches `OPEN` bets.

### Security
* **Authentication:** stateless `X-API-Key`. Keys come from configuration, are hashed (SHA-256) at startup
  and looked up by hash; plaintext is never logged. Startup fails if no keys are configured, a key is
  shorter than 16 characters, keys are duplicated, or a `USER` key has no `user-id`.
* **Authorisation:** `ADMIN` manages matches/scores and can read everything; `USER` keys are *bound to a
  user id*, so a user can only bet as themselves and read their own bets. The `userId` in the body of
  `POST /bets` must equal the key's user. Reading someone else's bet returns `404` (no existence leak);
  listing someone else's bets returns `403`. Everything not explicitly allowed is denied.
* CSRF is disabled deliberately: the API is stateless and header-authenticated (no cookies).
* Responses never contain stack traces, SQL or constraint names; those are logged with a request id
  (`X-Request-Id`, also in every log line).

### Domain rules
* Match lifecycle only moves forward: `SCHEDULED → LIVE → COMPLETED`.
* The two team ids of a match are fixed once created (names/start time may change until completion).
* A completed match is immutable (an identical re-PUT is accepted as a replay).
* Scores are stored as the latest value per team; `wickets` 0–10, `runs` ≥ 0, `overs` 0–20.0 in
  cricket notation (`17.3` = 17 overs 3 balls; `17.7` is rejected).

---

## Assumptions

1. **One bet per user per match**, on exactly one of the two teams, and bets cannot be changed or
   cancelled. (Enforced by a database unique constraint.)
2. **Betting window:** a bet is accepted only while the match is `SCHEDULED` *and* the current time is
   before `startTime`. It closes at the start time (or as soon as the operator sets the match `LIVE`).
3. **Who decides the winner:** when a match is set to `COMPLETED`, the winner is the team with more runs in
   the stored scores. An operator can override this with `winnerTeamId` (e.g. rain-affected result).
4. **Tie / no result / missing scores:** no winner is recorded and every open bet is settled as `VOID`
   (neither won nor lost). This adds a third `result` value to the brief's `WON`/`LOST`.
5. **Settlement is triggered by the status change** to `COMPLETED`, atomically with it, and is final.
6. **No money:** the brief has no stake, odds or wallet, so none are modelled. `WON`/`LOST` is purely the
   outcome of the prediction.
7. **Users are external:** there is no user table or signup. A user exists as an API key bound to a
   `userId`. `GET /users/{userId}/bets` for a user with no bets returns an empty list.
8. **Two teams per match**, ids unique within a match (a team id may be reused across matches).
9. **Who calls what:** matches and scores are written by an operator/feed integration (`ADMIN`); bets are
   written by end users (`USER`); `GET /matches/{id}/bets` is an operational view and therefore admin-only.
10. **`PUT` score** is a full overwrite of that team's latest score; no history is kept, and scores are not
    required to be monotonic (so operators can correct mistakes).
11. **Score response:** `PUT …/score` returns the whole match (with all current scores) rather than the
    single score.
12. **Time:** all timestamps are UTC ISO-8601 with millisecond precision.
13. **Lists** are ordered (matches by start time; bets newest first), paginated with `page` (0-based) and
    `size` (default 100, max 500). The response body stays a plain JSON array as in the brief; the total is
    in `X-Total-Count`.

## Constraints and limitations

* **Single instance, in-memory H2.** All data is lost on restart and nothing is shared between instances.
  Row locks and the idempotency table work as designed on one JVM; on a real multi-instance deployment the
  same code works unchanged against PostgreSQL/MySQL (`SELECT … FOR UPDATE` and the constraints are
  standard), only the datasource and Flyway dialect need changing.
* **API keys are static configuration.** There is no key rotation without a restart, expiry, per-key rate
  limiting, or user self-service. For production I would front this with OAuth2/OIDC (JWT) and an API gateway
  doing TLS, rate limiting and WAF; this service intentionally does not terminate TLS.
* **No money handling** (stakes, odds, payouts, wallet, limits, KYC / responsible-gambling controls,
  regulatory audit trail). These are essential for a real wagering product but outside the brief.
* **Per-match write serialisation:** all placements on one match queue behind one row lock. That is
  correct and simple, and fine at this scale, but it is a throughput ceiling for a single very hot match
  (a real system would shard or queue bets per match).
* **Idempotency records are never purged.** A production system would expire them (e.g. after 24 h).
* **Settlement is a single bulk statement**, which is the right trade-off here; for millions of bets on
  one match it would need batching/async settlement with its own progress tracking.
* **No score feed integration, no push/streaming:** "live scores" means clients poll `GET /matches`.
* **No result corrections:** once a match is `COMPLETED` it cannot be reopened, so a wrongly-declared
  winner cannot be fixed through the API (it would need a reviewed, audited back-office procedure).
* **No OpenAPI/Swagger UI, metrics or tracing** beyond health, request ids and logs.
* **Hibernate schema validation is off** (`ddl-auto: none`); Flyway owns the schema and the integration
  tests exercise every mapping. Switch to `validate` once running against a real database.

## Project layout

```
src/main/java/com/bally/bpl
  BplApplication.java
  config/       Clock bean, pagination properties
  domain/       JPA entities and enums (Match, MatchScore, Bet, IdempotencyRecord, …)
  repository/   Spring Data repositories (incl. FOR UPDATE query and bulk settlement)
  service/      MatchService (lifecycle, scores, settlement), BetService (placement, idempotency, queries)
  security/     API-key registry, filter, security chain, principal
  web/          Controllers, DTOs, exception handling, request-id filter
src/main/resources
  application.yml, db/migration/V1__init.sql
src/test/java/com/bally/bpl
  MatchApiTest, BetApiTest      end-to-end API behaviour through MockMvc + Spring Security
  ConcurrencyTest               parallel retries / duplicate bets / bet-vs-completion races
  ApiKeyRegistryTest            configuration safety checks
```
