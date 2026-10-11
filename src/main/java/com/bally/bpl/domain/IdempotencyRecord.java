package com.bally.bpl.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Remembers which bet a given (user, Idempotency-Key) produced, together with a fingerprint of the
 * original request so that re-using a key for a different request can be detected.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @EmbeddedId
    private IdempotencyId id;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "bet_id", nullable = false, length = 32)
    private String betId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
        // for JPA
    }

    public IdempotencyRecord(IdempotencyId id, String requestHash, String betId, Instant createdAt) {
        this.id = id;
        this.requestHash = requestHash;
        this.betId = betId;
        this.createdAt = createdAt;
    }

    public IdempotencyId getId() { return id; }
    public String getRequestHash() { return requestHash; }
    public String getBetId() { return betId; }
    public Instant getCreatedAt() { return createdAt; }
}
