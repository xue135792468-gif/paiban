package com.paiban.integration.org;

import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.FoundationService;
import com.paiban.integration.domain.DingUser;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pulls the staff directory from DingTalk; supports dingtalk-wins and local-wins
 * strategies. The directory is read recursively from the root department, so the
 * import scope is whatever the tenant's address-book permissions expose.
 *
 * <p>The directory fetch is a network call and therefore stays outside any
 * transaction; only the teacher writes and the audit row run in one. Teachers that
 * are missing from this run are never deactivated automatically: tightening the
 * tenant's visibility must not empty the school roster.
 */
@Component
public class DingTalkOrgDataProvider implements OrgDataProvider {

    private static final Logger log = LoggerFactory.getLogger(DingTalkOrgDataProvider.class);

    private final SettingsService settings;
    private final DingTalkDirectoryLoader directoryLoader;
    private final DingTalkTeacherMatcher matcher;
    private final TeacherRepository teachers;
    private final FoundationService foundation;
    private final TransactionTemplate transactionTemplate;

    public DingTalkOrgDataProvider(
            SettingsService settings,
            DingTalkDirectoryLoader directoryLoader,
            DingTalkTeacherMatcher matcher,
            TeacherRepository teachers,
            FoundationService foundation,
            PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.directoryLoader = directoryLoader;
        this.matcher = matcher;
        this.teachers = teachers;
        this.foundation = foundation;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public String code() {
        return "dingtalk";
    }

    @Override
    public OrgSyncResult sync() {
        if (!settings.dingtalkEnabled()) {
            return OrgSyncResult.failed("钉钉集成未启用");
        }
        long startedAt = System.nanoTime();
        DingTalkDirectory directory;
        try {
            directory = directoryLoader.load();
        } catch (RuntimeException ex) {
            long elapsed = elapsedMs(startedAt);
            String message = "拉取钉钉通讯录失败: " + ex.getMessage();
            transactionTemplate.executeWithoutResult(status ->
                    foundation.audit("system", "DINGTALK_SYNC", "teacher", message, 0, 0));
            log.warn("钉钉通讯录同步失败：耗时 {} ms，{}", elapsed, message);
            return new OrgSyncResult(false, 0, 0, 0, 0, 0, elapsed, message);
        }

        boolean dingtalkWins = "dingtalk".equalsIgnoreCase(settings.dingtalkSyncStrategy());
        OrgSyncResult result = transactionTemplate.execute(status -> applyDirectory(directory, dingtalkWins, startedAt));
        if (result == null) {
            return OrgSyncResult.failed("同步未执行");
        }
        log.info(
                "钉钉通讯录同步完成：扫描部门 {} 个 / 用户 {} 人 / 耗时 {} ms"
                        + "（跳过特殊部门 {}，不可读部门 {}），新增 {}，更新 {}，停用 {}",
                result.departmentsScanned(),
                result.usersScanned(),
                result.elapsedMs(),
                directory.skippedDepartments(),
                directory.failedDepartments(),
                result.created(),
                result.updated(),
                result.deactivated());
        return result;
    }

    /** Applies a fetched directory inside the caller's transaction. */
    private OrgSyncResult applyDirectory(DingTalkDirectory directory, boolean dingtalkWins, long startedAt) {
        int created = 0;
        int updated = 0;
        int deactivated = 0;
        for (DingUser user : directory.users()) {
            Teacher teacher = matcher.match(user).orElse(null);
            if (teacher == null) {
                if (isBlank(user.name())) {
                    // A nameless account cannot become a teacher row.
                    continue;
                }
                Teacher fresh = new Teacher();
                applyFields(fresh, user, true);
                fresh.setDingtalkUserId(user.userId());
                fresh.setActive(user.active());
                fresh.setCreatedAt(Instant.now());
                teachers.save(fresh);
                created++;
            } else {
                applyFields(teacher, user, dingtalkWins);
                teacher.setDingtalkUserId(user.userId());
                teacher.setActive(user.active());
                updated++;
            }
            if (!user.active()) {
                deactivated++;
            }
        }
        long elapsed = elapsedMs(startedAt);
        String message = "同步完成：扫描 " + directory.departmentsScanned() + " 个部门 / "
                + directory.usersScanned() + " 人，耗时 " + elapsed + " ms；新增 " + created
                + "，更新 " + updated + "，停用 " + deactivated;
        foundation.audit("system", "DINGTALK_SYNC", "teacher", message, created + updated, 0);
        return new OrgSyncResult(
                true,
                created,
                updated,
                deactivated,
                directory.departmentsScanned(),
                directory.usersScanned(),
                elapsed,
                message);
    }

    /** DingTalk wins overwrites present values; local wins only fills empty ones. */
    private void applyFields(Teacher teacher, DingUser user, boolean dingtalkWins) {
        if (dingtalkWins) {
            setIfPresent(user.name(), teacher::setName);
            setIfPresent(user.jobNumber(), teacher::setEmployeeNo);
            setIfPresent(user.mobile(), teacher::setPhone);
            return;
        }
        if (isBlank(teacher.getName())) {
            setIfPresent(user.name(), teacher::setName);
        }
        if (isBlank(teacher.getEmployeeNo())) {
            setIfPresent(user.jobNumber(), teacher::setEmployeeNo);
        }
        if (isBlank(teacher.getPhone())) {
            setIfPresent(user.mobile(), teacher::setPhone);
        }
    }

    @Transactional
    public void applyEvent(String type, DingUser user) {
        if (user == null || user.userId() == null) {
            return;
        }
        Teacher teacher = matcher.match(user).orElse(null);
        if ("user_leave".equalsIgnoreCase(type)) {
            if (teacher != null) {
                teacher.setActive(false);
            }
            return;
        }
        if (teacher == null) {
            if (isBlank(user.name())) {
                return;
            }
            teacher = new Teacher();
            teacher.setCreatedAt(Instant.now());
        }
        setIfPresent(user.name(), teacher::setName);
        setIfPresent(user.jobNumber(), teacher::setEmployeeNo);
        setIfPresent(user.mobile(), teacher::setPhone);
        setIfPresent(user.email(), teacher::setEmail);
        teacher.setDingtalkUserId(user.userId());
        teacher.setActive(true);
        teachers.save(teacher);
    }

    private void setIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (!isBlank(value)) {
            setter.accept(value);
        }
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
