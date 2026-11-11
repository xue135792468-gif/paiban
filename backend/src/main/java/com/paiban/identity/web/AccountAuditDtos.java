package com.paiban.identity.web;

import java.time.Instant;
import java.util.List;

/** 票 02：账号审计查看接口的返回结构（不含任何密码、哈希、token 或原请求）。 */
public final class AccountAuditDtos {

    private AccountAuditDtos() {}

    /** 账号快照 allowlist：账号标识、姓名、启用、关联教师、身份。 */
    public record AccountSnapshot(
            String username,
            String displayName,
            boolean enabled,
            Long teacherId,
            String teacherName,
            List<String> roleCodes) {}

    public record AccountAuditEntry(
            Long id,
            Instant createdAt,
            String actor,
            String action,
            Long targetUserId,
            String targetUsername,
            String targetDisplayName,
            AccountSnapshot before,
            AccountSnapshot after) {}

    public record AccountAuditPage(List<AccountAuditEntry> items, int page, int size, long total) {}

    public record AccountActionOption(String code, String label) {}
}
