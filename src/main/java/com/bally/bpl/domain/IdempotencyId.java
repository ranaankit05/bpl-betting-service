package com.bally.bpl.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;

@Embeddable
public record IdempotencyId(
        @Column(name = "user_id", length = 64, nullable = false) String userId,
        @Column(name = "idem_key", length = 128, nullable = false) String key) implements Serializable {
}
