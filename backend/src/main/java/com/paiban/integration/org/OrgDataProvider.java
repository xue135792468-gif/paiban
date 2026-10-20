package com.paiban.integration.org;

/** Pluggable source of staff data (DingTalk or local). */
public interface OrgDataProvider {

    String code();

    OrgSyncResult sync();
}
