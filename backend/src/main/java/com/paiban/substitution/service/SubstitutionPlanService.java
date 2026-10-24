package com.paiban.substitution.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionPlan;
import com.paiban.substitution.domain.SubstitutionPlanMove;
import com.paiban.substitution.domain.SubstitutionPlanMoveRepository;
import com.paiban.substitution.domain.SubstitutionPlanRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.substitution.plan.CandidatePlan;
import com.paiban.substitution.plan.PlanMove;
import com.paiban.substitution.plan.PlanSnapshot;
import com.paiban.substitution.plan.PlanVerifier;
import com.paiban.substitution.plan.PlanningResult;
import com.paiban.substitution.plan.SwapPlanner;
import com.paiban.substitution.service.PlanSnapshotFactory.SwapPlannerOptions;
import com.paiban.substitution.web.SubstitutionDtos.DiagnosisDto;
import com.paiban.substitution.web.SubstitutionDtos.GeneratePlansResponse;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionPlanDto;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionRequestDto;
import com.paiban.substitution.web.SubstitutionDtos.SwapTargetDto;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Swap cycles: target slots, plan generation, plan listing and whole-plan approval. */
@Service
public class SubstitutionPlanService {

    private static final Duration PLAN_TTL = Duration.ofMinutes(30);

    private final SubstitutionRequestRepository requests;
    private final SubstitutionPlanRepository plans;
    private final SubstitutionPlanMoveRepository planMoves;
    private final TimetableEntryRepository entries;
    private final UserService users;
    private final PlanSnapshotFactory snapshots;
    private final SubstitutionAuditService audits;
    private final AuditLogRepository auditLogs;
    private final SubstitutionEffects effects;
    private final SubstitutionMapper mapper;

    public SubstitutionPlanService(
            SubstitutionRequestRepository requests,
            SubstitutionPlanRepository plans,
            SubstitutionPlanMoveRepository planMoves,
            TimetableEntryRepository entries,
            UserService users,
            PlanSnapshotFactory snapshots,
            SubstitutionAuditService audits,
            AuditLogRepository auditLogs,
            SubstitutionEffects effects,
            SubstitutionMapper mapper) {
        this.requests = requests;
        this.plans = plans;
        this.planMoves = planMoves;
        this.entries = entries;
        this.users = users;
        this.snapshots = snapshots;
        this.audits = audits;
        this.auditLogs = auditLogs;
        this.effects = effects;
        this.mapper = mapper;
    }

    @Transactional
    public GeneratePlansResponse generatePlans(Long requestId, String username) {
        SubstitutionRequest request = SubstitutionLookups.requireRequest(requests, requestId);
        if (!Substitution.TYPE_SWAP.equals(SubstitutionMapper.typeOf(request))) {
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
                planMoves.save(mapper.toMoveEntity(stored.getId(), move));
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
                saved.stream().map(mapper::toPlanDto).toList(),
                result.diagnoses().stream()
                        .map(diagnosis -> new DiagnosisDto(
                                diagnosis.code(), diagnosis.message(),
                                diagnosis.entryId(), diagnosis.periodId()))
                        .toList());
    }

    /**
     * Same-class occupied slots the requester can swap into. Deliberately narrow:
     * a plain teacher cannot read the class timetable, so the form asks only for
     * the slots it needs to render the target picker.
     */
    @Transactional(readOnly = true)
    public List<SwapTargetDto> swapTargets(String username, Long entryId, LocalDate date) {
        TimetableEntry entry = entries.findById(entryId)
                .orElseThrow(() -> ApiException.notFound("课节不存在: " + entryId));
        AppUser user = users.getByUsername(username);
        boolean admin = users.hasPermission(user, com.paiban.identity.domain.Permissions.SUBSTITUTION_VIEW);
        if (!admin && !Objects.equals(user.getTeacherId(), entry.getTeacherId())) {
            throw ApiException.forbidden("只能为自己的课选择目标节次");
        }
        if (date == null || date.getDayOfWeek().getValue() != entry.getDayOfWeek()) {
            throw ApiException.badRequest("目标日期与课节的星期不一致");
        }
        PlanSnapshot snapshot = snapshots.build(entry.getSemesterId());
        Map<Long, SwapTargetDto> byPeriod = new LinkedHashMap<>();
        for (var lesson : snapshot.scheduleOn(date).ofClass(entry.getAdminClassId())) {
            if (lesson.entryId() == entryId) {
                continue;
            }
            var course = snapshot.course(lesson.courseId());
            var teacher = snapshot.teacher(lesson.teacherId());
            byPeriod.putIfAbsent(lesson.periodId(), new SwapTargetDto(
                    lesson.periodId(), lesson.periodNo(), lesson.courseId(),
                    course == null ? null : course.name(), lesson.teacherId(),
                    teacher == null ? null : teacher.name()));
        }
        return new ArrayList<>(byPeriod.values());
    }

