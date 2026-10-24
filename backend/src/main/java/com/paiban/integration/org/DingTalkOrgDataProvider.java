package com.paiban.integration.org;

import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.FoundationService;
import com.paiban.integration.dingtalk.DingTalkApiException;
import com.paiban.integration.domain.DingUser;
import com.paiban.integration.org.DingTalkSyncPlan.Category;
import com.paiban.integration.org.DingTalkSyncPlan.Item;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pulls the staff directory from DingTalk. The directory is read recursively from
 * the root department, so the import scope is whatever the tenant's address-book
 * permissions expose.
 *
 * <p>Preview and confirm share {@link DingTalkSyncPlanner}: the preview is strictly
 * read-only, and confirm re-plans the freshly fetched directory and writes exactly
 * the items the planner marked as CREATE or MERGE. Same-name users are never merged
 * without an explicit decision, so a name-only overlap cannot create duplicates.
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
    private final DingTalkSyncPlanner planner;
    private final DingTalkMergeRules rules;
    private final DingTalkTeacherMatcher matcher;
    private final TeacherRepository teachers;
    private final FoundationService foundation;
    private final TransactionTemplate transactionTemplate;

    public DingTalkOrgDataProvider(
            SettingsService settings,
            DingTalkDirectoryLoader directoryLoader,
            DingTalkSyncPlanner planner,
            DingTalkMergeRules rules,
            DingTalkTeacherMatcher matcher,
            TeacherRepository teachers,
            FoundationService foundation,
            PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.directoryLoader = directoryLoader;
        this.planner = planner;
        this.rules = rules;
        this.matcher = matcher;
        this.teachers = teachers;
        this.foundation = foundation;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public String code() {
        return "dingtalk";
    }

    /**
     * One-click sync with safe defaults: import every readable user, merge only by
     * key, and leave same-name candidates untouched for the preview screen.
     */
    @Override
    public OrgSyncResult sync() {
        return confirm(DingTalkSyncDecisions.none());
    }

    /** Read-only: loads the directory, classifies it, and writes nothing at all. */
    public DingTalkSyncPreview preview(DingTalkSyncDecisions decisions) {
        if (!settings.dingtalkEnabled()) {
            return DingTalkSyncPreview.failed("钉钉集成未启用");
        }
        long startedAt = System.nanoTime();
        DingTalkDirectory directory;
        try {
            directory = directoryLoader.load();
        } catch (RuntimeException ex) {
            String message = "拉取钉钉通讯录失败: " + DingTalkApiException.redact(ex.getMessage());
            log.warn("钉钉通讯录预演失败：耗时 {} ms，{}", elapsedMs(startedAt), message);
            return DingTalkSyncPreview.failed(message);
        }
        // An invalid merge decision is a caller error (400), not a failed fetch.
        DingTalkSyncPlan plan = planner.plan(directory, decisions);
        long elapsed = elapsedMs(startedAt);
        String message = "预演完成：扫描 " + directory.departmentsScanned() + " 个部门 / "
                + directory.usersScanned() + " 人，耗时 " + elapsed + " ms；新增 "
                + plan.count(Category.CREATE) + "，补齐 " + plan.count(Category.MERGE)
                + "，疑似同名 " + plan.count(Category.SUSPECTED)
                + "，跳过 " + plan.count(Category.SKIP);
        log.info(
                "钉钉通讯录预演：扫描部门 {} 个 / 用户 {} 人 / 耗时 {} ms（跳过特殊部门 {}，不可读部门 {}），"
                        + "新增 {}，补齐 {}，疑似同名 {}，跳过 {}",
                directory.departmentsScanned(),
                directory.usersScanned(),
                elapsed,
                directory.skippedDepartments(),
                directory.failedDepartments(),
                plan.count(Category.CREATE),
                plan.count(Category.MERGE),
                plan.count(Category.SUSPECTED),
                plan.count(Category.SKIP));
        return new DingTalkSyncPreview(
                true,
                message,
                directory.departmentsScanned(),
                directory.usersScanned(),
                directory.skippedDepartments(),
                directory.failedDepartments(),
                elapsed,
                directory.skipped(),
                plan);
    }

    /** Applies the plan built from the selected scope and audits the write. */
    public OrgSyncResult confirm(DingTalkSyncDecisions decisions) {
        if (!settings.dingtalkEnabled()) {
            return OrgSyncResult.failed("钉钉集成未启用");
        }
        long startedAt = System.nanoTime();
        DingTalkDirectory directory;
        try {
            directory = directoryLoader.load();
        } catch (RuntimeException ex) {
            long elapsed = elapsedMs(startedAt);
            String message = "拉取钉钉通讯录失败: " + DingTalkApiException.redact(ex.getMessage());
            transactionTemplate.executeWithoutResult(status ->
                    foundation.audit("system", "DINGTALK_SYNC", "teacher", message, 0, 0));
            log.warn("钉钉通讯录同步失败：耗时 {} ms，{}", elapsed, message);
            return new OrgSyncResult(false, 0, 0, 0, 0, 0, 0, 0, elapsed, message);
        }

        DingTalkSyncPlan plan = planner.plan(directory, decisions);
        OrgSyncResult result = transactionTemplate.execute(status -> apply(directory, plan, startedAt));
        if (result == null) {
            return OrgSyncResult.failed("同步未执行");
        }
        log.info(
                "钉钉通讯录同步完成：扫描部门 {} 个 / 用户 {} 人 / 耗时 {} ms"
                        + "（跳过特殊部门 {}，不可读部门 {}），新增 {}，补齐 {}，停用 {}，跳过 {}，疑似同名 {}",
                result.departmentsScanned(),
                result.usersScanned(),
                result.elapsedMs(),
                directory.skippedDepartments(),
                directory.failedDepartments(),
                result.created(),
                result.updated(),
                result.deactivated(),
                result.skipped(),
                result.suspected());
        return result;
    }

    /** Writes exactly the CREATE/MERGE items inside the caller's transaction. */
    private OrgSyncResult apply(DingTalkDirectory directory, DingTalkSyncPlan plan, long startedAt) {
        int created = 0;
        int updated = 0;
        int deactivated = 0;
        for (Item item : plan.items()) {
            if (item.category() == Category.CREATE) {
                Teacher fresh = new Teacher();
                rules.apply(fresh, item.user());
                fresh.setCreatedAt(Instant.now());
                teachers.save(fresh);
                created++;
            } else if (item.category() == Category.MERGE && item.matchedTeacherId() != null) {
                Teacher teacher = teachers.findById(item.matchedTeacherId()).orElse(null);
                if (teacher == null) {
                    // Deleted between planning and writing; nothing sensible to update.
                    continue;
                }
                rules.apply(teacher, item.user());
                teachers.save(teacher);
                updated++;
            } else {
                continue;
            }
            if (!item.user().active()) {
                deactivated++;
            }
        }
        int skipped = (int) plan.count(Category.SKIP);
        int suspected = (int) plan.count(Category.SUSPECTED);
        long elapsed = elapsedMs(startedAt);
        String message = "同步完成：扫描 " + directory.departmentsScanned() + " 个部门 / "
                + directory.usersScanned() + " 人，耗时 " + elapsed + " ms；新增 " + created
                + "，补齐 " + updated + "，停用 " + deactivated + "，跳过 " + skipped
                + "，疑似同名 " + suspected;
        foundation.audit("system", "DINGTALK_SYNC", "teacher", message, created + updated, 0);
        return new OrgSyncResult(
                true,
                created,
                updated,
                deactivated,
                skipped,
                suspected,
                directory.departmentsScanned(),
                directory.usersScanned(),
                elapsed,
                message);
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
