package com.paiban.integration.dingtalk;

import com.paiban.integration.domain.DingDepartment;
import java.util.List;

/** Thin port over the DingTalk open platform so it can be faked in tests. */
public interface DingTalkClient {

    /** Exchanges a mini-app authCode for a DingTalk userId. */
    String exchangeAuthCode(String authCode);

    /** Direct children of a department ({@code topapi/v2/department/listsub}). */
    List<DingDepartment> listSubDepartments(long departmentId);

    /** One cursor page of a department's users ({@code topapi/v2/user/list}). */
    DingUserPage listUsers(long departmentId, long cursor, int size);

    boolean sendWorkNotification(String dingtalkUserId, String title, String markdown, String link);
}
