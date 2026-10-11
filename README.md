# Bally Premium League Betting Service


This is a Spring Boot service for managing cricket matches and match-winner bets. Operators create matches
and update scores, while users choose which team they think will win. When a match is completed, the
service settles its open bets automatically.


The project uses Java 17, Spring Boot 3.5, Spring Security, Spring Data JPA, Flyway, and an in-memory H2
database.


## Running the project


You will need JDK 17 or later and Maven 3.9+.


```bash
# Build the project and run all tests
mvn clean verify


# Start the application
mvn spring-boot:run
```


The service starts on `http://localhost:8080`. Once it is running, check it with:


```bash
curl http://localhost:8080/actuator/health
```


You can also build and run the JAR:


```bash
mvn clean package
java -jar target/bpl-betting-service-1.0.0.jar
```


### H2 console


The H2 console is disabled during normal runs. To enable it locally, start the application with the `dev`
profile:


```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```


Open `http://localhost:8080/h2-console` and use:


- JDBC URL: `jdbc:h2:mem:bpl`
- User: `sa`
- Password: leave blank


If IntelliJ reports that records are not supported, check that both the project SDK and language level
are set to Java 17 or later, then reload the Maven project.


## Authentication


All endpoints except the health check require an `X-API-Key` header. These development keys are configured
in `src/main/resources/application.yml`:


| Used by | Role | API key | User |
|---|---|---|---|
| Operator/feed | `ADMIN` | `dev-admin-key-change-me` | - |
| User 1 | `USER` | `dev-user-u1001-key-change-me` | `U1001` |
| User 2 | `USER` | `dev-user-u1002-key-change-me` | `U1002` |


These keys are only for local development. Use environment variables such as
`BPL_SECURITY_APIKEYS_0_KEY` to provide real secrets outside the development environment.


## Trying the API


The example below covers the usual flow: create a match and place two bets.


```bash
ADMIN=dev-admin-key-change-me
U1=dev-user-u1001-key-change-me
U2=dev-user-u1002-key-change-me


# Create a scheduled match
curl -X PUT http://localhost:8080/matches/BPL-2026-M01 \
 -H "X-API-Key: $ADMIN" \
 -H "Content-Type: application/json" \
 -d '{
   "name": "Mumbai Mavericks vs Chennai Chargers",
   "teams": [
     {"teamId": "MUM", "name": "Mumbai Mavericks"},
     {"teamId": "CHE", "name": "Chennai Chargers"}
   ],
   "startTime": "2030-11-02T14:00:00Z",
   "status": "SCHEDULED"
 }'


# Place one bet for each user
curl -X POST http://localhost:8080/bets \
 -H "X-API-Key: $U1" \
 -H "Idempotency-Key: 6f1c2d0e-0001" \
 -H "Content-Type: application/json" \
 -d '{"userId":"U1001","matchId":"BPL-2026-M01","selectedTeamId":"MUM"}'


curl -X POST http://localhost:8080/bets \
 -H "X-API-Key: $U2" \
 -H "Content-Type: application/json" \
 -d '{"userId":"U1002","matchId":"BPL-2026-M01","selectedTeamId":"CHE"}'
```


To accept score updates, first update the match status to `LIVE`. Scores are full replacements of the
latest score for a team, so they can also correct an earlier update.


When the match status changes to `COMPLETED`, every open bet is settled in the same database transaction.
Users can then retrieve their bets with:


```bash
curl http://localhost:8080/users/U1001/bets -H "X-API-Key: $U1"
curl "http://localhost:8080/users/U1002/bets?status=SETTLED" -H "X-API-Key: $U2"
```


## Endpoints


| Method and path | Access | Purpose |
|---|---|---|
| `PUT /matches/{matchId}` | Admin | Create or update a match |
| `PUT /matches/{matchId}/score` | Admin | Update a team's score for a live match |
| `GET /matches` | Any authenticated caller | List matches, optionally filtered by status |
| `GET /matches/{matchId}` | Any authenticated caller | Get one match |
| `POST /bets` | User | Place a bet for the authenticated user |
| `GET /bets/{betId}` | Owner or admin | Get one bet |
| `GET /users/{userId}/bets` | Same user or admin | List a user's bets |
| `GET /matches/{matchId}/bets` | Admin | List bets for a match |


