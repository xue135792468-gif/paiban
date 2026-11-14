package com.paiban.substitution.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionHistoryRepository;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.substitution.plan.PlanMove;
import com.paiban.substitution.plan.PlanSnapshot;
import com.paiban.substitution.plan.PlanVerifier;
import com.paiban.substitution.web.SubstitutionDtos.CreateSubstitutionRequest;
import com.paiban.substitution.web.SubstitutionDtos.HistoryDto;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionRequestDto;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Request lifecycle and cover approval (one teacher replaces another, same slot). */
@Service
public class SubstitutionRequestService {

    private final SubstitutionRequestRepository requests;
    private final SubstitutionHistoryRepository history;
    private final AuditLogRepository auditLogs;
    private final SubstitutionApprovalService approvals;
    private final SubstitutionRepository substitutions;
    private final TimetableEntryRepository entries;
    private final AdminClassRepository adminClasses;
    private final TeacherRepository teachers;
    private final PeriodRepository periods;
    private final SemesterService semesters;
    private final UserService users;
    private final PlanSnapshotFactory snapshots;
    private final SubstitutionAuditService audits;
    private final SubstitutionEffects effects;
    private final SubstitutionMapper mapper;
    private final Clock clock;

    public SubstitutionRequestService(
            SubstitutionRequestRepository requests,
            SubstitutionHistoryRepository history,
            AuditLogRepository auditLogs,
            SubstitutionApprovalService approvals,
            SubstitutionRepository substitutions,
            TimetableEntryRepository entries,
            AdminClassRepository adminClasses,
            TeacherRepository teachers,
            PeriodRepository periods,
            SemesterService semesters,
            UserService users,
            PlanSnapshotFactory snapshots,
            SubstitutionAuditService audits,
            SubstitutionEffects effects,
            SubstitutionMapper mapper,
            Clock clock) {
        this.requests = requests;
        this.history = history;
        this.auditLogs = auditLogs;
        this.approvals = approvals;
        this.substitutions = substitutions;
        this.entries = entries;
        this.adminClasses = adminClasses;
        this.teachers = teachers;
        this.periods = periods;
        this.semesters = semesters;
        this.users = users;
        this.snapshots = snapshots;
        this.audits = audits;
        this.effects = effects;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public SubstitutionRequestDto create(String username, CreateSubstitutionRequest body) {
        Long semesterId = semesters.requireCurrent().getId();
        TimetableEntry entry = entries.findById(body.entryId())
                .orElseThrow(() -> ApiException.notFound("课节不存在: " + body.entryId()));
        if (!entry.getSemesterId().equals(semesterId)) {
            throw ApiException.badRequest("该课节不属于当前学期");
        }
        AppUser user = users.getByUsername(username);
        // 票 03 权限收紧：代人提交（含直接生效分支）需要「全校课表查看 + 提交 + 审批」组合；
        // 仅有 SUBSTITUTION_VIEW（查看全部调代记录）不能代发。
        boolean admin = users.hasAllPermissions(user,
                AdminSubstitutionService.MANAGER_PERMISSIONS);
        if (!admin && !Objects.equals(user.getTeacherId(), entry.getTeacherId())) {
            throw ApiException.forbidden("只能为自己的课提交调代申请");
        }
        int weekday = body.effectiveDate().getDayOfWeek().getValue();
        if (weekday != entry.getDayOfWeek()) {
            throw ApiException.badRequest("所选日期的星期与课节不一致");
        }
        com.paiban.foundation.domain.Semester semester = semesters.require(semesterId);
        if (body.effectiveDate().isBefore(semester.getStartDate())
                || body.effectiveDate().isAfter(semester.getEndDate())) {
            throw ApiException.badRequest("调代日期不在学期范围内");
        }
        String type = normalizeType(body.type());
        Long targetPeriodId = null;
        if (Substitution.TYPE_SWAP.equals(type)) {
            throw ApiException.badRequest("调课请在管理端调课工作台发起");
        } else if (body.targetPeriodId() != null) {
            throw ApiException.badRequest("代课不能指定目标节次");
        }
        if (body.expectedSubstituteTeacherId() == null) {
            throw ApiException.badRequest("请指定代课教师");
        }
        SubstitutionLookups.requireTeacher(teachers, body.expectedSubstituteTeacherId());

        // 先拿学期行锁，再读当前安排与校验：排队期间课表可能已被别人改动，
        // 一切判定都以锁后状态为准。
        effects.lockSemester(semesterId);
        // 同一实例同一时间最多一笔「待审批」申请。查重必须在学期行锁之后：
        // 锁前查询会让两个并发提交都看到「没有待批」然后各写一张。
        Long raced = SubstitutionLookups.activeRequestFor(requests, entry.getId(), body.effectiveDate());
        if (raced != null) {
            throw ApiException.conflict("该课节已有待处理的申请，不能重复提交");
        }
        PlanSnapshot snapshot = snapshots.build(semesterId);
        // 该实例当前的实际安排（含已生效的调课/代课）：再次调整以它为起点，
        // 代课只换教师，因此沿用它的槽位（被调课挪过日期/节次时尤其重要）。
        PlanSnapshot.ScheduledLesson current = snapshot.scheduleOn(body.effectiveDate()).lessons().stream()
                .filter(lesson -> lesson.entryId() == entry.getId())
                .findFirst()
                .orElseThrow(() -> ApiException.conflict(
                        "该日期没有这节课（可能已被调走或当天不是授课日），请核对日期"));

        SubstitutionRequest request = new SubstitutionRequest();
        request.setSemesterId(semesterId);
        request.setEntryId(entry.getId());
        request.setAdminClassId(entry.getAdminClassId());
        request.setCourseId(entry.getCourseId());
        request.setOriginalTeacherId(entry.getTeacherId());
        request.setRequesterTeacherId(user.getTeacherId());
        // 真实操作账号：老师自助时就是本人账号，管理员代发时是管理员账号。
        request.setOperatorUserId(user.getId());
        request.setPreviousTeacherId(current.teacherId());
        request.setEffectiveDate(body.effectiveDate());
        request.setPeriodId(current.periodId());
        request.setDayOfWeek(current.dayOfWeek());
        request.setPeriodNo(current.periodNo());
        request.setReason(body.reason());
        request.setExpectedSubstituteTeacherId(body.expectedSubstituteTeacherId());
        request.setType(type);
        request.setTargetPeriodId(targetPeriodId);
        request.setStatus(SubstitutionRequest.SUBMITTED);
        request.setCreatedAt(Instant.now());
        request.setUpdatedAt(Instant.now());
        SubstitutionRequest saved = requests.save(request);
        effects.recordHistory(saved.getId(), null, SubstitutionRequest.SUBMITTED, username, null);

        verifyCoverOrReject(username, saved, body.expectedSubstituteTeacherId(), snapshot);
        // 提交同样受开课截止约束（与管理端 confirmDirect 一致）；提交后停在「待审批」，
        // 不产生生效行、不写生效历史、不发通知——批准时按当前课表重验后才生效。
        new DeadlineChecker(clock::instant).requireBeforeDeadline(
                LessonDeadline.earliest(List.of(new LessonDeadline.ChangedSlot(
                        body.effectiveDate(),
                        SubstitutionLookups.periodStart(periods, current.periodId())))),
                SubstitutionMapper.label(current.dayOfWeek(), current.periodNo())
                        + "（" + body.effectiveDate() + "）");
        auditLogs.save(new AuditLog(username, "SUBSTITUTION_REQUEST_SUBMITTED", "SUBSTITUTION",
                "requestId=" + saved.getId() + ", entryId=" + saved.getEntryId()
                        + ", lessonDate=" + saved.getEffectiveDate()
                        + ", requesterTeacherId=" + saved.getRequesterTeacherId()
                        + ", expectedSubstituteTeacherId=" + saved.getExpectedSubstituteTeacherId(),
                1, 0));
        return mapper.toDto(saved);
    }

    @Transactional
    public List<SubstitutionRequestDto> list(
            String username, Long semesterId, String status, boolean mineOnly) {
        AppUser user = users.getByUsername(username);
        boolean admin = users.hasPermission(user, com.paiban.identity.domain.Permissions.SUBSTITUTION_VIEW);
        Long resolved = semesterId != null ? semesterId : semesters.requireCurrent().getId();
        // 列表可能触发作废，所以是普通可写事务：先把过了开课时刻的待批单作废，
        // 再按最新状态重查，避免刚变 EXPIRED 的行仍出现在「待审批」里。
        approvals.expireOverdue(resolved, user);
        List<SubstitutionRequest> result;
        if (mineOnly || !admin) {
            // 本人列表认真实操作账号（旧行回退到非空教师关系），不再只按教师档案查。
            result = requests.findBySemesterIdOrderByCreatedAtDesc(resolved).stream()
                    .filter(request -> SubstitutionLookups.isOwn(request, user))
                    .toList();
        } else if (status != null && !status.isBlank()) {
            result = requests.findBySemesterIdAndStatusOrderByCreatedAtDesc(resolved, status);
        } else {
            result = requests.findBySemesterIdOrderByCreatedAtDesc(resolved);
        }
        if (status != null && !status.isBlank() && (mineOnly || !admin)) {
            result = result.stream().filter(request -> status.equals(request.getStatus())).toList();
        }
        return result.stream().map(mapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public SubstitutionRequestDto detail(String username, Long requestId) {
        SubstitutionRequest request = SubstitutionLookups.requireRequest(requests, requestId);
        AppUser user = users.getByUsername(username);
        if (!users.hasPermission(user, com.paiban.identity.domain.Permissions.SUBSTITUTION_VIEW)
                && !SubstitutionLookups.isOwn(request, user)) {
            throw ApiException.forbidden("不能查看他人的调代申请");
        }
        return mapper.toDto(request);
    }

    @Transactional(readOnly = true)
    public List<HistoryDto> history(String username, Long requestId) {
        SubstitutionRequest request = SubstitutionLookups.requireRequest(requests, requestId);
        AppUser user = users.getByUsername(username);
        boolean requester = SubstitutionLookups.isOwn(request, user)
                || (user.getTeacherId() != null
                        && user.getTeacherId().equals(request.getOriginalTeacherId()));
        boolean substitute = user.getTeacherId() != null
                && substitutions.findByRequestId(requestId).stream()
                        .anyMatch(item -> user.getTeacherId().equals(item.getSubstituteTeacherId()));
        // 班主任历史分支必须同时持有「本班调代记录」权限；身份字段本身不构成授权。
        boolean headTeacher = users.hasPermission(
                        user, com.paiban.identity.domain.Permissions.SUBSTITUTION_VIEW_CLASS)
                && user.getTeacherId() != null
                && adminClasses.findById(request.getAdminClassId())
                        .map(adminClass -> user.getTeacherId().equals(adminClass.getHeadTeacherId()))
                        .orElse(false);
        if (!users.hasPermission(user, com.paiban.identity.domain.Permissions.SUBSTITUTION_VIEW)
                && !requester && !substitute && !headTeacher) {
            throw ApiException.forbidden("不能查看他人的调代流转记录");
        }
        return history.findByRequestIdOrderByCreatedAtAsc(requestId).stream()
                .map(item -> new HistoryDto(
                        item.getId(), item.getFromStatus(), item.getToStatus(), item.getActor(),
                        item.getComment(), item.getCreatedAt()))
                .toList();
    }

    private void verifyCoverOrReject(String username, SubstitutionRequest request,
                                     Long substituteTeacherId, PlanSnapshot snapshot) {
        TimetableEntry entry = request.getEntryId() == null ? null
                : entries.findById(request.getEntryId()).orElse(null);
        if (entry == null) {
            throw ApiException.conflict("课表已撤回，该调代申请对应的课节已失效，请重新提交");
        }
        Period targetPeriod = periods.findById(request.getPeriodId()).orElse(null);
        if (targetPeriod == null) {
            throw ApiException.notFound("目标节次不存在");
        }
        PlanMove move = new PlanMove(
                request.getEntryId(), request.getAdminClassId(), request.getCourseId(),
                substituteTeacherId,
                request.getPeriodId(), request.getDayOfWeek(), request.getPeriodNo(),
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
            // 失败审计保留，但不发告警通知：票 05 的提交/审批路径不新增任何持锁外发。
            audits.failure(username, "SUBSTITUTION_COVER_VERIFY_FAILED", "SUBSTITUTION", detail);
            throw ApiException.conflict("代课未通过校验，整单不生效：" + detail);
        }
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return Substitution.TYPE_COVER;
        }
        String normalized = type.trim().toUpperCase();
        if (!Substitution.TYPE_SWAP.equals(normalized) && !Substitution.TYPE_COVER.equals(normalized)) {
            throw ApiException.badRequest("调代类型只能是 SWAP 或 COVER");
        }
        return normalized;
    }
}
