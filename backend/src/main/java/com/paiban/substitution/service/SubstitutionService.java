package com.paiban.substitution.service;

import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.Room;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import com.paiban.notification.service.NotificationService;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionHistory;
import com.paiban.substitution.domain.SubstitutionHistoryRepository;
import com.paiban.substitution.domain.SubstitutionPlan;
import com.paiban.substitution.domain.SubstitutionPlanMove;
import com.paiban.substitution.domain.SubstitutionPlanMoveRepository;
import com.paiban.substitution.domain.SubstitutionPlanRepository;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.substitution.plan.CandidatePlan;
import com.paiban.substitution.plan.PlanDiagnosis;
import com.paiban.substitution.plan.PlanMove;
import com.paiban.substitution.plan.PlanSnapshot;
import com.paiban.substitution.plan.PlanVerifier;
import com.paiban.substitution.plan.PlanningResult;
import com.paiban.substitution.plan.RankingPreset;
import com.paiban.substitution.plan.SwapPlanner;
import com.paiban.substitution.service.PlanSnapshotFactory.SwapPlannerOptions;
import com.paiban.substitution.web.SubstitutionDtos.CreateSubstitutionRequest;
import com.paiban.substitution.web.SubstitutionDtos.DecisionRequest;
import com.paiban.substitution.web.SubstitutionDtos.DiagnosisDto;
import com.paiban.substitution.web.SubstitutionDtos.GeneratePlansResponse;
import com.paiban.substitution.web.SubstitutionDtos.HistoryDto;
import com.paiban.substitution.web.SubstitutionDtos.PlanMoveDto;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionPlanDto;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionRecordDto;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionRequestDto;
import com.paiban.substitution.web.SubstitutionDtos.TeacherStatsDto;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubstitutionService {

    private static final String[] DAY_NAMES = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final Duration PLAN_TTL = Duration.ofMinutes(30);

    private final SubstitutionRequestRepository requests;
    private final SubstitutionHistoryRepository history;
    private final SubstitutionRepository substitutions;
    private final SubstitutionPlanRepository plans;
    private final SubstitutionPlanMoveRepository planMoves;
    private final TimetableEntryRepository entries;
    private final AdminClassRepository adminClasses;
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final RoomRepository rooms;
    private final PeriodRepository periods;
    private final SemesterService semesters;
    private final UserService users;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final PlanSnapshotFactory snapshots;
    private final SubstitutionAuditService audits;
    private final AuditLogRepository auditLogs;

    public SubstitutionService(
            SubstitutionRequestRepository requests,
            SubstitutionHistoryRepository history,
            SubstitutionRepository substitutions,
            SubstitutionPlanRepository plans,
            SubstitutionPlanMoveRepository planMoves,
            TimetableEntryRepository entries,
            AdminClassRepository adminClasses,
            CourseRepository courses,
            TeacherRepository teachers,
            RoomRepository rooms,
            PeriodRepository periods,
            SemesterService semesters,
            UserService users,
            SettingsService settings,
            NotificationService notifications,
            PlanSnapshotFactory snapshots,
            SubstitutionAuditService audits,
            AuditLogRepository auditLogs) {
        this.requests = requests;
        this.history = history;
        this.substitutions = substitutions;
        this.plans = plans;
        this.planMoves = planMoves;
        this.entries = entries;
        this.adminClasses = adminClasses;
        this.courses = courses;
        this.teachers = teachers;
        this.rooms = rooms;
        this.periods = periods;
        this.semesters = semesters;
        this.users = users;
        this.settings = settings;
        this.notifications = notifications;
        this.snapshots = snapshots;
        this.audits = audits;
        this.auditLogs = auditLogs;
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
        boolean admin = users.hasPermission(user, com.paiban.identity.domain.Permissions.SUBSTITUTION_VIEW);
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
        if (substitutions.findFirstByEntryIdAndEffectiveDateAndStatus(
                entry.getId(), body.effectiveDate(), Substitution.EFFECTIVE).isPresent()) {
            throw ApiException.conflict("该课节当天已有生效的调代，不能重复申请");
        }
        boolean duplicate = requests.findByEntryIdAndEffectiveDate(entry.getId(), body.effectiveDate())
                .stream()
                .anyMatch(existing -> !SubstitutionRequest.REJECTED.equals(existing.getStatus())
                        && !SubstitutionRequest.CANCELLED.equals(existing.getStatus()));
        if (duplicate) {
            throw ApiException.conflict("该课节当天已有待处理或已生效的申请，不能重复提交");
        }

        String type = normalizeType(body.type());
        Long targetPeriodId = null;
        if (Substitution.TYPE_SWAP.equals(type)) {
            if ("direct".equalsIgnoreCase(settings.approvalMode())) {
                throw ApiException.badRequest("调课必须经教务审批，不能使用直接生效模式");
            }
            if (body.targetPeriodId() == null) {
                throw ApiException.badRequest("调课必须选择目标节次");
            }
            Period target = periods.findById(body.targetPeriodId())
                    .orElseThrow(() -> ApiException.notFound("目标节次不存在: " + body.targetPeriodId()));
            if (!target.getSemesterId().equals(semesterId)) {
                throw ApiException.badRequest("目标节次不属于当前学期");
            }
            if (body.expectedSubstituteTeacherId() != null) {
                throw ApiException.badRequest("调课不需要指定代课教师");
            }
            targetPeriodId = target.getId();
        } else if (body.targetPeriodId() != null) {
            throw ApiException.badRequest("代课不能指定目标节次");
        }

        String mode = settings.approvalMode();
        boolean direct = "direct".equalsIgnoreCase(mode);
        if (direct && body.expectedSubstituteTeacherId() == null) {
            throw ApiException.badRequest("直接生效模式必须指定代课教师");
        }
        if (body.expectedSubstituteTeacherId() != null) {
            requireTeacher(body.expectedSubstituteTeacherId());
        }

        SubstitutionRequest request = new SubstitutionRequest();
        request.setSemesterId(semesterId);
        request.setEntryId(entry.getId());
        request.setAdminClassId(entry.getAdminClassId());
        request.setCourseId(entry.getCourseId());
        request.setOriginalTeacherId(entry.getTeacherId());
        request.setRequesterTeacherId(user.getTeacherId());
        request.setEffectiveDate(body.effectiveDate());
        request.setPeriodId(entry.getPeriodId());
        request.setDayOfWeek(entry.getDayOfWeek());
        request.setPeriodNo(entry.getPeriodNo());
        request.setReason(body.reason());
        request.setExpectedSubstituteTeacherId(body.expectedSubstituteTeacherId());
        request.setType(type);
        request.setTargetPeriodId(targetPeriodId);
        request.setApprovalMode(direct && Substitution.TYPE_COVER.equals(type) ? "direct" : "approval");
        request.setStatus(SubstitutionRequest.SUBMITTED);
        request.setCreatedAt(Instant.now());
        request.setUpdatedAt(Instant.now());
        SubstitutionRequest saved = requests.save(request);
        recordHistory(saved.getId(), null, SubstitutionRequest.SUBMITTED, username, null);

        if (direct && Substitution.TYPE_COVER.equals(type)) {
            verifyCoverOrReject(username, saved, body.expectedSubstituteTeacherId(), null, null);
            saved.setStatus(SubstitutionRequest.EFFECTIVE);
            saved.setUpdatedAt(Instant.now());
            recordHistory(saved.getId(), SubstitutionRequest.SUBMITTED, SubstitutionRequest.EFFECTIVE, username, null);
            Substitution substitution = createSubstitution(saved, body.expectedSubstituteTeacherId());
            notifyEffective(saved, substitution);
        } else {
            saved.setStatus(SubstitutionRequest.PENDING_APPROVAL);
            saved.setUpdatedAt(Instant.now());
            recordHistory(
                    saved.getId(), SubstitutionRequest.SUBMITTED, SubstitutionRequest.PENDING_APPROVAL, username, null);
        }
        return toDto(saved);
    }

    @Transactional
    public GeneratePlansResponse generatePlans(Long requestId, String username) {
        SubstitutionRequest request = requireRequest(requestId);
        if (!Substitution.TYPE_SWAP.equals(typeOf(request))) {
            throw ApiException.badRequest("只有调课申请可以生成置换方案");
        }
        if (!SubstitutionRequest.PENDING_APPROVAL.equals(request.getStatus())) {
            throw ApiException.conflict("该申请当前状态为 " + request.getStatus() + "，不能生成方案");
        }
        PlanSnapshot snapshot = snapshots.build(request.getSemesterId());
        SwapPlannerOptions options = snapshots.swapOptions();
        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(
                        request.getAdminClassId(), request.getEntryId(), request.getEffectiveDate(),
                        request.getTargetPeriodId()),
                SwapPlanner.Options.of(options.windowDays(), options.maxTeachers(), options.preset()));

        plans.findByRequestIdAndStatusOrderByIdAsc(requestId, SubstitutionPlan.PROPOSED)
                .forEach(old -> {
                    planMoves.deleteByPlanId(old.getId());
                    plans.delete(old);
                });

        List<SubstitutionPlan> saved = new ArrayList<>();
        String hash = snapshot.snapshotHash();
        Instant now = Instant.now();
        for (CandidatePlan candidate : result.candidates().stream().limit(3).toList()) {
            SubstitutionPlan plan = new SubstitutionPlan();
            plan.setRequestId(requestId);
            plan.setSemesterId(request.getSemesterId());
            plan.setSnapshotHash(hash);
            plan.setRankingPreset(options.preset().code());
            plan.setStatus(SubstitutionPlan.PROPOSED);
            plan.setImpactDays(candidate.impactDays());
            plan.setImpactTeachers(candidate.impactTeachers());
            plan.setSoftPenalty(candidate.softPenalty());
            plan.setCycleLength(candidate.cycleLength());
            plan.setSameWeek(candidate.sameWeek());
            plan.setCreatedAt(now);
            plan.setExpiresAt(now.plus(PLAN_TTL));
            SubstitutionPlan stored = plans.save(plan);
            for (PlanMove move : candidate.moves()) {
                planMoves.save(toMoveEntity(stored.getId(), move));
            }
            saved.add(stored);
        }
        auditLogs.save(new AuditLog(
                username, "SUBSTITUTION_PLAN_GENERATED", "SUBSTITUTION_PLAN",
                "request=" + requestId + ", preset=" + options.preset().code()
                        + ", plans=" + saved.size() + ", replaced=previous-proposed",
                saved.size(), 0));
        return new GeneratePlansResponse(
                requestId,
                options.preset().code(),
                options.preset().label(),
                saved.stream().map(this::toPlanDto).toList(),
                result.diagnoses().stream()
                        .map(diagnosis -> new DiagnosisDto(
                                diagnosis.code(), diagnosis.message(),
                                diagnosis.entryId(), diagnosis.periodId()))
                        .toList());
    }

    @Transactional(readOnly = true)
    public List<SubstitutionPlanDto> plansFor(Long requestId, String username) {
        SubstitutionRequest request = requireRequest(requestId);
        AppUser user = users.getByUsername(username);
        boolean approver = users.hasPermission(user, com.paiban.identity.domain.Permissions.SUBSTITUTION_APPROVE);
        if (!approver && !Objects.equals(user.getTeacherId(), request.getRequesterTeacherId())) {
            throw ApiException.forbidden("不能查看他人的调课方案");
        }
        return plans.findByRequestIdOrderByIdAsc(requestId).stream().map(this::toPlanDto).toList();
    }

    @Transactional
    public SubstitutionRequestDto approvePlan(Long planId, String username, String comment) {
        SubstitutionPlan plan = plans.findById(planId)
                .orElseThrow(() -> ApiException.notFound("调课方案不存在: " + planId));
        SubstitutionRequest request = requireRequest(plan.getRequestId());
        if (!SubstitutionPlan.PROPOSED.equals(plan.getStatus())) {
            throw ApiException.conflict("该方案当前状态为 " + plan.getStatus() + "，不能批准");
        }
        if (!SubstitutionRequest.PENDING_APPROVAL.equals(request.getStatus())) {
            throw ApiException.conflict("该申请当前状态为 " + request.getStatus() + "，不能批准方案");
        }
        if (Instant.now().isAfter(plan.getExpiresAt())) {
            audits.expirePlan(planId, SubstitutionPlan.EXPIRED, username,
                    "SUBSTITUTION_PLAN_EXPIRED", "方案 " + planId + " 已超过 30 分钟有效期");
            throw ApiException.conflict("方案已过期，请重新生成");
        }
        PlanSnapshot snapshot = snapshots.build(request.getSemesterId());
        if (!snapshot.snapshotHash().equals(plan.getSnapshotHash())) {
            audits.expirePlan(planId, SubstitutionPlan.EXPIRED, username,
                    "SUBSTITUTION_PLAN_STALE",
                    "方案 " + planId + " 生成的课表快照已变化，拒绝生效");
            throw ApiException.conflict("课表已变化，旧方案不能生效，请重新生成");
        }
        List<SubstitutionPlanMove> moveEntities = planMoves.findByPlanIdOrderByMoveOrderAsc(planId);
        List<PlanMove> moves = moveEntities.stream().map(this::toPlanMove).toList();
        PlanVerifier.Result verification = PlanVerifier.verify(
                snapshot, moves, PlanVerifier.Kind.SWAP, plan.getSoftPenalty());
        if (!verification.passed()) {
            String detail = verification.issues().stream()
                    .limit(5)
                    .map(issue -> issue.code() + ": " + issue.message())
                    .collect(Collectors.joining("；"));
            audits.planFailure(planId, SubstitutionPlan.REJECTED, username,
                    "SUBSTITUTION_PLAN_VERIFY_FAILED", detail, true);
            throw ApiException.conflict("方案未通过校验，整单不生效：" + detail);
        }

        Instant now = Instant.now();
        AppUser approver = users.getByUsername(username);
        for (PlanMove move : moves) {
            Substitution substitution = new Substitution();
            substitution.setSemesterId(request.getSemesterId());
            substitution.setRequestId(request.getId());
            substitution.setEntryId(move.entryId());
            substitution.setEffectiveDate(move.fromDate());
            substitution.setOriginalTeacherId(move.teacherId());
            substitution.setSubstituteTeacherId(move.teacherId());
            substitution.setNewRoomId(move.roomId());
            substitution.setNewPeriodId(move.toPeriodId());
            substitution.setNewDayOfWeek(move.toDayOfWeek());
            substitution.setNewPeriodNo(move.toPeriodNo());
            substitution.setType(Substitution.TYPE_SWAP);
            substitution.setPlanId(plan.getId());
            substitution.setStatus(Substitution.EFFECTIVE);
            substitution.setCreatedAt(now);
            substitutions.save(substitution);
        }
        request.setStatus(SubstitutionRequest.EFFECTIVE);
        request.setApproverId(approver.getId());
        request.setApprovedAt(now);
        request.setApprovalComment(comment);
        request.setUpdatedAt(now);
        recordHistory(request.getId(), SubstitutionRequest.PENDING_APPROVAL,
                SubstitutionRequest.EFFECTIVE, username, comment);
        plan.setStatus(SubstitutionPlan.APPROVED);
        plan.setApprovedAt(now);
        plan.setApprovedBy(username);
        auditLogs.save(new AuditLog(
                username, "SUBSTITUTION_PLAN_APPROVED", "SUBSTITUTION_PLAN",
                "request=" + request.getId() + ", plan=" + plan.getId()
                        + ", moves=" + moves.size() + ", teachers=" + plan.getImpactTeachers(),
                moves.size(), 0));
        notifySwap(request, moves);
        return toDto(request);
    }

    @Transactional
    public SubstitutionRequestDto approve(Long requestId, String username, DecisionRequest decision) {
        SubstitutionRequest request = requireRequest(requestId);
        if (!SubstitutionRequest.PENDING_APPROVAL.equals(request.getStatus())) {
            throw ApiException.conflict("该申请当前状态为 " + request.getStatus() + "，不能审批");
        }
        if (Substitution.TYPE_SWAP.equals(typeOf(request))) {
            throw ApiException.badRequest("调课请生成方案后整单批准：POST /substitution-plans/{id}/approve");
        }
        Long substituteTeacherId = decision != null && decision.substituteTeacherId() != null
                ? decision.substituteTeacherId()
                : request.getExpectedSubstituteTeacherId();
        if (substituteTeacherId == null) {
            throw ApiException.badRequest("请指定代课教师");
        }
        requireTeacher(substituteTeacherId);
        if (substituteTeacherId.equals(request.getOriginalTeacherId())) {
            throw ApiException.badRequest("代课教师不能与原任课教师相同");
        }
        Long targetPeriodId = decision != null && decision.newPeriodId() != null
                ? decision.newPeriodId() : request.getPeriodId();
        boolean busy = entries.findBySemesterIdAndTeacherId(request.getSemesterId(), substituteTeacherId).stream()
                .anyMatch(entry -> entry.getPeriodId().equals(targetPeriodId));
        if (!busy) {
            for (Substitution existing : substitutions.findBySubstituteTeacherIdAndEffectiveDateAndStatus(
                    substituteTeacherId, request.getEffectiveDate(), Substitution.EFFECTIVE)) {
                SubstitutionRequest other = requests.findById(existing.getRequestId()).orElse(null);
                Long otherPeriod = existing.getNewPeriodId() != null
                        ? existing.getNewPeriodId()
                        : other == null ? null : other.getPeriodId();
                if (targetPeriodId.equals(otherPeriod)) {
                    busy = true;
                    break;
                }
            }
        }
        if (busy) {
            throw ApiException.conflict("代课教师在该时段已有课，不能安排代课");
        }
        if (substitutions.findFirstByEntryIdAndEffectiveDateAndStatus(
                request.getEntryId(), request.getEffectiveDate(), Substitution.EFFECTIVE).isPresent()) {
            throw ApiException.conflict("该课节当天已有生效的调代，不能重复生效");
        }
        verifyCoverOrReject(username, request, substituteTeacherId,
                decision == null ? null : decision.newRoomId(),
                decision == null ? null : decision.newPeriodId());

        AppUser approver = users.getByUsername(username);
        request.setStatus(SubstitutionRequest.APPROVED);
        request.setApproverId(approver.getId());
        request.setApprovedAt(Instant.now());
        request.setApprovalComment(decision == null ? null : decision.comment());
        request.setUpdatedAt(Instant.now());
        recordHistory(
                requestId,
                SubstitutionRequest.PENDING_APPROVAL,
                SubstitutionRequest.APPROVED,
                username,
                decision == null ? null : decision.comment());

        Substitution substitution = createSubstitution(request, substituteTeacherId);
        if (decision != null) {
            if (decision.newRoomId() != null) {
                if (!rooms.existsById(decision.newRoomId())) {
                    throw ApiException.notFound("教室不存在: " + decision.newRoomId());
                }
                substitution.setNewRoomId(decision.newRoomId());
            }
            if (decision.newPeriodId() != null) {
                Period newPeriod = periods.findById(decision.newPeriodId())
                        .orElseThrow(() -> ApiException.notFound("课节不存在: " + decision.newPeriodId()));
                if (!newPeriod.getSemesterId().equals(request.getSemesterId())) {
                    throw ApiException.badRequest("新课节不属于该学期");
                }
                substitution.setNewPeriodId(newPeriod.getId());
                substitution.setNewDayOfWeek(newPeriod.getDayOfWeek());
                substitution.setNewPeriodNo(newPeriod.getPeriodNo());
            }
            substitutions.save(substitution);
        }
        request.setStatus(SubstitutionRequest.EFFECTIVE);
        request.setUpdatedAt(Instant.now());
        recordHistory(
                requestId, SubstitutionRequest.APPROVED, SubstitutionRequest.EFFECTIVE, username, null);
        notifyEffective(request, substitution);
        return toDto(request);
    }

    @Transactional
    public SubstitutionRequestDto reject(Long requestId, String username, String comment) {
        SubstitutionRequest request = requireRequest(requestId);
        if (!SubstitutionRequest.PENDING_APPROVAL.equals(request.getStatus())) {
            throw ApiException.conflict("该申请当前状态为 " + request.getStatus() + "，不能驳回");
        }
        AppUser approver = users.getByUsername(username);
        request.setStatus(SubstitutionRequest.REJECTED);
        request.setApproverId(approver.getId());
        request.setApprovedAt(Instant.now());
        request.setApprovalComment(comment);
        request.setUpdatedAt(Instant.now());
        recordHistory(
                requestId,
                SubstitutionRequest.PENDING_APPROVAL,
                SubstitutionRequest.REJECTED,
                username,
                comment);
        Map<String, Object> variables = variables(request);
        variables.put("comment", comment == null ? "" : comment);
        notifications.substitutionRejected(request, variables);
        return toDto(request);
    }

    @Transactional(readOnly = true)
    public List<SubstitutionRequestDto> list(String username, Long semesterId, String status, boolean mineOnly) {
        AppUser user = users.getByUsername(username);
        boolean admin = users.hasPermission(user, com.paiban.identity.domain.Permissions.SUBSTITUTION_VIEW);
        Long resolved = semesterId != null ? semesterId : semesters.requireCurrent().getId();
        List<SubstitutionRequest> result;
        if (mineOnly || !admin) {
            if (user.getTeacherId() == null) {
                return List.of();
            }
            result = requests.findBySemesterIdAndRequesterTeacherIdOrderByCreatedAtDesc(
                    resolved, user.getTeacherId());
        } else if (status != null && !status.isBlank()) {
            result = requests.findBySemesterIdAndStatusOrderByCreatedAtDesc(resolved, status);
        } else {
            result = requests.findBySemesterIdOrderByCreatedAtDesc(resolved);
        }
        if (status != null && !status.isBlank() && (mineOnly || !admin)) {
            result = result.stream().filter(request -> status.equals(request.getStatus())).toList();
        }
        return result.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public SubstitutionRequestDto detail(String username, Long requestId) {
        SubstitutionRequest request = requireRequest(requestId);
        AppUser user = users.getByUsername(username);
        if (!users.hasPermission(user, com.paiban.identity.domain.Permissions.SUBSTITUTION_VIEW) && !Objects.equals(user.getTeacherId(), request.getRequesterTeacherId())) {
            throw ApiException.forbidden("不能查看他人的调代申请");
        }
        return toDto(request);
    }

    @Transactional(readOnly = true)
    public List<HistoryDto> history(String username, Long requestId) {
        SubstitutionRequest request = requireRequest(requestId);
        AppUser user = users.getByUsername(username);
        boolean requester = Objects.equals(user.getTeacherId(), request.getRequesterTeacherId())
                || Objects.equals(user.getTeacherId(), request.getOriginalTeacherId());
        boolean substitute = user.getTeacherId() != null
                && substitutions.findByRequestId(requestId).stream()
                        .anyMatch(item -> user.getTeacherId().equals(item.getSubstituteTeacherId()));
        boolean headTeacher = user.getTeacherId() != null
                && adminClasses.findById(request.getAdminClassId())
                        .map(adminClass -> user.getTeacherId().equals(adminClass.getHeadTeacherId()))
                        .orElse(false);
        if (!users.hasPermission(user, com.paiban.identity.domain.Permissions.SUBSTITUTION_VIEW) && !requester && !substitute && !headTeacher) {
            throw ApiException.forbidden("不能查看他人的调代流转记录");
        }
        return history.findByRequestIdOrderByCreatedAtAsc(requestId).stream()
                .map(item -> new HistoryDto(
                        item.getId(), item.getFromStatus(), item.getToStatus(), item.getActor(),
                        item.getComment(), item.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SubstitutionRecordDto> records(
            Long semesterId, Long teacherId, Long classId, LocalDate dateFrom, LocalDate dateTo, String type) {
        Long resolved = semesterId != null ? semesterId : semesters.requireCurrent().getId();
        List<Substitution> result = type != null && !type.isBlank()
                ? substitutions.findBySemesterIdAndStatusAndType(resolved, Substitution.EFFECTIVE, type)
                : substitutions.findBySemesterIdAndStatus(resolved, Substitution.EFFECTIVE);
        if (teacherId != null) {
            result = result.stream()
                    .filter(item -> teacherId.equals(item.getSubstituteTeacherId())
                            || teacherId.equals(item.getOriginalTeacherId()))
                    .toList();
        }
        if (classId != null) {
            Map<Long, TimetableEntry> entryMap = entries.findAll().stream()
                    .collect(Collectors.toMap(TimetableEntry::getId, Function.identity()));
            result = result.stream()
                    .filter(item -> {
                        TimetableEntry entry = entryMap.get(item.getEntryId());
                        return entry != null && classId.equals(entry.getAdminClassId());
                    })
                    .toList();
        }
        if (dateFrom != null) {
            result = result.stream().filter(item -> !item.getEffectiveDate().isBefore(dateFrom)).toList();
        }
        if (dateTo != null) {
            result = result.stream().filter(item -> !item.getEffectiveDate().isAfter(dateTo)).toList();
        }
        return result.stream().map(this::toRecordDto).toList();
    }

    @Transactional(readOnly = true)
    public TeacherStatsDto stats(Long teacherId) {
        requireTeacher(teacherId);
        Long semesterId = semesters.requireCurrent().getId();
        long out = substitutions.countBySemesterIdAndOriginalTeacherIdAndTypeAndStatus(
                semesterId, teacherId, Substitution.TYPE_COVER, Substitution.EFFECTIVE);
        long in = substitutions.countBySemesterIdAndSubstituteTeacherIdAndTypeAndStatus(
                semesterId, teacherId, Substitution.TYPE_COVER, Substitution.EFFECTIVE);
        long swaps = substitutions.countSwapPlans(semesterId, teacherId);
        Teacher teacher = teachers.findById(teacherId).orElse(null);
        return new TeacherStatsDto(teacherId, teacher == null ? null : teacher.getName(), out, in, swaps);
    }

    private void verifyCoverOrReject(String username, SubstitutionRequest request, Long substituteTeacherId,
                                     Long newRoomId, Long newPeriodId) {
        TimetableEntry entry = entries.findById(request.getEntryId())
                .orElseThrow(() -> ApiException.notFound("课节不存在: " + request.getEntryId()));
        Period targetPeriod = newPeriodId == null
                ? periods.findById(request.getPeriodId()).orElse(null)
                : periods.findById(newPeriodId).orElse(null);
        if (targetPeriod == null) {
            throw ApiException.notFound("目标节次不存在");
        }
        Long roomId = newRoomId != null ? newRoomId : entry.getRoomId();
        PlanMove move = new PlanMove(
                request.getEntryId(), request.getAdminClassId(), request.getCourseId(),
                substituteTeacherId, roomId,
                request.getPeriodId(), request.getDayOfWeek(), request.getPeriodNo(),
                request.getEffectiveDate(),
                targetPeriod.getId(), targetPeriod.getDayOfWeek(), targetPeriod.getPeriodNo(),
                request.getEffectiveDate(), 0);
        PlanSnapshot snapshot = snapshots.build(request.getSemesterId());
        PlanVerifier.Result verification = PlanVerifier.verify(
                snapshot, List.of(move), PlanVerifier.Kind.COVER, -1);
        if (!verification.passed()) {
            String detail = verification.issues().stream()
                    .limit(5)
                    .map(issue -> issue.code() + ": " + issue.message())
                    .collect(Collectors.joining("；"));
            audits.failure(username, "SUBSTITUTION_COVER_VERIFY_FAILED", "SUBSTITUTION", detail, true);
            throw ApiException.conflict("代课未通过校验，整单不生效：" + detail);
        }
    }

    private Substitution createSubstitution(SubstitutionRequest request, Long substituteTeacherId) {
        Substitution substitution = new Substitution();
        substitution.setSemesterId(request.getSemesterId());
        substitution.setRequestId(request.getId());
        substitution.setEntryId(request.getEntryId());
        substitution.setEffectiveDate(request.getEffectiveDate());
        substitution.setOriginalTeacherId(request.getOriginalTeacherId());
        substitution.setSubstituteTeacherId(substituteTeacherId);
        substitution.setType(typeOf(request));
        substitution.setStatus(Substitution.EFFECTIVE);
        substitution.setCreatedAt(Instant.now());
        return substitutions.save(substitution);
    }

    private void notifyEffective(SubstitutionRequest request, Substitution substitution) {
        notifications.substitutionEffective(request, substitution, variables(request));
    }

    private void notifySwap(SubstitutionRequest request, List<PlanMove> moves) {
        Map<Long, String> details = new LinkedHashMap<>();
        for (PlanMove move : moves) {
            String courseName = courses.findById(move.courseId()).map(Course::getName).orElse("课程");
            String text = courseName + " " + label(move.fromDayOfWeek(), move.fromPeriodNo())
                    + " → " + label(move.toDayOfWeek(), move.toPeriodNo())
                    + "（" + move.toDate() + "）";
            details.merge(move.teacherId(), text, (left, right) -> left + "；" + right);
        }
        String summary = details.values().stream().collect(Collectors.joining("；"));
        notifications.swapEffective(request, details, summary);
    }

    private Map<String, Object> variables(SubstitutionRequest request) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("className", adminClasses.findById(request.getAdminClassId())
                .map(AdminClass::getName).orElse(""));
        variables.put("courseName", courses.findById(request.getCourseId())
                .map(Course::getName).orElse(""));
        variables.put("date", request.getEffectiveDate().toString());
        variables.put("periodLabel", DAY_NAMES[request.getDayOfWeek()] + "第" + request.getPeriodNo() + "节");
        variables.put("originalTeacher", teachers.findById(request.getOriginalTeacherId())
                .map(Teacher::getName).orElse(""));
        Long substituteId = substitutions.findFirstByEntryIdAndEffectiveDateAndStatus(
                        request.getEntryId(), request.getEffectiveDate(), Substitution.EFFECTIVE)
                .map(Substitution::getSubstituteTeacherId)
                .orElse(request.getExpectedSubstituteTeacherId());
        variables.put("substituteTeacher", substituteId == null ? "" : teachers.findById(substituteId)
                .map(Teacher::getName).orElse(""));
        variables.put("roomName", entries.findById(request.getEntryId())
                .map(TimetableEntry::getRoomId)
                .flatMap(rooms::findById)
                .map(Room::getName)
                .orElse("原教室"));
        variables.put("reason", request.getReason() == null ? "" : request.getReason());
        return variables;
    }

    private void recordHistory(Long requestId, String from, String to, String actor, String comment) {
        SubstitutionHistory item = new SubstitutionHistory();
        item.setRequestId(requestId);
        item.setFromStatus(from);
        item.setToStatus(to);
        item.setActor(actor);
        item.setComment(comment);
        item.setCreatedAt(Instant.now());
        history.save(item);
    }

    private SubstitutionRequest requireRequest(Long requestId) {
        return requests.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("调代申请不存在: " + requestId));
    }

    private void requireTeacher(Long teacherId) {
        if (!teachers.existsById(teacherId)) {
            throw ApiException.notFound("教师不存在: " + teacherId);
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

    private static String typeOf(SubstitutionRequest request) {
        return request.getType() == null ? Substitution.TYPE_COVER : request.getType();
    }

    private static String label(int dayOfWeek, int periodNo) {
        String day = dayOfWeek >= 1 && dayOfWeek <= 7 ? DAY_NAMES[dayOfWeek] : "周" + dayOfWeek;
        return day + "第" + periodNo + "节";
    }

    private SubstitutionPlanMove toMoveEntity(Long planId, PlanMove move) {
        SubstitutionPlanMove entity = new SubstitutionPlanMove();
        entity.setPlanId(planId);
        entity.setEntryId(move.entryId());
        entity.setClassId(move.classId());
        entity.setCourseId(move.courseId());
        entity.setTeacherId(move.teacherId());
        entity.setRoomId(move.roomId());
        entity.setFromPeriodId(move.fromPeriodId());
        entity.setFromDayOfWeek(move.fromDayOfWeek());
        entity.setFromPeriodNo(move.fromPeriodNo());
        entity.setFromDate(move.fromDate());
        entity.setToPeriodId(move.toPeriodId());
        entity.setToDayOfWeek(move.toDayOfWeek());
        entity.setToPeriodNo(move.toPeriodNo());
        entity.setToDate(move.toDate());
        entity.setMoveOrder(move.order());
        return entity;
    }

    private PlanMove toPlanMove(SubstitutionPlanMove entity) {
        return new PlanMove(
                entity.getEntryId(), entity.getClassId(), entity.getCourseId(), entity.getTeacherId(),
                entity.getRoomId(), entity.getFromPeriodId(), entity.getFromDayOfWeek(),
                entity.getFromPeriodNo(), entity.getFromDate(), entity.getToPeriodId(),
                entity.getToDayOfWeek(), entity.getToPeriodNo(), entity.getToDate(), entity.getMoveOrder());
    }

    private SubstitutionPlanDto toPlanDto(SubstitutionPlan plan) {
        List<PlanMoveDto> moves = planMoves.findByPlanIdOrderByMoveOrderAsc(plan.getId()).stream()
                .map(this::toPlanMoveDto)
                .toList();
        RankingPreset preset = RankingPreset.fromCode(plan.getRankingPreset());
        return new SubstitutionPlanDto(
                plan.getId(), plan.getRequestId(), plan.getStatus(), plan.getRankingPreset(),
                preset.label(), plan.getImpactDays(), plan.getImpactTeachers(), plan.getSoftPenalty(),
                plan.getCycleLength(), plan.isSameWeek(), plan.getCreatedAt(), plan.getExpiresAt(), moves);
    }

    private PlanMoveDto toPlanMoveDto(SubstitutionPlanMove move) {
        String className = adminClasses.findById(move.getClassId())
                .map(AdminClass::getName).orElse(null);
        String courseName = courses.findById(move.getCourseId()).map(Course::getName).orElse(null);
        String teacherName = teachers.findById(move.getTeacherId()).map(Teacher::getName).orElse(null);
        String roomName = move.getRoomId() == null ? null
                : rooms.findById(move.getRoomId()).map(Room::getName).orElse(null);
        return new PlanMoveDto(
                move.getId(), move.getEntryId(), move.getClassId(), className,
                move.getCourseId(), courseName, move.getTeacherId(), teacherName,
                move.getRoomId(), roomName,
                move.getFromPeriodId(), move.getFromDayOfWeek(), move.getFromPeriodNo(),
                label(move.getFromDayOfWeek(), move.getFromPeriodNo()), move.getFromDate(),
                move.getToPeriodId(), move.getToDayOfWeek(), move.getToPeriodNo(),
                label(move.getToDayOfWeek(), move.getToPeriodNo()), move.getToDate(),
                move.getMoveOrder());
    }

    private SubstitutionRequestDto toDto(SubstitutionRequest request) {
        String className = adminClasses.findById(request.getAdminClassId())
                .map(AdminClass::getName).orElse(null);
        String courseName = courses.findById(request.getCourseId()).map(Course::getName).orElse(null);
        String originalTeacher = teachers.findById(request.getOriginalTeacherId())
                .map(Teacher::getName).orElse(null);
        Long substituteId = substitutions.findFirstByEntryIdAndEffectiveDateAndStatus(
                        request.getEntryId(), request.getEffectiveDate(), Substitution.EFFECTIVE)
                .map(Substitution::getSubstituteTeacherId)
                .orElse(request.getExpectedSubstituteTeacherId());
        String substituteTeacher = substituteId == null ? null : teachers.findById(substituteId)
                .map(Teacher::getName).orElse(null);
        String approver = request.getApproverId() == null ? null : users.get(request.getApproverId())
                .getDisplayName();
        String targetPeriodLabel = request.getTargetPeriodId() == null ? null
                : periods.findById(request.getTargetPeriodId())
                        .map(period -> label(period.getDayOfWeek(), period.getPeriodNo()))
                        .orElse(null);
        return new SubstitutionRequestDto(
                request.getId(),
                request.getEntryId(),
                request.getAdminClassId(),
                className,
                request.getCourseId(),
                courseName,
                request.getOriginalTeacherId(),
                originalTeacher,
                substituteId,
                substituteTeacher,
                request.getEffectiveDate(),
                request.getPeriodId(),
                request.getDayOfWeek(),
                request.getPeriodNo(),
                DAY_NAMES[request.getDayOfWeek()] + "第" + request.getPeriodNo() + "节",
                request.getTargetPeriodId(),
                targetPeriodLabel,
                typeOf(request),
                request.getReason(),
                request.getStatus(),
                request.getApprovalMode(),
                approver,
                request.getApprovedAt(),
                request.getApprovalComment(),
                request.getCreatedAt());
    }

    private SubstitutionRecordDto toRecordDto(Substitution substitution) {
        TimetableEntry entry = entries.findById(substitution.getEntryId()).orElse(null);
        String className = entry == null ? null : adminClasses.findById(entry.getAdminClassId())
                .map(AdminClass::getName).orElse(null);
        String courseName = entry == null ? null : courses.findById(entry.getCourseId())
                .map(Course::getName).orElse(null);
        String original = teachers.findById(substitution.getOriginalTeacherId())
                .map(Teacher::getName).orElse(null);
        String substitute = teachers.findById(substitution.getSubstituteTeacherId())
                .map(Teacher::getName).orElse(null);
        Long roomId = substitution.getNewRoomId() != null ? substitution.getNewRoomId()
                : entry == null ? null : entry.getRoomId();
        String roomName = roomId == null ? null : rooms.findById(roomId).map(Room::getName).orElse(null);
        int fromDay = entry == null ? 0 : entry.getDayOfWeek();
        int fromNo = entry == null ? 0 : entry.getPeriodNo();
        int toDay = substitution.getNewDayOfWeek() != null ? substitution.getNewDayOfWeek() : fromDay;
        int toNo = substitution.getNewPeriodNo() != null ? substitution.getNewPeriodNo() : fromNo;
        return new SubstitutionRecordDto(
                substitution.getId(),
                substitution.getRequestId(),
                substitution.getEntryId(),
                substitution.getType() == null ? Substitution.TYPE_COVER : substitution.getType(),
                substitution.getPlanId(),
                substitution.getEffectiveDate(),
                entry == null ? null : entry.getAdminClassId(),
                className,
                entry == null ? null : entry.getCourseId(),
                courseName,
                substitution.getOriginalTeacherId(),
                original,
                substitution.getSubstituteTeacherId(),
                substitute,
                roomId,
                roomName,
                substitution.getStatus(),
                substitution.getCreatedAt(),
                fromDay,
                fromNo,
                label(fromDay, fromNo),
                toDay,
                toNo,
                label(toDay, toNo));
    }

    @Transactional(readOnly = true)
    public void requireHeadTeacher(String username, Long classId) {
        AppUser user = users.getByUsername(username);
        boolean headTeacher = user.getTeacherId() != null
                && adminClasses.findById(classId)
                        .map(adminClass -> user.getTeacherId().equals(adminClass.getHeadTeacherId()))
                        .orElse(false);
        if (!headTeacher) {
            throw ApiException.forbidden("只能查看本班的调代记录");
        }
    }

    @Transactional(readOnly = true)
    public List<SubstitutionRequestDto> pending(String username) {
        return list(username, null, SubstitutionRequest.PENDING_APPROVAL, false);
    }

    @Transactional(readOnly = true)
    public List<SubstitutionRecordDto> myRecords(String username) {
        AppUser user = users.getByUsername(username);
        if (user.getTeacherId() == null) {
            return List.of();
        }
        return records(null, user.getTeacherId(), null, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<SubstitutionRecordDto> allRecords() {
        return new ArrayList<>(records(null, null, null, null, null, null));
    }
}
