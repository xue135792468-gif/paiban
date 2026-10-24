package com.paiban.integration.dingtalk;

import com.paiban.integration.domain.DingUser;
import java.util.List;

/** One cursor page of {@code topapi/v2/user/list}. */
public record DingUserPage(List<DingUser> users, boolean hasMore, long nextCursor) {

    public DingUserPage {
        users = users == null ? List.of() : List.copyOf(users);
    }

    public static DingUserPage last(List<DingUser> users) {
        return new DingUserPage(users, false, 0);
    }
}
