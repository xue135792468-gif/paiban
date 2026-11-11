package com.paiban.substitution.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.service.UserService;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.substitution.plan.PlanMove;
import com.paiban.substitution.plan.PlanSnapshot;
import com.paiban.substitution.plan.PlanVerifier;
import com.paiban.substitution.web.SubstitutionDtos.ApproveSubstitutionRequest;
import com.paiban.substitution.web.SubstitutionDtos.RejectSubstitutionRequest;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionRequestDto;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 票 05：待审批申请的生命周期（批准 / 驳回 / 撤回 / 惰性作废）。
 *
 * <p>锁定顺序固定为「学期 → 申请」：先用轻量读取定位申请所属学期（仅用于选锁，
 * 不是锁后权威状态），拿到学期行锁后再锁申请行并刷新，此后的一切判断都用刷新后的状态。
 *
 * <p>作废必须真落库：开课后仍未处理的申请在同一个可写事务里保存 EXPIRED 与历史，
 * 事务提交之后才向调用方返回 409。因此这里用显式的 {@link TransactionTemplate}
 * 承载事务、用返回值表达「成功 / 已作废」，而不是在事务里抛异常把作废一起回滚，
 * 也不对全部 ApiException 免回滚（那会保留本应回滚的半写）。
 */
@Service
public class SubstitutionApprovalService {

    /** 审批意见 / 驳回理由与列宽（varchar 500）一致；入口先校验，不把超长文本留给 SQL。 */
    static final int MAX_COMMENT = 500;

    private final SubstitutionRequestRepository requests;
    private final TimetableEntryRepository entries;
    private final PeriodRepository periods;
    private final TeacherRepository teachers;
    private final UserService users;
    private final PlanSnapshotFactory snapshots;
    private final SubstitutionEffects effects;
    private final SubstitutionMapper mapper;
    private final AuditLogRepository auditLogs;
    private final Clock clock;
    private final TransactionTemplate transaction;

    @PersistenceContext
    private EntityManager entityManager;

