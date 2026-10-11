package com.bally.bpl.domain;

public enum BetResult {
    WON,
    LOST,
    /** Match finished without a winner (tie / no result / abandoned). The bet is neither won nor lost. */
    VOID
}
