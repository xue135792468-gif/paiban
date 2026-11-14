package com.paiban.integration.dingtalk;

/**
 * A stateless credential probe: exchange a token for the supplied credentials and
 * read department 1. It deliberately does not share {@link DingTalkClient}'s runtime
 * token cache or any directory, auth or notification state.
 */
public interface DingTalkProbeClient {

    DingTalkProbeCode probe(String appKey, String appSecret);
}
