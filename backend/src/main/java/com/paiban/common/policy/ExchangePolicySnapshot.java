package com.paiban.common.policy;

/**
 * One consistent read of the shared exchange policy.
 *
 * <p>Draft swaps and published-timetable substitutions must both read the same
 * values, and the fingerprint makes it possible to tell whether two plans were
 * generated under the same policy (contracts §3).
 */
public record ExchangePolicySnapshot(int maxMovedLessons,
                                     ExchangeRankingPreset rankingPreset,
                                     String fingerprint) {}
