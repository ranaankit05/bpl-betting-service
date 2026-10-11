package com.bally.bpl.web.dto;

/** Shared identifier format: restrictive on purpose, ids end up in URLs, logs and SQL parameters. */
public final class Patterns {

    public static final String ID = "^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$";
    public static final String MATCH_ID = "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$";
    public static final String USER_ID = "^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$";
    public static final String IDEMPOTENCY_KEY = "^[A-Za-z0-9][A-Za-z0-9_.:-]{7,127}$";

    private Patterns() {
    }
}
