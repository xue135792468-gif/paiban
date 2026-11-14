package com.paiban.identity.web;

import com.paiban.identity.domain.LoginType;
import java.time.Instant;
import java.util.List;

/** 票 02：账号审计查看接口的返回结构（不含任何密码、哈希、token 或原请求）。 */
public final class AccountAuditDtos {

    private AccountAuditDtos() {}

    /**
     * 账号快照 allowlist：账号标识、姓名、启用、关联教师、身份，以及票 03 追加的
     * 登录类型与钉钉 UID（业务字段，便于事后分辨账号类型与绑定关系）。
     *
     * <p>任何密码、哈希、token、集成密钥都不会进入快照。历史记录的 JSON 没有后两个字段，
     * 读取时保持 {@code null} 表示「当时未知」，不改写旧 JSON。
     */
    public record AccountSnapshot(
            String username,
            String displayName,
            boolean enabled,
            Long teacherId,
            String teacherName,
            List<String> roleCodes,
            LoginType loginType,
            String dingtalkUserId) {

        /** 旧 6 参构造器兼容（02 的历史事件与既有测试 fixture）：未知的类型/绑定读作 null。 */
        public AccountSnapshot(
                String username,
                String displayName,
                boolean enabled,
                Long teacherId,
                String teacherName,
                List<String> roleCodes) {
            this(username, displayName, enabled, teacherId, teacherName, roleCodes, null, null);
        }
    }

    public record AccountAuditEntry(
            Long id,
            Instant createdAt,
            String actor,
            String action,
            Long targetUserId,
            String targetUsername,
            String targetDisplayName,
            /** 旧记录的原文描述，只作为纯文本展示；不含原请求或实体序列化。 */
            String detail,
            int successRows,
            int failedRows,
            AccountSnapshot before,
            AccountSnapshot after) {}

    public record AccountAuditPage(List<AccountAuditEntry> items, int page, int size, long total) {}

    public record AccountActionOption(String code, String label) {}
}