    @Transactional(readOnly = true)
    public List<SubstitutionPlanDto> plansFor(Long requestId, String username) {
        SubstitutionRequest request = SubstitutionLookups.requireRequest(requests, requestId);
        AppUser user = users.getByUsername(username);
        boolean approver = users.hasPermission(user, com.paiban.identity.domain.Permissions.SUBSTITUTION_APPROVE);
        if (!approver && !Objects.equals(user.getTeacherId(), request.getRequesterTeacherId())) {
            throw ApiException.forbidden("不能查看他人的调课方案");
        }
        return plans.findByRequestIdOrderByIdAsc(requestId).stream().map(mapper::toPlanDto).toList();
    }

    @Transactional
    public SubstitutionRequestDto approvePlan(Long planId, String username, String comment) {
        SubstitutionPlan plan = plans.findById(planId)
                .orElseThrow(() -> ApiException.notFound("调课方案不存在: " + planId));
        SubstitutionRequest request = SubstitutionLookups.requireRequest(requests, plan.getRequestId());
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
        effects.lockSemester(request.getSemesterId());
        PlanSnapshot snapshot = snapshots.build(request.getSemesterId());
        if (!snapshot.snapshotHash().equals(plan.getSnapshotHash())) {
            audits.expirePlan(planId, SubstitutionPlan.EXPIRED, username,
                    "SUBSTITUTION_PLAN_STALE",
                    "方案 " + planId + " 生成的课表快照已变化，拒绝生效");
            throw ApiException.conflict("课表已变化，旧方案不能生效，请重新生成");
        }
        List<SubstitutionPlanMove> moveEntities = planMoves.findByPlanIdOrderByMoveOrderAsc(planId);
        List<PlanMove> moves = moveEntities.stream().map(mapper::toPlanMove).toList();
        PlanVerifier.Result verification = PlanVerifier.verify(
                snapshot, moves, PlanVerifier.Kind.SWAP, plan.getSoftPenalty());
        if (!verification.passed()) {
            String detail = verification.issues().stream()
                    .limit(5)
                    .map(issue -> issue.code() + ": " + issue.message())
                    .collect(Collectors.joining("；"));
            audits.planFailure(planId, SubstitutionPlan.REJECTED, username,
                    "SUBSTITUTION_PLAN_VERIFY_FAILED", detail, true,
                    request.getRequesterTeacherId());
            throw ApiException.conflict("方案未通过校验，整单不生效：" + detail);
        }

        Instant now = Instant.now();
        AppUser approver = users.getByUsername(username);
        for (PlanMove move : moves) {
            effects.createSwapSubstitution(request, plan.getId(), move, now);
        }
        request.setStatus(SubstitutionRequest.EFFECTIVE);
        request.setApproverId(approver.getId());
        request.setApprovedAt(now);
        request.setApprovalComment(comment);
        request.setUpdatedAt(now);
        effects.recordHistory(request.getId(), SubstitutionRequest.PENDING_APPROVAL,
                SubstitutionRequest.EFFECTIVE, username, comment);
        plan.setStatus(SubstitutionPlan.APPROVED);
        plan.setApprovedAt(now);
        plan.setApprovedBy(username);
        auditLogs.save(new AuditLog(
                username, "SUBSTITUTION_PLAN_APPROVED", "SUBSTITUTION_PLAN",
                "request=" + request.getId() + ", plan=" + plan.getId()
                        + ", moves=" + moves.size() + ", teachers=" + plan.getImpactTeachers(),
                moves.size(), 0));
        effects.notifySwap(request, moves);
        return mapper.toDto(request);
    }
}
