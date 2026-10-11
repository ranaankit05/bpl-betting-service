package com.bally.bpl.web;

public final class Headers {

    public static final String TOTAL_COUNT = "X-Total-Count";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
    public static final String REQUEST_ID = "X-Request-Id";

    private Headers() {
    }
}