List endpoints accept `page` and `size` parameters and return the total count in the `X-Total-Count`
response header.


`POST /bets` accepts an optional `Idempotency-Key` header. Repeating the same request with the same key
returns the original bet instead of creating another one. Reusing that key for a different request returns
an error.


## Errors


Errors use the RFC 7807 `application/problem+json` format. The `code` field is intended for client-side
handling, while `detail` provides a readable description.


```json
{
 "type": "about:blank",
 "title": "Conflict",
 "status": 409,
 "detail": "Betting is closed for match BPL-2026-M01",
 "code": "BETTING_CLOSED",
 "path": "/bets"
}
```


Common responses are:


- `400` for validation errors or malformed requests
- `401` for a missing or invalid API key
- `403` when the caller does not have permission
- `404` when a match or bet cannot be found
- `409` when the request conflicts with the current match or bet state
- `422` when the request is valid JSON but breaks a business rule
- `503` when a database lock cannot be acquired in time


## Implementation notes


The code is split into the usual web, service, repository, and domain layers. Flyway owns the database
schema, including its foreign keys, checks, and unique constraints.


### Match updates and concurrent bets


Operations that depend on a match's current state lock its database row with `SELECT ... FOR UPDATE`.
This prevents a new bet from slipping in while the same match is being completed.


Completing a match and settling its bets happen in one transaction. A concurrent bet either finishes
before settlement and gets settled, or waits and is rejected because betting has closed. Locks are scoped
to a single match, so activity on different matches does not block each other.


### Bet retries


A user can only have one bet per match, enforced by a database unique constraint. The optional
`Idempotency-Key` makes request retries safe:


- Same user, key, and request: return the original bet
- Same user and key with different request data: return `IDEMPOTENCY_KEY_REUSED`


Idempotency keys are stored with a SHA-256 fingerprint of the request rather than the request body itself.


### Security


API keys are hashed at startup and plaintext keys are not logged. A `USER` key is tied to one user ID,
which prevents users from placing bets or reading data on behalf of another user. Admin keys can manage
matches and inspect all bets.


The application is stateless and does not use cookie authentication, so CSRF protection is disabled.
Unexpected errors are logged with a request ID without exposing stack traces or database details in the
response.


## Business rules


- A match moves from `SCHEDULED` to `LIVE` to `COMPLETED`. It cannot move backwards.
- Bets are accepted only while a match is scheduled and its start time has not passed.
- Each user can place one bet per match. Bets cannot be edited or cancelled.
- A completed match cannot be changed, although repeating the same update is allowed.
- The team with the higher stored score wins unless the operator supplies `winnerTeamId`.
- A tie, missing score, or no-result match settles open bets as `VOID`.
- Scores use cricket notation: `17.3` means 17 overs and 3 balls, while `17.7` is invalid.
- The service tracks predictions only. Stakes, odds, wallets, and payouts are not part of this project.


## Current limitations


This project is intended as a standalone demo service, not a production betting platform:


- H2 data is lost whenever the application restarts.
- API keys are static and require a restart to rotate.
- Idempotency records are not automatically removed.
- Live scores must be polled; there is no feed integration or push API.
- Completed results cannot be corrected through the API.
- There is no Swagger UI, metrics, tracing, wallet, payout, or regulatory workflow.
- Writes for a heavily used match are serialized on one row lock, which limits throughput.


For a production deployment, the service would need a persistent database, managed authentication,
rate limiting, TLS, monitoring, and the relevant financial and regulatory controls.


## Project structure


```text
src/main/java/com/bally/bpl
 config/       Application configuration
 domain/       JPA entities and enums
 repository/   Spring Data repositories
 security/     API-key authentication and authorization
 service/      Match and betting business logic
 web/          Controllers, DTOs, and error handling


src/main/resources
 application.yml
 db/migration/V1__init.sql


src/test/java/com/bally/bpl
 API, security, and concurrency tests
```


