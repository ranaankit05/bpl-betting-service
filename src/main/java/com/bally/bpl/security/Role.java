package com.bally.bpl.security;

public enum Role {
    /** Operations staff / data feed: manages matches and scores, can read everything. */
    ADMIN,
    /** An app user: can place bets as themselves and read their own bets. */
    USER
}
