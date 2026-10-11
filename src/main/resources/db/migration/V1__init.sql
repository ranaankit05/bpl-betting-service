-- BPL betting service: initial schema

CREATE TABLE matches (
    match_id       VARCHAR(64)  NOT NULL,
    name           VARCHAR(200) NOT NULL,
    team1_id       VARCHAR(32)  NOT NULL,
    team1_name     VARCHAR(100) NOT NULL,
    team2_id       VARCHAR(32)  NOT NULL,
    team2_name     VARCHAR(100) NOT NULL,
    start_time     TIMESTAMP WITH TIME ZONE NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    winner_team_id VARCHAR(32),
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_matches PRIMARY KEY (match_id),
    CONSTRAINT ck_matches_status CHECK (status IN ('SCHEDULED', 'LIVE', 'COMPLETED')),
    CONSTRAINT ck_matches_distinct_teams CHECK (team1_id <> team2_id)
);

CREATE INDEX ix_matches_status_start ON matches (status, start_time);

CREATE TABLE match_scores (
    match_id   VARCHAR(64) NOT NULL,
    team_id    VARCHAR(32) NOT NULL,
    runs       INT         NOT NULL,
    wickets    INT         NOT NULL,
    overs      DECIMAL(3, 1) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_match_scores PRIMARY KEY (match_id, team_id),
    CONSTRAINT fk_scores_match FOREIGN KEY (match_id) REFERENCES matches (match_id),
    CONSTRAINT ck_scores_runs CHECK (runs >= 0),
    CONSTRAINT ck_scores_wickets CHECK (wickets BETWEEN 0 AND 10),
    CONSTRAINT ck_scores_overs CHECK (overs >= 0 AND overs <= 20)
);

CREATE TABLE bets (
    bet_id           VARCHAR(32) NOT NULL,
    user_id          VARCHAR(64) NOT NULL,
    match_id         VARCHAR(64) NOT NULL,
    selected_team_id VARCHAR(32) NOT NULL,
    status           VARCHAR(16) NOT NULL,
    result           VARCHAR(16),
    placed_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    settled_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_bets PRIMARY KEY (bet_id),
    CONSTRAINT fk_bets_match FOREIGN KEY (match_id) REFERENCES matches (match_id),
    -- Business rule enforced at the database level: one bet per user per match.
    CONSTRAINT uq_bets_user_match UNIQUE (user_id, match_id),
    CONSTRAINT ck_bets_status CHECK (status IN ('OPEN', 'SETTLED')),
    CONSTRAINT ck_bets_result CHECK (result IS NULL OR result IN ('WON', 'LOST', 'VOID')),
    CONSTRAINT ck_bets_settled_consistency CHECK (
        (status = 'OPEN' AND result IS NULL AND settled_at IS NULL) OR
        (status = 'SETTLED' AND result IS NOT NULL AND settled_at IS NOT NULL))
);

CREATE INDEX ix_bets_user_status ON bets (user_id, status, placed_at);
CREATE INDEX ix_bets_match_status ON bets (match_id, status, placed_at);

CREATE TABLE idempotency_keys (
    user_id      VARCHAR(64)  NOT NULL,
    idem_key     VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64)  NOT NULL,
    bet_id       VARCHAR(32)  NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (user_id, idem_key),
    CONSTRAINT fk_idem_bet FOREIGN KEY (bet_id) REFERENCES bets (bet_id)
);
