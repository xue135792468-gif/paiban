package com.paiban.integration.dingtalk;

import com.paiban.integration.domain.DingDepartment;
import com.paiban.integration.domain.DingUser;
import java.util.List;

/** Thin port over the DingTalk open platform so it can be faked in tests. */
public interface DingTalkClient {

    /** Exchanges an H5 micro-app authCode for a DingTalk userId. */
    String exchangeAuthCode(String authCode);

    /** Reads the vendor-verified identity; local permissions never come from vendor role flags. */
    DingUser getUser(String userId);

    /** Direct children of a department ({@code topapi/v2/department/listsub}). */
    List<DingDepartment> listSubDepartments(long departmentId);

    /** One cursor page of a department's users ({@code topapi/v2/user/list}). */
    DingUserPage listUsers(long departmentId, long cursor, int size);

    boolean sendWorkNotification(String dingtalkUserId, String title, String markdown, String link);
}
