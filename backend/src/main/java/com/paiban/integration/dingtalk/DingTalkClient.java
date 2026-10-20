package com.paiban.integration.dingtalk;

import com.paiban.integration.domain.DingUser;
import java.util.List;

/** Thin port over the DingTalk open platform so it can be faked in tests. */
public interface DingTalkClient {

    /** Exchanges a mini-app authCode for a DingTalk userId. */
    String exchangeAuthCode(String authCode);

    List<DingUser> listUsers();

    boolean sendWorkNotification(String dingtalkUserId, String title, String markdown, String link);
}
