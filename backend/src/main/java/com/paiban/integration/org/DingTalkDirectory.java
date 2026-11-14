package com.paiban.integration.org;

import com.paiban.integration.domain.DingDepartment;
import com.paiban.integration.domain.DingUser;
import java.util.List;

/**
 * The full directory the application is allowed to read, already de-duplicated by
 * DingTalk user id. {@code departments} contains every non-special department that
 * was scanned; {@code skipped} lists the negative ids that were not (for example
 * {@code -7}, the 家校通讯录) and {@code failedDepartments} counts departments the
 * tenant refused to list.
 */
public record DingTalkDirectory(
        List<DingUser> users,
        List<DingDepartment> departments,
        List<DingDepartment> skipped,
        int failedDepartments,
        String incompleteReason) {

    public DingTalkDirectory {
        users = users == null ? List.of() : List.copyOf(users);
        departments = departments == null ? List.of() : List.copyOf(departments);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }

    /**
     * Older callers (and tests that do not model a broken scan) only know the
     * failed-department counter; an unreadable department still means incomplete.
     */
    public DingTalkDirectory(
            List<DingUser> users,
            List<DingDepartment> departments,
            List<DingDepartment> skipped,
            int failedDepartments) {
        this(users, departments, skipped, failedDepartments, failedDepartments > 0
                ? "有 " + failedDepartments + " 个部门未能完整读取"
                : null);
    }

    /** Ticket 04: false means the missing-user comparison must not run at all. */
    public boolean complete() {
        return incompleteReason == null;
    }

    public int departmentsScanned() {
        return departments.size();
    }

    public int usersScanned() {
        return users.size();
    }

    public int skippedDepartments() {
        return skipped.size();
    }
}
