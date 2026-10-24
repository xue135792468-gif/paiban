package com.paiban.integration.org;

/**
 * Outcome of an organisation sync.
 *
 * <p>{@code success=false} means the directory could not be read (DingTalk error,
 * integration disabled). The {@code message} always carries DingTalk's
 * {@code errcode}/{@code sub_code} on failures so the administrator can act on it.
 * {@code suspected} counts same-name users that were left untouched; {@code skipped}
 * counts the remaining non-writes. Scan counters are zero for providers that do not
 * scan a directory (Feishu, local).
 */
public record OrgSyncResult(
        boolean success,
        int created,
        int updated,
        int deactivated,
        int skipped,
        int suspected,
        int departmentsScanned,
        int usersScanned,
        long elapsedMs,
        String message) {

    public static OrgSyncResult ok(int created, int updated, int deactivated, String message) {
        return new OrgSyncResult(true, created, updated, deactivated, 0, 0, 0, 0, 0, message);
    }

    public static OrgSyncResult failed(String message) {
        return new OrgSyncResult(false, 0, 0, 0, 0, 0, 0, 0, 0, message);
    }
}
