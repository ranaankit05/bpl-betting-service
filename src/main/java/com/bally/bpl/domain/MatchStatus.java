package com.bally.bpl.domain;

/** Lifecycle of a match. Transitions only move forward: SCHEDULED -> LIVE -> COMPLETED. */
public enum MatchStatus {
    SCHEDULED,
    LIVE,
    COMPLETED;

    public boolean canMoveTo(MatchStatus target) {
        return target.ordinal() >= this.ordinal();
    }
}
