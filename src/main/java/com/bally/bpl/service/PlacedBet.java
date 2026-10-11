package com.bally.bpl.service;

import com.bally.bpl.domain.Bet;

/** Outcome of a place-bet call. {@code replayed} is true when an earlier request with the same idempotency key is returned. */
public record PlacedBet(Bet bet, boolean replayed) {
}
