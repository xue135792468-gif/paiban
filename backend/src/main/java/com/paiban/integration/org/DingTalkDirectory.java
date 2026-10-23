package com.paiban.integration.org;

import com.paiban.integration.domain.DingDepartment;
import com.paiban.integration.domain.DingUser;
import java.util.List;

/**
 * The full directory the application is allowed to read, already de-duplicated by
 * DingTalk user id. {@code departments} contains every non-special department that
 * was scanned; {@code skippedDepartments} counts negative ids that were skipped and
 * {@code failedDepartments} counts departments the tenant refused to list.
 */
public record DingTalkDirectory(
        List<DingUser> users,
        List<DingDepartment> departments,
        int skippedDepartments,
        int failedDepartments) {

    public DingTalkDirectory {
        users = users == null ? List.of() : List.copyOf(users);
        departments = departments == null ? List.of() : List.copyOf(departments);
    }

    public int departmentsScanned() {
        return departments.size();
    }

    public int usersScanned() {
        return users.size();
    }
}
