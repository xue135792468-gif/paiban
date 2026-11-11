package com.paiban.identity.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.AccountAuditAction;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.Role;
import com.paiban.identity.web.AccountAuditDtos.AccountActionOption;
import com.paiban.identity.web.AccountAuditDtos.AccountAuditEntry;
import com.paiban.identity.web.AccountAuditDtos.AccountAuditPage;
import com.paiban.identity.web.AccountAuditDtos.AccountSnapshot;
import java.util.Arrays;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 票 02：账号操作审计的唯一写入与读取入口，复用现有 {@code audit_log} 表。
 *
 * <p>写入发生在调用方同一个事务里：业务操作失败回滚时审计一并消失，不会留下
 * 「成功」的假事件。03 自动开号 / 配对、04 同步停用通过 {@link #record} 写入，
 * 操作者用 {@link #SYSTEM_ACTOR}。
 *
 * <p>快照严格按 allowlist 生成：只有账号标识、姓名、启用状态、关联教师与身份编码；
 * 密码、哈希、token、集成密钥与原始请求都不会经过这里。
 */
@Service
public class AccountAuditService {

    /** 账号类事件的分类值：账号审计页只读它，旧通用日志接口只读它以外的事件。 */
    public static final String CATEGORY = "ACCOUNT";

    /** 规格「记录」43：钉钉免登触发的系统动作，操作者写作「系统（钉钉免登）」。 */
    public static final String SYSTEM_ACTOR = "系统（钉钉免登）";

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final AuditLogRepository auditLogs;
    private final TeacherRepository teachers;
    private final ObjectMapper json;

    public AccountAuditService(
            AuditLogRepository auditLogs, TeacherRepository teachers, ObjectMapper json) {
        this.auditLogs = auditLogs;
        this.teachers = teachers;
        this.json = json;
    }

    /** 一个账号动作一条事件；{@code before} 为 null 表示新建。 */
    @Transactional
    public void record(
            String actor,
            AccountAuditAction action,
            Long targetUserId,
            AccountSnapshot before,
            AccountSnapshot after) {
        AccountSnapshot subject = after != null ? after : before;
        String detail = action.label()
                + (subject == null ? "" : "「" + subject.displayName() + "」(" + subject.username() + ")");
        AuditLog log = newLog(actor, action, targetUserId, detail, subject == null ? 0 : 1, 0);
        log.setBeforeJson(write(before));
        log.setAfterJson(write(after));
        auditLogs.save(log);
    }

    /**
     * 没有单一目标账号的账号类事件（批量导入的整批摘要等）。带上分类，
     * 因此也不会从旧通用日志接口漏给教务管理员。
     */
    @Transactional
    public void recordSummary(String actor, AccountAuditAction action, String detail, int success, int failed) {
        auditLogs.save(newLog(actor, action, null, detail, success, failed));
    }

    /** 账号审计页：过滤、排序与分页全部由数据库完成。 */
    @Transactional(readOnly = true)
    public AccountAuditPage page(String actionCode, Long targetUserId, int page, int size) {
        AccountAuditAction action = null;
        if (StringUtils.hasText(actionCode)) {
            action = AccountAuditAction.byCode(actionCode);
            if (action == null) {
                throw ApiException.badRequest("未知的账号动作: " + actionCode);
            }
        }
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 1);
        Page<AuditLog> found = auditLogs.search(
                CATEGORY,
                action == null ? null : action.code(),
                action == null ? null : AccountAuditAction.legacyCodeFor(action.code()),
                targetUserId,
                PageRequest.of(safePage - 1, safeSize));
        return new AccountAuditPage(
                found.getContent().stream().map(this::toEntry).toList(),
                safePage,
                safeSize,
                found.getTotalElements());
    }

    public List<AccountActionOption> actions() {
        return Arrays.stream(AccountAuditAction.values())
                .map(action -> new AccountActionOption(action.code(), action.label()))
                .toList();
    }

    /** 账号快照 allowlist（唯一一份，业务写入与测试断言都以它为准）。 */
    public AccountSnapshot snapshotOf(AppUser user) {
        if (user == null) {
            return null;
        }
        List<String> roleCodes = user.getRoles().stream().map(Role::getCode).sorted().toList();
        Long teacherId = user.getTeacherId();
        String teacherName = teacherId == null ? null
                : teachers.findById(teacherId).map(teacher -> teacher.getName()).orElse(null);
        return new AccountSnapshot(
                user.getUsername(),
                user.getDisplayName(),
                user.isEnabled(),
                teacherId,
                teacherName,
                roleCodes);
    }

    private AuditLog newLog(
            String actor,
            AccountAuditAction action,
            Long targetUserId,
            String detail,
            int success,
            int failed) {
        AuditLog log = new AuditLog(actor, action.code(), "APP_USER", detail, success, failed);
        log.setCategory(CATEGORY);
        log.setTargetUserId(targetUserId);
        return log;
    }

    private AccountAuditEntry toEntry(AuditLog log) {
        AccountSnapshot before = read(log.getBeforeJson());
        AccountSnapshot after = read(log.getAfterJson());
        AccountSnapshot subject = after != null ? after : before;
        return new AccountAuditEntry(
                log.getId(),
                log.getCreatedAt(),
                log.getActor(),
                AccountAuditAction.canonicalCode(log.getAction()),
                log.getTargetUserId(),
                subject == null ? null : subject.username(),
                subject == null ? null : subject.displayName(),
                log.getDetail(),
                log.getSuccessRows(),
                log.getFailedRows(),
                before,
                after);
    }

    private String write(AccountSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            return json.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("账号审计快照序列化失败", ex);
        }
    }

    /** 旧事件没有快照；解析失败也只当作「无快照」，不让一条坏数据挡住整页。 */
    private AccountSnapshot read(String payload) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        try {
            return json.readValue(payload, AccountSnapshot.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
