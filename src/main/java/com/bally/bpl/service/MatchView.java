package com.bally.bpl.service;

import com.bally.bpl.domain.Match;
import com.bally.bpl.domain.MatchScore;

import java.util.List;

/** A match together with its current scores. */
public record MatchView(Match match, List<MatchScore> scores) {
}
