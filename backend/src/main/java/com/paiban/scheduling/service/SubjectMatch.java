package com.paiban.scheduling.service;

import java.util.Locale;

/**
 * One rule for "are these two subjects the same?" shared by the swap write endpoint
 * and the candidate panel. Values are trimmed and compared ignoring case; a missing
 * value is unknown rather than a match, so callers can warn and let the exchange
 * through instead of silently treating two blank subjects as compatible.
 */
public final class SubjectMatch {

    public enum Result {
        MATCH,
        MISMATCH,
        UNKNOWN
    }

    private SubjectMatch() {}

    public static Result of(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        if (normalizedLeft == null || normalizedRight == null) {
            return Result.UNKNOWN;
        }
        return normalizedLeft.equals(normalizedRight) ? Result.MATCH : Result.MISMATCH;
    }

    /** Trimmed lowercase value, or null when the subject is missing or blank. */
    public static String normalize(String subject) {
        return subject == null || subject.isBlank() ? null : subject.trim().toLowerCase(Locale.ROOT);
    }

    /** Trimmed value for messages, or "未设置" when the subject is missing or blank. */
    public static String display(String subject) {
        return subject == null || subject.isBlank() ? "未设置" : subject.trim();
    }
}
