package com.paiban.integration.org;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the administrator chose on the preview screen. An empty instance is the
 * safe default used by the one-click sync: everything readable is imported, and
 * same-name candidates are left untouched instead of being merged.
 */
public record DingTalkSyncDecisions(
        Set<String> excludedDepartments,
        Set<String> excludedUserIds,
        Map<String, SuspectedDecision> suspectedDecisions) {

    public enum Action {
        /** Merge the DingTalk user into an existing same-name teacher. */
        MERGE,
        /** The names collide but they are different people: create a new teacher. */
        CREATE,
        /** Leave the DingTalk user out of this run. */
        SKIP
    }

    public record SuspectedDecision(Action action, Long teacherId) {}

    /** Input form that names the DingTalk user the decision belongs to. */
    public record Decision(String userId, Action action, Long teacherId) {}

    public DingTalkSyncDecisions {
        excludedDepartments = excludedDepartments == null ? Set.of() : Set.copyOf(excludedDepartments);
        excludedUserIds = excludedUserIds == null ? Set.of() : Set.copyOf(excludedUserIds);
        suspectedDecisions = suspectedDecisions == null ? Map.of() : Map.copyOf(suspectedDecisions);
    }

    public static DingTalkSyncDecisions none() {
        return new DingTalkSyncDecisions(Set.of(), Set.of(), Map.of());
    }

    public static DingTalkSyncDecisions of(
            List<String> excludedDepartments,
            List<String> excludedUserIds,
            List<Decision> suspectedDecisions) {
        Map<String, SuspectedDecision> decisions = new java.util.LinkedHashMap<>();
        if (suspectedDecisions != null) {
            for (Decision decision : suspectedDecisions) {
                if (decision != null && decision.userId() != null) {
                    decisions.put(decision.userId(), new SuspectedDecision(decision.action(), decision.teacherId()));
                }
            }
        }
        return new DingTalkSyncDecisions(clean(excludedDepartments), clean(excludedUserIds), decisions);
    }

    private static Set<String> clean(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        Set<String> cleaned = new java.util.LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value);
            }
        }
        return Set.copyOf(cleaned);
    }
}
