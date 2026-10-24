package com.paiban.integration.org;

import com.paiban.integration.domain.DingDepartment;
import com.paiban.integration.domain.DingUser;
import java.util.List;

/**
 * A read-only classification of one DingTalk directory against the local teacher
 * table. Preview renders it; confirm applies exactly the same items, so the two
 * screens cannot disagree about who is new and who is merged.
 */
public record DingTalkSyncPlan(
        List<Item> items,
        List<DingDepartment> skippedDepartments,
        int failedDepartments) {

    public enum Category {
        /** No local teacher: a new row will be created. */
        CREATE,
        /** Matched by a key: only empty fields (and the active flag) will be written. */
        MERGE,
        /** Same name as one or more local teachers, but no matching key. Needs a decision. */
        SUSPECTED,
        /** Nothing to write, with a human-readable reason. */
        SKIP
    }

    public record Item(
            DingUser user,
            Category category,
            String reason,
            Long matchedTeacherId,
            String matchedTeacherName,
            List<FieldChange> fills,
            List<FieldChange> conflicts,
            List<TeacherCandidate> candidates) {

        public Item {
            fills = fills == null ? List.of() : List.copyOf(fills);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public boolean writesTeacher() {
            return category == Category.CREATE || category == Category.MERGE;
        }
    }

    /** Field codes are stable API values; {@code label} is for display. */
    public record FieldChange(String field, String label, String localValue, String remoteValue) {}

    public record TeacherCandidate(Long id, String name, String employeeNo, String phone) {}

    public DingTalkSyncPlan {
        items = items == null ? List.of() : List.copyOf(items);
        skippedDepartments = skippedDepartments == null ? List.of() : List.copyOf(skippedDepartments);
    }

    public long count(Category category) {
        return items.stream().filter(item -> item.category() == category).count();
    }

    public long writeCount() {
        return items.stream().filter(Item::writesTeacher).count();
    }
}
