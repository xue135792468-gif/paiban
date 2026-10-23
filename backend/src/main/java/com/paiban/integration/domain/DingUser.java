package com.paiban.integration.domain;

import java.util.List;

/**
 * A DingTalk user as returned by {@code topapi/v2/user/list}.
 *
 * <p>{@code departmentNames} carries every department the user was found in; the
 * directory loader merges duplicates that appear in more than one branch.
 * {@code active} is false when DingTalk says the account is inactive or when the
 * user only appears in a department whose name marks leavers.
 */
public record DingUser(
        String userId,
        String name,
        String mobile,
        String email,
        String jobNumber,
        boolean active,
        List<String> departmentNames) {

    /** Event payloads and callers that do not care about departments use this shape. */
    public DingUser(String userId, String name, String mobile, String email, String jobNumber) {
        this(userId, name, mobile, email, jobNumber, true, List.of());
    }

    public DingUser {
        departmentNames = departmentNames == null ? List.of() : List.copyOf(departmentNames);
    }

    public String primaryDepartmentName() {
        return departmentNames.isEmpty() ? null : departmentNames.get(0);
    }
}
