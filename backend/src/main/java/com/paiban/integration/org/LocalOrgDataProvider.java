package com.paiban.integration.org;

import org.springframework.stereotype.Component;

@Component
public class LocalOrgDataProvider implements OrgDataProvider {

    @Override
    public String code() {
        return "local";
    }

    @Override
    public OrgSyncResult sync() {
        return OrgSyncResult.ok(0, 0, 0, "本地模式无需同步");
    }
}