    public SubstitutionApprovalService(
            SubstitutionRequestRepository requests,
            TimetableEntryRepository entries,
            PeriodRepository periods,
            TeacherRepository teachers,
            UserService users,
            PlanSnapshotFactory snapshots,
            SubstitutionEffects effects,
            SubstitutionMapper mapper,
            AuditLogRepository auditLogs,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.requests = requests;
        this.entries = entries;
        this.periods = periods;
        this.teachers = teachers;
        this.users = users;
        this.snapshots = snapshots;
        this.effects = effects;
        this.mapper = mapper;
        this.auditLogs = auditLogs;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** 事务内结果：成功带 DTO；已作废带对外冲突文案（提交后再抛，保证作废已落库）。 */
    private record Outcome(SubstitutionRequestDto dto, String conflictAfterCommit) {}

    public SubstitutionRequestDto approve(
            String username, Long requestId, ApproveSubstitutionRequest body) {
        String comment = StringUtils.hasText(body == null ? null : body.comment())
                ? body.comment().trim() : null;
        if (comment != null && comment.length() > MAX_COMMENT) {
            throw ApiException.badRequest("审批意见不能超过 " + MAX_COMMENT + " 字");
        }
        Outcome outcome = transaction.execute(status -> approveLocked(username, requestId, body));
        if (outcome == null) {
            throw ApiException.conflict("审批未完成，请重试");
        }
        if (outcome.conflictAfterCommit() != null) {
            throw ApiException.conflict(outcome.conflictAfterCommit());
        }
        return outcome.dto();
    }

    private Outcome approveLocked(
            String username, Long requestId, ApproveSubstitutionRequest body) {
        SubstitutionRequest request = lockRequest(requestId);
        AppUser user = requireApprover(username);
        requireStillPending(request);

        TimetableEntry entry = request.getEntryId() == null ? null
                : entries.findById(request.getEntryId()).orElse(null);
        if (entry == null) {
            throw ApiException.conflict("课表已撤回，该申请对应的课节已失效，不能批准，请驳回该申请");
        }
        PlanSnapshot snapshot = snapshots.build(request.getSemesterId());
        PlanSnapshot.ScheduledLesson current = currentInstance(snapshot, request);
        requireInstanceUnchanged(request, current);

        Instant deadline = LessonDeadline.earliest(List.of(new LessonDeadline.ChangedSlot(
                request.getEffectiveDate(),
                SubstitutionLookups.periodStart(periods, current.periodId()))));
        String label = SubstitutionMapper.label(current.dayOfWeek(), current.periodNo())
                + "（" + request.getEffectiveDate() + "）";
        if (!LessonDeadline.canConfirm(clock.instant(), deadline)) {
            // 真落库：保存 EXPIRED 与历史，事务提交后由外层抛出 409。
            expire(request, username);
            return new Outcome(null, "已到开课时刻（" + label + "），该申请已作废");
        }

        Long finalTeacherId = body != null && body.substituteTeacherId() != null
                ? body.substituteTeacherId() : request.getExpectedSubstituteTeacherId();
        if (finalTeacherId == null) {
            throw ApiException.badRequest("请指定代课教师");
        }
        requireUsableTeacher(finalTeacherId);
        verifyCover(request, current, finalTeacherId, snapshot);

        String comment = StringUtils.hasText(body == null ? null : body.comment())
                ? body.comment().trim() : null;
        Instant now = clock.instant();
        // 再次调整：同一实例的旧生效行退出，新行接管（同一时刻只留一条 EFFECTIVE）。
        effects.retireEffectiveRowAt(entry.getId(), request.getEffectiveDate());
        effects.createCoverSubstitution(request, finalTeacherId, current);
        request.setFinalTeacherId(finalTeacherId);
        request.setStatus(SubstitutionRequest.EFFECTIVE);
        request.setApproverUserId(user.getId());
        request.setApprovedAt(now);
        request.setApprovalComment(comment);
        request.setUpdatedAt(now);
        SubstitutionRequest saved = requests.save(request);
        effects.recordHistory(saved.getId(), SubstitutionRequest.SUBMITTED,
                SubstitutionRequest.EFFECTIVE, username, comment);
        auditLogs.save(new AuditLog(username, "SUBSTITUTION_REQUEST_APPROVED", "SUBSTITUTION",
                "requestId=" + saved.getId()
                        + ", entryId=" + saved.getEntryId()
                        + ", lessonDate=" + saved.getEffectiveDate()
                        + ", expectedSubstituteTeacherId=" + saved.getExpectedSubstituteTeacherId()
                        + ", finalSubstituteTeacherId=" + finalTeacherId
                        + ", approverUserId=" + user.getId(),
                1, 0));
        return new Outcome(mapper.toDto(saved), null);
    }

    /** 驳回：理由必填且不超列宽；不产生生效行，老师能在「我的调代」看到理由。 */
    public SubstitutionRequestDto reject(
            String username, Long requestId, RejectSubstitutionRequest body) {
        String reason = body == null || body.reason() == null ? "" : body.reason().trim();
        if (reason.isEmpty()) {
            throw ApiException.badRequest("请填写驳回理由");
        }
        if (reason.length() > MAX_COMMENT) {
            throw ApiException.badRequest("驳回理由不能超过 " + MAX_COMMENT + " 字");
        }
        Outcome outcome = transaction.execute(
                status -> rejectLocked(username, requestId, reason));
        if (outcome == null) {
            throw ApiException.conflict("驳回未完成，请重试");
        }
        if (outcome.conflictAfterCommit() != null) {
            throw ApiException.conflict(outcome.conflictAfterCommit());
        }
        return outcome.dto();
    }

    private Outcome rejectLocked(
            String username, Long requestId, String reason) {
        SubstitutionRequest request = lockRequest(requestId);
        AppUser user = requireApprover(username);
        requireStillPending(request);
        if (overdue(request)) {
            expire(request, username);
            return new Outcome(null, "已到开课时刻，该申请已作废");
        }
        Instant now = clock.instant();
        request.setStatus(SubstitutionRequest.REJECTED);
        request.setApproverUserId(user.getId());
        request.setApprovedAt(now);
        request.setApprovalComment(reason);
        request.setUpdatedAt(now);
        SubstitutionRequest saved = requests.save(request);
        effects.recordHistory(saved.getId(), SubstitutionRequest.SUBMITTED,
                SubstitutionRequest.REJECTED, username, reason);
        auditLogs.save(new AuditLog(username, "SUBSTITUTION_REQUEST_REJECTED", "SUBSTITUTION",
                "requestId=" + saved.getId()
                        + ", entryId=" + saved.getEntryId()
                        + ", lessonDate=" + saved.getEffectiveDate()
                        + ", approverUserId=" + user.getId()
                        + ", reason=" + reason,
                1, 0));
        return new Outcome(mapper.toDto(saved), null);
    }

    /** 撤回：只有申请人本人、且只在待审批时可撤；不需要审批权限。 */
    public SubstitutionRequestDto withdraw(String username, Long requestId) {
        Outcome outcome = transaction.execute(
                status -> withdrawLocked(username, requestId));
        if (outcome == null) {
            throw ApiException.conflict("撤回未完成，请重试");
        }
        if (outcome.conflictAfterCommit() != null) {
            throw ApiException.conflict(outcome.conflictAfterCommit());
        }
        return outcome.dto();
    }

    private Outcome withdrawLocked(String username, Long requestId) {
        SubstitutionRequest request = lockRequest(requestId);
        AppUser user = requireCurrentUser(username);
        if (!SubstitutionLookups.isOwn(request, user)) {
            throw ApiException.forbidden("只能撤回自己的调代申请");
        }
        requireStillPending(request);
        if (overdue(request)) {
            expire(request, username);
            return new Outcome(null, "已到开课时刻，该申请已作废");
        }
        Instant now = clock.instant();
        request.setStatus(SubstitutionRequest.WITHDRAWN);
        request.setUpdatedAt(now);
        SubstitutionRequest saved = requests.save(request);
        effects.recordHistory(saved.getId(), SubstitutionRequest.SUBMITTED,
                SubstitutionRequest.WITHDRAWN, username, null);
        auditLogs.save(new AuditLog(username, "SUBSTITUTION_REQUEST_WITHDRAWN", "SUBSTITUTION",
                "requestId=" + saved.getId()
                        + ", entryId=" + saved.getEntryId()
                        + ", lessonDate=" + saved.getEffectiveDate()
                        + ", operatorUserId=" + user.getId(),
                1, 0));
        return new Outcome(mapper.toDto(saved), null);
    }

    /** 开课后仍未处理：作废并留痕，随后由外层把 409 返回给调用方。 */
    private void expire(SubstitutionRequest request, String username) {
        request.setStatus(SubstitutionRequest.EXPIRED);
        request.setUpdatedAt(clock.instant());
        requests.save(request);
        effects.recordHistory(request.getId(), SubstitutionRequest.SUBMITTED,
                SubstitutionRequest.EXPIRED, username, "开课后仍未处理，自动作废");
        auditLogs.save(new AuditLog(username, "SUBSTITUTION_REQUEST_EXPIRED", "SUBSTITUTION",
                "requestId=" + request.getId()
                        + ", entryId=" + request.getEntryId()
                        + ", lessonDate=" + request.getEffectiveDate(),
                1, 0));
    }

    /**
     * 惰性清扫：把已经过了开课时刻、仍停在待审批的申请作废（只处理调用者有权看到的记录）。
     * 列表用它保证「待审批」不会长期挂着早已上过的课；写入与请求同事务提交，
     * 因此返回的列表重查后看不到刚作废的行。
     */
    public int expireOverdue(Long semesterId, AppUser viewer) {
        boolean allVisible = users.hasPermission(viewer, Permissions.SUBSTITUTION_VIEW);
        Integer expired = transaction.execute(status -> {
            List<SubstitutionRequest> pending = requests.findBySemesterIdAndStatusOrderByCreatedAtDesc(
                    semesterId, SubstitutionRequest.SUBMITTED);
            int count = 0;
            boolean semesterLocked = false;
            for (SubstitutionRequest candidate : pending) {
                if (!allVisible && !SubstitutionLookups.isOwn(candidate, viewer)) {
                    continue;
                }
                if (!overdue(candidate)) {
                    continue;
                }
                if (!semesterLocked) {
                    effects.lockSemester(semesterId);
                    semesterLocked = true;
                }
                SubstitutionRequest row = requests.findWithLockById(candidate.getId()).orElse(null);
                if (row == null) {
                    continue;
                }
                entityManager.refresh(row);
                // 锁后复查：锁前读到的待批列表可能已经过时，另一位管理员可能在这期间
                // 把同一张单批准/驳回/撤回了；这里必须同时重验仍是 SUBMITTED 且确实过期，
                // 否则会把已生效的申请改成 EXPIRED，留下「状态 EXPIRED + 生效行 EFFECTIVE」。
                if (!SubstitutionRequest.SUBMITTED.equals(row.getStatus()) || !overdue(row)) {
                    continue;
                }
                expire(row, viewer.getUsername());
                count++;
            }
            return count;
        });
        return expired == null ? 0 : expired;
    }

    private boolean overdue(SubstitutionRequest request) {
        Instant deadline;
        try {
            deadline = LessonDeadline.earliest(List.of(new LessonDeadline.ChangedSlot(
                    request.getEffectiveDate(),
                    SubstitutionLookups.periodStart(periods, request.getPeriodId()))));
        } catch (ApiException | LessonDeadline.MissingStartTimeException ex) {
            // 课节时间缺失等异常留给批准路径给出明确原因，这里不猜、不作废。
            return false;
        }
        return !LessonDeadline.canConfirm(clock.instant(), deadline);
    }

    private AppUser requireApprover(String username) {
        AppUser user = requireCurrentUser(username);
        if (!users.hasPermission(user, Permissions.SUBSTITUTION_APPROVE)) {
            throw ApiException.forbidden("只有管理员可以审批调代申请");
        }
        return user;
    }

    /** 锁后重新读取账号，不能继续信任权限检查前的一级缓存快照。 */
    private AppUser requireCurrentUser(String username) {
        AppUser user = users.getByUsername(username);
        entityManager.refresh(user);
        if (!user.isEnabled()) {
            throw ApiException.forbidden("账号已停用");
        }
        return user;
    }

    /** 学期行锁 → 申请行锁 → 刷新，三步顺序固定。 */
    private SubstitutionRequest lockRequest(Long requestId) {
        SubstitutionRequest located = SubstitutionLookups.requireRequest(requests, requestId);
        effects.lockSemester(located.getSemesterId());
        SubstitutionRequest locked = requests.findWithLockById(requestId)
                .orElseThrow(() -> ApiException.notFound("调代申请不存在: " + requestId));
        // 锁前载入的实例可能已被另一位管理员处理；锁后必须用数据库里的最新状态。
        entityManager.refresh(locked);
        return locked;
    }

    private static void requireStillPending(SubstitutionRequest request) {
        if (!SubstitutionRequest.SUBMITTED.equals(request.getStatus())) {
            throw ApiException.conflict("该申请已被处理（当前状态 " + request.getStatus() + "），请刷新列表");
        }
    }

    private PlanSnapshot.ScheduledLesson currentInstance(
            PlanSnapshot snapshot, SubstitutionRequest request) {
        return snapshot.scheduleOn(request.getEffectiveDate()).lessons().stream()
                .filter(lesson -> lesson.entryId() == request.getEntryId())
                .findFirst()
                .orElseThrow(() -> ApiException.conflict(
                        "该日期没有这节课（可能已被调走或当天不是授课日），不能批准"));
    }

    /**
     * 提交后课表可能已变：批准前核对请求记录的「原教师 + 节次」仍是当前实例的实际安排，
     * 不一致就让申请方撤回重提，而不是把代课挂到别的老师/节次上。
     */
    private static void requireInstanceUnchanged(
            SubstitutionRequest request, PlanSnapshot.ScheduledLesson current) {
        if (request.getPreviousTeacherId() != null
                && !request.getPreviousTeacherId().equals(current.teacherId())) {
            throw ApiException.conflict("当前课表的任课教师已变化，请让老师撤回后重新提交");
        }
        if (request.getPeriodId() != null && !request.getPeriodId().equals(current.periodId())) {
            throw ApiException.conflict("当前课表的节次已变化，请让老师撤回后重新提交");
        }
    }

    /** 教师有效性按教师档案判定：存在、未软删、active；不拿账号启用状态顶替。 */
    private void requireUsableTeacher(Long teacherId) {
        Teacher teacher = teachers.findById(teacherId).orElse(null);
        if (teacher == null || teacher.getDeletedAt() != null || !teacher.isActive()) {
            throw ApiException.conflict("代课教师当前不可用（不存在、已停用或已删除），请换人后批准");
        }
    }

    /** 撞课校验与提交用的是同一套独立校验器；失败只拒绝，不写失败告警通知。 */
    private void verifyCover(SubstitutionRequest request, PlanSnapshot.ScheduledLesson current,
                             Long substituteTeacherId, PlanSnapshot snapshot) {
        Period targetPeriod = periods.findById(current.periodId()).orElse(null);
        if (targetPeriod == null) {
            throw ApiException.notFound("目标节次不存在");
        }
        PlanMove move = new PlanMove(
                request.getEntryId(), request.getAdminClassId(), request.getCourseId(),
                substituteTeacherId,
                current.periodId(), current.dayOfWeek(), current.periodNo(),
                request.getEffectiveDate(),
                targetPeriod.getId(), targetPeriod.getDayOfWeek(), targetPeriod.getPeriodNo(),
                request.getEffectiveDate(), 0);
        PlanVerifier.Result verification = PlanVerifier.verify(
                snapshot, List.of(move), PlanVerifier.Kind.COVER, -1);
        if (!verification.passed()) {
            String detail = verification.issues().stream()
                    .limit(5)
                    .map(issue -> issue.code() + ": " + issue.message())
                    .collect(Collectors.joining("；"));
            throw ApiException.conflict("代课未通过校验，整单不生效：" + detail);
        }
    }
}
