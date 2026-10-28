package com.paiban.substitution.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.Semester;
import com.paiban.foundation.service.SemesterService;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionPlan;
import com.paiban.substitution.domain.SubstitutionPlanMoveRepository;
import com.paiban.substitution.domain.SubstitutionPlanRepository;
import com.paiban.substitution.domain.SubstitutionPreview;
import com.paiban.substitution.domain.SubstitutionPreviewRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.plan.CandidatePlan;
import com.paiban.substitution.plan.FrozenSlot;
import com.paiban.substitution.plan.PlanDiagnosis;
import com.paiban.substitution.plan.PlanMove;
import com.paiban.substitution.plan.PlanSnapshot;
import com.paiban.substitution.plan.PlanVerifier;
import com.paiban.substitution.plan.PlanningResult;
import com.paiban.substitution.plan.RankingPreset;
import com.paiban.substitution.plan.SwapPlanner;
import com.paiban.substitution.service.LessonDeadline.ChangedSlot;
import com.paiban.substitution.service.PlanSnapshotFactory.SwapPlannerOptions;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.GridCellDto;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.SwapCandidateDto;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.SwapChangeItemDto;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.SwapCompareResponse;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.SwapDayCompareDto;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.SwapPlanGenerateRequest;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.SwapPlansResponse;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.SwapPreviewCreateRequest;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.SwapPreviewResponse;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.SwapTargetSlotDto;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.SwapTargetsResponse;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.WorkbenchActionResult;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员同班调课（票 05）：为实际授课实例指定<b>明确的目标日期与课节</b>，在
 * 同一行政班当天已有课的槽位里求闭合置换环（二人环/多人环，≤3 个确定性候选），
 * 管理员查看整环左右对照与完整变化清单后提交审批或亲自确认，整环原子生效。
 *
 * <p>红线：
 * <ul>
 *   <li>目标绝不静默替换：管理员给定的目标日期原样进入求解与回传；空格、跨班、
 *       跨学期、窗口外、已开始的目标各自明确拒绝并说明原因。</li>
 *   <li>预览零申请、零生效、零通知：候选方案落库为没有申请的 PROPOSED 短期
 *       试算结果（30 分钟 TTL），凭证沿用票 03 的 {@link SubstitutionPreview}
 *       合同（绑定操作者、快照哈希、选定方案、过期 ≤30 分钟且 ≤ 开课截止）。</li>
 *   <li>提交审批进待批队列（旧课表继续）；亲自确认调用
 *       {@link SubstitutionPlanService#approvePlan}——学期行锁、快照哈希重验、
 *       {@link PlanVerifier} 独立校验、整环生效行、审计与通知完全同路径，不绕过。</li>
 *   <li>本单开课截止 = 环内所有被改变实例「当前与拟生效」开课时刻的最小值
 *       （{@link LessonDeadline#earliest}）；等待行锁后重读时钟，排队跨点即拒绝。</li>
 * </ul>
 */
@Service
public class AdminSwapService {

    private static final Duration PREVIEW_TTL = Duration.ofMinutes(30);
    private static final Duration PLAN_TTL = Duration.ofMinutes(30);

    private final SubstitutionPreviewRepository previews;
    private final SubstitutionRequestRepository requests;
    private final SubstitutionRepository substitutions;
    private final SubstitutionPlanRepository plans;
    private final SubstitutionPlanMoveRepository planMoves;
    private final TimetableEntryRepository entries;
    private final PeriodRepository periods;
    private final SemesterService semesters;
    private final UserService users;
    private final PlanSnapshotFactory snapshots;
    private final SubstitutionEffects effects;
    private final SubstitutionPlanService planService;
    private final SubstitutionMapper mapper;
    private final AuditLogRepository auditLogs;
    private final java.time.Clock clock;

    public AdminSwapService(
            SubstitutionPreviewRepository previews,
            SubstitutionRequestRepository requests,
            SubstitutionRepository substitutions,
            SubstitutionPlanRepository plans,
            SubstitutionPlanMoveRepository planMoves,
            TimetableEntryRepository entries,
            PeriodRepository periods,
            SemesterService semesters,
            UserService users,
            PlanSnapshotFactory snapshots,
            SubstitutionEffects effects,
            SubstitutionPlanService planService,
            SubstitutionMapper mapper,
            AuditLogRepository auditLogs,
            java.time.Clock clock) {
        this.previews = previews;
        this.requests = requests;
        this.substitutions = substitutions;
        this.plans = plans;
        this.planMoves = planMoves;
        this.entries = entries;
        this.periods = periods;
        this.semesters = semesters;
        this.users = users;
        this.snapshots = snapshots;
        this.effects = effects;
        this.planService = planService;
        this.mapper = mapper;
        this.auditLogs = auditLogs;
        this.clock = clock;
    }

    // ------------------------------------------------------------- swap targets

    /**
     * 目标日期里来源班当前已有课的槽位（表单/拖拽的目标候选格）。只列本班的课：
     * 跨班目标从这里就不可达；空日期返回空列表（空格只挪不补）。
     */
    @Transactional(readOnly = true)
    public SwapTargetsResponse swapTargets(String username, Long entryId, LocalDate lessonDate,
                                           LocalDate targetDate) {
        AppUser user = users.getByUsername(username);
        requireManager(user);
        if (targetDate == null) {
            throw ApiException.badRequest("请选择目标日期");
        }
        SourceInstance source = loadInstance(entryId, lessonDate);
        validateTargetDate(source, targetDate);
        PlanSnapshot snapshot = source.snapshot();
        long classId = source.entry().getAdminClassId();
        List<SwapTargetSlotDto> slots = new ArrayList<>();
        for (PlanSnapshot.ScheduledLesson lesson : snapshot.scheduleOn(targetDate).ofClass(classId)) {
            if (lesson.entryId() == entryId) {
                continue;
            }
            var course = snapshot.course(lesson.courseId());
            var teacher = snapshot.teacher(lesson.teacherId());
            slots.add(new SwapTargetSlotDto(
                    lesson.periodId(), lesson.periodNo(), lesson.courseId(),
                    course == null ? null : course.name(), lesson.teacherId(),
                    teacher == null ? null : teacher.name()));
        }
        slots.sort(Comparator.comparingInt(SwapTargetSlotDto::periodNo));
        return new SwapTargetsResponse(entryId, lessonDate, targetDate, classId,
                className(snapshot, classId), slots);
    }

    // ------------------------------------------------------------ plan generate

    /**
     * 生成置换环候选：目标日期由管理员明确指定，原样进入求解并回传实际日期；
     * 无解时返回原因（原课表不动、零申请、零生效），非法目标直接拒绝。
     */
    @Transactional
    public SwapPlansResponse generatePlans(String username, SwapPlanGenerateRequest body) {
        AppUser user = users.getByUsername(username);
        requireManager(user);
        SourceInstance source = loadInstance(body.entryId(), body.lessonDate());
        validateTargetDate(source, body.targetDate());
        PlanSnapshot snapshot = source.snapshot();
        long classId = source.entry().getAdminClassId();

        var targetPeriodView = snapshot.period(body.targetPeriodId());
        if (targetPeriodView == null) {
            throw ApiException.notFound("目标节次不存在: " + body.targetPeriodId());
        }
        if (targetPeriodView.dayOfWeek() != body.targetDate().getDayOfWeek().getValue()) {
            throw ApiException.badRequest("目标日期 " + body.targetDate()
                    + " 与目标课节的星期不一致，请重新选择");
        }
        PlanSnapshot.ScheduledLesson targetLesson =
                snapshot.scheduleOn(body.targetDate()).lessonAt(classId, body.targetPeriodId());
        if (targetLesson == null) {
            throw ApiException.badRequest("目标节次在 " + body.targetDate() + " 的本班课表里是空堂；"
                    + "空格只挪不补，不在调课范围内（跨班目标同样不支持）");
        }
        if (targetLesson.entryId() == body.entryId()) {
            throw ApiException.badRequest("目标课节就是来源课本身");
        }
        // 已开始的槽位不是有效目标：来源与目标的开课时刻各自复查（服务端权威时钟）。
        new DeadlineChecker(clock::instant).requireBeforeDeadline(
                deadline(body.lessonDate(), source.current().periodId()),
                "来源课节 " + label(source.current()));
        new DeadlineChecker(clock::instant).requireBeforeDeadline(
                deadline(body.targetDate(), targetLesson.periodId()),
                "目标课节 " + label(targetLesson) + "（" + body.targetDate() + "）");

        SwapPlannerOptions options = snapshots.swapOptions();
        RankingPreset preset = body.rankingPreset() == null || body.rankingPreset().isBlank()
                ? options.preset()
                : RankingPreset.fromCode(body.rankingPreset());
        // 冻结槽（票 07）：窗口内本班当前安排已开始的槽位交给求解器跳过——
        // 部分已开始的旧环里，不碰冻结实例的合法新方案要能被找到。
        Set<FrozenSlot> frozenSlots = frozenSlotsInWindow(snapshot, classId,
                body.lessonDate(), options.windowDays());
        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(classId, body.entryId(), body.lessonDate(),
                        body.targetPeriodId(), body.targetDate(), frozenSlots),
                SwapPlanner.Options.of(options.windowDays(), options.maxTeachers(), preset));

        // 管理预览候选是短期试算结果：重新生成时清掉上一批（没有申请的 PROPOSED 方案）。
        plans.findByRequestIdIsNullAndStatus(SubstitutionPlan.PROPOSED).forEach(old -> {
            planMoves.deleteByPlanId(old.getId());
            plans.delete(old);
        });

        String hash = snapshot.snapshotHash();
        Instant now = clock.instant();
        List<SwapCandidateDto> candidates = new ArrayList<>();
        for (CandidatePlan candidate : result.candidates().stream().limit(3).toList()) {
            SubstitutionPlan plan = new SubstitutionPlan();
            plan.setRequestId(null);
            plan.setSemesterId(source.semesterId());
            plan.setSnapshotHash(hash);
            plan.setRankingPreset(preset.code());
            plan.setStatus(SubstitutionPlan.PROPOSED);
            plan.setImpactDays(candidate.impactDays());
            plan.setImpactTeachers(candidate.impactTeachers());
            plan.setSoftPenalty(candidate.softPenalty());
            plan.setCycleLength(candidate.cycleLength());
            plan.setSameWeek(candidate.sameWeek());
            plan.setCreatedAt(now);
            plan.setExpiresAt(now.plus(PLAN_TTL));
            SubstitutionPlan stored = plans.save(plan);
            List<com.paiban.substitution.domain.SubstitutionPlanMove> moveEntities = new ArrayList<>();
            for (PlanMove move : candidate.moves()) {
                moveEntities.add(planMoves.save(mapper.toMoveEntity(stored.getId(), move)));
            }
            candidates.add(new SwapCandidateDto(
                    stored.getId(), candidate.impactDays(), candidate.impactTeachers(),
                    candidate.softPenalty(), candidate.cycleLength(), candidate.sameWeek(),
                    moveEntities.stream().map(mapper::toPlanMoveDto).toList()));
        }
        auditLogs.save(new AuditLog(
                username, "SUBSTITUTION_ADMIN_SWAP_PLANS_GENERATED", "SUBSTITUTION_PLAN",
                "entry=" + body.entryId() + ", lessonDate=" + body.lessonDate()
                        + ", target=" + body.targetDate() + "/" + body.targetPeriodId()
                        + ", preset=" + preset.code() + ", plans=" + candidates.size(),
                candidates.size(), 0));

        var sourceCourse = snapshot.course(source.current().courseId());
        return new SwapPlansResponse(
                body.entryId(), body.lessonDate(), classId, className(snapshot, classId),
                source.current().courseId(), sourceCourse == null ? null : sourceCourse.name(),
                body.targetDate(), body.targetPeriodId(), targetPeriodView.periodNo(),
                preset.code(), preset.label(), candidates,
                result.diagnoses().stream()
                        .map(diagnosis -> new com.paiban.substitution.web.SubstitutionDtos.DiagnosisDto(
                                diagnosis.code(), diagnosis.message(),
                                diagnosis.entryId(), diagnosis.periodId()))
                        .toList());
    }

    // ------------------------------------------------------------------ preview

    /** 创建 SWAP 预览凭证：绑定选定方案与快照哈希；零申请、零生效、零通知。 */
    @Transactional
    public SwapPreviewResponse preview(String username, SwapPreviewCreateRequest body) {
        AppUser user = users.getByUsername(username);
        requireManager(user);
        SourceInstance source = loadInstance(body.entryId(), body.lessonDate());
        PlanSnapshot snapshot = source.snapshot();
        SubstitutionPlan plan = requireUsablePlan(body.planId(), snapshot, source.semesterId());
        List<PlanMove> moves = movesOf(plan);
        if (moves.stream().noneMatch(move -> move.entryId() == body.entryId())) {
            throw ApiException.badRequest("该方案不包含所选课节，请重新生成");
        }
        if (hasLegacyCover(body.entryId(), body.lessonDate())) {
            throw ApiException.conflict(
                    "该课节当前生效的是旧格式调代记录（无授课实例日期，历史数据），"
                            + "需先完成历史转换才能在管理端修改，本次操作被拒绝");
        }
        Long raced = activeRequestWithin(moves);
        if (raced != null) {
            throw ApiException.conflict("置换环内的课节已有待处理的申请（#" + raced + "），请先处理该单");
        }

        // 本单截止 = 环内所有被改变实例「当前与拟生效」开课时刻的最小值。
        Instant deadline = swapDeadline(moves);
        new DeadlineChecker(clock::instant).requireBeforeDeadline(deadline, "置换环内最早的课节");

        // 冻结校验（票 07）：方案触及已开始的槽位（来源或落点）即拒绝——
        // 已开始或已结束的授课任何路径都不可改变。
        LessonFreeze.frozenTouchReason(moves, this::periodStart, clock.instant())
                .ifPresent(reason -> { throw ApiException.conflict(reason); });

        // 校验问题只是预览提示；硬冲突以确认时的独立校验器裁决为准。
        PlanVerifier.Result verification =
                PlanVerifier.verify(snapshot, moves, PlanVerifier.Kind.SWAP, plan.getSoftPenalty());
        List<String> issues = verification.issues().stream()
                .map(issue -> issue.code() + ": " + issue.message())
                .toList();

        // 修订链（票 07）：预览绑定来源实例的当前生效申请作为基准。
        // 有基准 = 修订调课（RESCHEDULE_REVISION），无基准 = 首次新调课（RESCHEDULE）。
        Long revisesRequestId = SubstitutionLookups
                .currentInstanceRow(substitutions, body.entryId(), body.lessonDate())
                .map(Substitution::getRequestId).orElse(null);
        String revisionKind = RevisionPolicy.swapRevisionKind(revisesRequestId);

        Instant now = clock.instant();
        SubstitutionPreview voucher = new SubstitutionPreview();
        voucher.setSemesterId(source.semesterId());
        voucher.setEntryId(body.entryId());
        voucher.setLessonDate(body.lessonDate());
        voucher.setPlanId(plan.getId());
        voucher.setPreviousTeacherId(source.current().teacherId());
        voucher.setRevisesRequestId(revisesRequestId);
        voucher.setRevisionKind(revisionKind);
        voucher.setOperatorUserId(user.getId());
        voucher.setSnapshotHash(snapshot.snapshotHash());
        voucher.setReason(body.reason());
        voucher.setDeadlineAt(deadline);
        voucher.setExpiresAt(min(now.plus(PREVIEW_TTL), deadline));
        voucher.setStatus(SubstitutionPreview.ACTIVE);
        voucher.setCreatedAt(now);
        SubstitutionPreview saved = previews.save(voucher);

        long classId = source.entry().getAdminClassId();
        PlanMove sourceMove = moves.stream()
                .filter(move -> move.entryId() == body.entryId()).findFirst().orElse(moves.get(0));
        var targetPeriodView = snapshot.period(sourceMove.toPeriodId());
        Set<Long> teacherIds = new LinkedHashSet<>();
        moves.forEach(move -> teacherIds.add(move.teacherId()));
        List<String> teacherNames = teacherIds.stream()
                .map(id -> teacherName(snapshot, id)).toList();
        return new SwapPreviewResponse(
                saved.getId(),
                plan.getId(),
                deadline,
                saved.getExpiresAt(),
                body.entryId(),
                body.lessonDate(),
                // 规格：目标 API 回传实际日期（从方案权威读回，不信客户端）。
                sourceMove.toDate(),
                sourceMove.toPeriodId(),
                targetPeriodView == null ? null : targetPeriodView.periodNo(),
                classId,
                className(snapshot, classId),
                new ArrayList<>(teacherIds),
                teacherNames,
                compareDays(snapshot, classId, moves),
                changeItems(snapshot, moves),
                issues,
                true,
                true,
                revisesRequestId,
                revisionKind);
    }

    // ------------------------------------------------------- submit / confirm

    /** 管理员代发调课：提交审批进待批队列，旧安排继续生效，不发已生效通知。 */
    @Transactional
    public WorkbenchActionResult submitApproval(Long voucherId, String username, String comment) {
        return consumeVoucher(voucherId, username,
                SubstitutionPreview.ACTION_SUBMIT_APPROVAL, comment, false);
    }

    /**
     * 管理员亲自确认整环生效：同一事务里创建 SWAP 申请并走
     * {@link SubstitutionPlanService#approvePlan} 的普通批准路径
     * （TTL、快照哈希重验、独立校验、整环生效行、审计与通知），失败整单零写入。
     */
    @Transactional
    public WorkbenchActionResult confirm(Long voucherId, String username, String comment) {
        return consumeVoucher(voucherId, username, SubstitutionPreview.ACTION_CONFIRM, comment, true);
    }

    private WorkbenchActionResult consumeVoucher(
            Long voucherId, String username, String action, String comment, boolean effectiveNow) {
        AppUser user = users.getByUsername(username);
        requireManager(user);
        SubstitutionPreview voucher = previews.findById(voucherId)
                .orElseThrow(() -> ApiException.notFound("预览凭证不存在: " + voucherId));
        if (!voucher.getOperatorUserId().equals(user.getId())) {
            throw ApiException.forbidden("预览凭证绑定操作者，不能使用他人的预览");
        }
        String actionHash = actionHash(action, comment);

        // 幂等：相同动作 + 相同参数的重试返回第一次结果；参数改变返回冲突。
        var replayed = voucher.replay(action, actionHash);
        if (replayed.isPresent()) {
            return WorkbenchActionResult.fromJson(replayed.get());
        }
        if (voucher.conflictsWith(action, actionHash)) {
            throw ApiException.conflict("该预览凭证已用于其他操作，如需调整请重新预览");
        }
        if (!SubstitutionPreview.ACTIVE.equals(voucher.getStatus())) {
            throw ApiException.conflict("该预览凭证已失效，请重新预览");
        }
        SubstitutionPlan plan = plans.findById(voucher.getPlanId())
                .orElseThrow(() -> ApiException.conflict("预览绑定的调课方案不存在，请重新生成"));
        if (!SubstitutionPlan.PROPOSED.equals(plan.getStatus())) {
            throw ApiException.conflict("该方案当前状态为 " + plan.getStatus() + "，不能继续");
        }
        if (effectiveNow) {
            // 确认路径：先拿学期行锁（可能要等其他管理员的调代写入），拿到锁后再
            // 重读时钟、重建快照——排队期间课表可能已被改动，一切复核以锁后状态为准。
            effects.lockSemester(voucher.getSemesterId());
        }
        // 等待行锁后必须重新读取时间：排队跨过开课截止必须拒绝。
        new DeadlineChecker(clock::instant).requireBeforeDeadline(
                voucher.getDeadlineAt(), "置换环内最早的课节");
        PlanSnapshot snapshot = snapshots.build(voucher.getSemesterId());
        if (!snapshot.snapshotHash().equals(voucher.getSnapshotHash())) {
            expire(voucher);
            throw ApiException.conflict("课表已变化，旧预览不能生效，请重新预览");
        }
        List<PlanMove> moves = movesOf(plan);
        Long raced = activeRequestWithin(moves);
        if (raced != null) {
            throw ApiException.conflict("置换环内的课节已有待处理的申请（#" + raced + "），请先处理该单");
        }
        // 冻结复核（票 07）：等待行锁后实时读时钟——排队期间环内任何槽位开课都整单拒绝。
        LessonFreeze.frozenTouchReason(moves, this::periodStart, clock.instant())
                .ifPresent(reason -> { throw ApiException.conflict(reason); });
        // 基准复核（票 07）：来源实例的当前生效行必须仍属于预览绑定的基准申请。
        // 快照哈希已保证整份课表未变，这里钉住基准防旁路；基准被抢先替代时明确冲突。
        var currentRow = SubstitutionLookups
                .currentInstanceRow(substitutions, voucher.getEntryId(), voucher.getLessonDate());
        if (currentRow.isPresent()) {
            if (voucher.getRevisesRequestId() == null
                    || !voucher.getRevisesRequestId().equals(currentRow.get().getRequestId())) {
                expire(voucher);
                throw ApiException.conflict("该课节的当前安排已变化，旧预览不能生效，请重新预览");
            }
        } else if (voucher.getRevisesRequestId() != null) {
            expire(voucher);
            throw ApiException.conflict("该修订的基准安排已不存在，请重新预览");
        }

        SubstitutionRequest request = createSwapRequest(user, voucher, snapshot, moves);
        plan.setRequestId(request.getId());
        plans.save(plan);

        WorkbenchActionResult result;
        if (effectiveNow) {
            // 与普通批准完全同路径：TTL、快照哈希、独立校验、生效行、历史与通知都在
            // approvePlan 里，全部发生在当前事务内；失败则整单回滚、凭证不被消费。
            planService.approvePlan(plan.getId(), username, comment);
            result = new WorkbenchActionResult(request.getId(), SubstitutionRequest.EFFECTIVE, false);
        } else {
            result = new WorkbenchActionResult(request.getId(), SubstitutionRequest.PENDING_APPROVAL, false);
        }
        auditLogs.save(new AuditLog(
                username,
                effectiveNow ? "SUBSTITUTION_ADMIN_SWAP_CONFIRMED" : "SUBSTITUTION_ADMIN_SWAP_DISPATCHED",
                "SUBSTITUTION",
                "operator=" + user.getUsername()
                        + ", operatorUserId=" + user.getId()
                        + ", voucherId=" + voucherId
                        + ", planId=" + plan.getId()
                        + ", requestId=" + request.getId()
                        + ", moves=" + moves.size(),
                moves.size(), 0));
        voucher.consume(action, actionHash, result.toJson(), clock.instant());
        previews.save(voucher);
        return result;
    }

    /** SWAP 代发申请：代发对象 = 来源课基线教师；明确目标日期/课节随申请落库。 */
    private SubstitutionRequest createSwapRequest(
            AppUser operator, SubstitutionPreview voucher, PlanSnapshot snapshot, List<PlanMove> moves) {
        TimetableEntry entry = entries.findById(voucher.getEntryId())
                .orElseThrow(() -> ApiException.conflict("来源课节不存在，请重新预览"));
        PlanSnapshot.ScheduledLesson current = snapshot.scheduleOn(voucher.getLessonDate()).lessons()
                .stream().filter(lesson -> lesson.entryId() == voucher.getEntryId())
                .findFirst()
                .orElseThrow(() -> ApiException.conflict("来源课节当天已没有课，请重新预览"));
        PlanMove sourceMove = moves.stream()
                .filter(move -> move.entryId() == voucher.getEntryId()).findFirst().orElse(moves.get(0));
        SubstitutionRequest request = new SubstitutionRequest();
        request.setSemesterId(voucher.getSemesterId());
        request.setEntryId(entry.getId());
        request.setAdminClassId(entry.getAdminClassId());
        request.setCourseId(entry.getCourseId());
        request.setOriginalTeacherId(entry.getTeacherId());
        // 代发对象：被代发申请的原任课教师；管理员绑定的教师身份不参与。
        request.setRequesterTeacherId(entry.getTeacherId());
        request.setOperatorUserId(operator.getId());
        request.setPreviousTeacherId(current.teacherId());
        request.setEffectiveDate(voucher.getLessonDate());
        request.setPeriodId(current.periodId());
        request.setDayOfWeek(current.dayOfWeek());
        request.setPeriodNo(current.periodNo());
        request.setTargetPeriodId(sourceMove.toPeriodId());
        request.setTargetDate(sourceMove.toDate());
        request.setReason(voucher.getReason());
        request.setType(Substitution.TYPE_SWAP);
        // 修订链（票 07）：RESCHEDULE = 首次新调课；RESCHEDULE_REVISION = 以当前
        // 生效安排为基准的修订调课。前驱申请随凭证绑定落库，批准后由 approvePlan 处理。
        request.setRevisesRequestId(voucher.getRevisesRequestId());
        request.setRevisionKind(voucher.getRevisionKind());
        // 管理员的提交审批与亲自确认都不依赖全局教师自助模式。
        request.setApprovalMode("approval");
        request.setStatus(SubstitutionRequest.SUBMITTED);
        Instant now = clock.instant();
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        SubstitutionRequest saved = requests.save(request);
        effects.recordHistory(saved.getId(), null, SubstitutionRequest.SUBMITTED,
                operator.getUsername(), null);
        saved.setStatus(SubstitutionRequest.PENDING_APPROVAL);
        saved.setUpdatedAt(now);
        SubstitutionRequest pending = requests.save(saved);
        effects.recordHistory(saved.getId(), SubstitutionRequest.SUBMITTED,
                SubstitutionRequest.PENDING_APPROVAL, operator.getUsername(), null);
        return pending;
    }

    // ------------------------------------------------------------------ compare

    /**
     * 切换视角（班级/教师）与日期后的单日左右对照；两侧同对象同日期范围联动，
     * 跨周变化可按日期逐日定位。
     */
    @Transactional(readOnly = true)
    public SwapCompareResponse compare(Long voucherId, String username, String view,
                                       Long teacherId, LocalDate date) {
        AppUser user = users.getByUsername(username);
        requireManager(user);
        if (date == null) {
            throw ApiException.badRequest("请选择对照日期");
        }
        SubstitutionPreview voucher = previews.findById(voucherId)
                .orElseThrow(() -> ApiException.notFound("预览凭证不存在: " + voucherId));
        if (!voucher.getOperatorUserId().equals(user.getId())) {
            throw ApiException.forbidden("预览凭证绑定操作者，不能使用他人的预览");
        }
        SubstitutionPlan plan = plans.findById(voucher.getPlanId())
                .orElseThrow(() -> ApiException.conflict("预览绑定的调课方案不存在，请重新生成"));
        PlanSnapshot snapshot = snapshots.build(voucher.getSemesterId());
        List<PlanMove> moves = movesOf(plan);
        if ("teacher".equals(view)) {
            if (teacherId == null) {
                throw ApiException.badRequest("请选择教师");
            }
            List<PlanSnapshot.ScheduledLesson> lessons = snapshot.scheduleOn(date).lessons().stream()
                    .filter(lesson -> lesson.teacherId() == teacherId)
                    .toList();
            Map<Long, GridCellDto> left = leftGrid(snapshot, lessons);
            Map<Long, GridCellDto> right = rightGrid(snapshot, lessons, date, moves);
            return new SwapCompareResponse(date, "teacher", teacherId,
                    teacherName(snapshot, teacherId),
                    marked(left, right, true), marked(left, right, false));
        }
        long classId = snapshot.scheduleOn(date).lessons().stream()
                .filter(lesson -> lesson.entryId() == voucher.getEntryId())
                .map(PlanSnapshot.ScheduledLesson::classId)
                .findFirst()
                .orElse(entries.findById(voucher.getEntryId())
                        .map(TimetableEntry::getAdminClassId)
                        .orElseThrow(() -> ApiException.conflict("来源课节不存在，请重新预览")));
        List<PlanSnapshot.ScheduledLesson> lessons = snapshot.scheduleOn(date).ofClass(classId);
        Map<Long, GridCellDto> left = leftGrid(snapshot, lessons);
        Map<Long, GridCellDto> right = rightGrid(snapshot, lessons, date, moves);
        return new SwapCompareResponse(date, "class", classId, className(snapshot, classId),
                marked(left, right, true), marked(left, right, false));
    }

    // ------------------------------------------------------------------ helpers

    private record SourceInstance(TimetableEntry entry, Long semesterId, LocalDate lessonDate,
                                  PlanSnapshot snapshot, PlanSnapshot.ScheduledLesson current) {}

    /** 装载来源实例（学期 + 基线条目 + 授课日期）与其当前实际安排。 */
    private SourceInstance loadInstance(Long entryId, LocalDate lessonDate) {
        if (lessonDate == null) {
            throw ApiException.badRequest("请选择授课日期");
        }
        TimetableEntry entry = entries.findById(entryId)
                .orElseThrow(() -> ApiException.notFound("课节不存在: " + entryId));
        Semester semester = semesters.require(entry.getSemesterId());
        if (lessonDate.isBefore(semester.getStartDate()) || lessonDate.isAfter(semester.getEndDate())) {
            throw ApiException.badRequest("授课日期不在学期范围内");
        }
        PlanSnapshot snapshot = snapshots.build(entry.getSemesterId());
        PlanSnapshot.ScheduledLesson current = snapshot.scheduleOn(lessonDate).lessons().stream()
                .filter(lesson -> lesson.entryId() == entryId)
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest(
                        "该日期没有这节课（可能已被调走或当天不是授课日），请核对日期"));
        return new SourceInstance(entry, semester.getId(), lessonDate, snapshot, current);
    }

    /**
     * 目标日期边界：窗口「从来源日期向后展开」（不支持向前搜索）、默认 7 天、
     * 配置 1–30；目标日期同时必须在学期范围内。非法目标在进入求解前明确拒绝。
     */
    private void validateTargetDate(SourceInstance source, LocalDate targetDate) {
        int windowDays = snapshots.swapOptions().windowDays();
        if (targetDate.isBefore(source.lessonDate())) {
            throw ApiException.badRequest("目标日期 " + targetDate + " 早于来源日期；"
                    + "调课窗口从来源日期向后展开 " + windowDays + " 天，不支持向来源日期之前搜索");
        }
        if (targetDate.isAfter(source.lessonDate().plusDays(windowDays - 1L))) {
            throw ApiException.badRequest("目标日期 " + targetDate + " 不在来源日期起 "
                    + windowDays + " 天的搜索窗口内");
        }
        if (targetDate.isAfter(source.snapshot().semesterEnd())) {
            throw ApiException.badRequest("目标日期超出学期范围");
        }
    }

    /** 可用的管理预览方案：PROPOSED、未过期、未关联申请、快照哈希匹配当前课表。 */
    private SubstitutionPlan requireUsablePlan(Long planId, PlanSnapshot snapshot, Long semesterId) {
        SubstitutionPlan plan = plans.findById(planId)
                .orElseThrow(() -> ApiException.notFound("调课方案不存在: " + planId));
        if (!SubstitutionPlan.PROPOSED.equals(plan.getStatus())) {
            throw ApiException.conflict("该方案当前状态为 " + plan.getStatus() + "，不能预览");
        }
        if (!semesterId.equals(plan.getSemesterId())) {
            throw ApiException.badRequest("方案与课节不在同一学期");
        }
        if (plan.getRequestId() != null) {
            throw ApiException.conflict("该方案已关联申请，请重新生成方案");
        }
        if (clock.instant().isAfter(plan.getExpiresAt())) {
            throw ApiException.conflict("方案已过期，请重新生成");
        }
        if (!snapshot.snapshotHash().equals(plan.getSnapshotHash())) {
            throw ApiException.conflict("课表已变化，旧方案不能预览，请重新生成");
        }
        return plan;
    }

    private List<PlanMove> movesOf(SubstitutionPlan plan) {
        List<PlanMove> moves = planMoves.findByPlanIdOrderByMoveOrderAsc(plan.getId()).stream()
                .map(mapper::toPlanMove)
                .toList();
        if (moves.isEmpty()) {
            throw ApiException.conflict("方案没有任何课节移动，请重新生成");
        }
        return moves;
    }

    /**
     * 窗口内本班已开始的槽位集合（票 07）：日期从来源日起按窗口展开、不超过学期末，
     * 槽位取当前实际安排（含生效调代）。课节开始时间缺失即拒绝写入（规格第 6 节）。
     */
    private Set<FrozenSlot> frozenSlotsInWindow(PlanSnapshot snapshot, long classId,
                                                LocalDate lessonDate, int windowDays) {
        Set<FrozenSlot> frozen = new LinkedHashSet<>();
        LocalDate windowEnd = lessonDate.plusDays(windowDays - 1L);
        for (LocalDate date = lessonDate; !date.isAfter(windowEnd)
                && !date.isAfter(snapshot.semesterEnd()); date = date.plusDays(1)) {
            for (PlanSnapshot.ScheduledLesson lesson : snapshot.scheduleOn(date).ofClass(classId)) {
                if (LessonFreeze.started(clock.instant(), date, periodStart(lesson.periodId()))) {
                    frozen.add(new FrozenSlot(date, lesson.periodId()));
                }
            }
        }
        return frozen;
    }

    /** 本单截止：环内所有被改变实例「当前与拟生效」开课时刻的最小值。 */
    private Instant swapDeadline(List<PlanMove> moves) {
        List<ChangedSlot> slots = new ArrayList<>();
        for (PlanMove move : moves) {
            slots.add(new ChangedSlot(move.fromDate(), periodStart(move.fromPeriodId())));
            slots.add(new ChangedSlot(move.toDate(), periodStart(move.toPeriodId())));
        }
        try {
            return LessonDeadline.earliest(slots);
        } catch (LessonDeadline.MissingStartTimeException ex) {
            throw ApiException.badRequest(ex.getMessage());
        }
    }

    private Instant deadline(LocalDate lessonDate, long periodId) {
        try {
            return LessonDeadline.earliest(List.of(
                    new ChangedSlot(lessonDate, periodStart(periodId))));
        } catch (LessonDeadline.MissingStartTimeException ex) {
            throw ApiException.badRequest(ex.getMessage());
        }
    }

    private LocalTime periodStart(long periodId) {
        Period period = periods.findById(periodId)
                .orElseThrow(() -> ApiException.badRequest(
                        "无法确定开课时刻：课节 " + periodId + " 不存在，拒绝写入"));
        if (period.getStartTime() == null) {
            throw ApiException.badRequest(
                    "无法确定开课时刻：课节 " + periodId + " 缺少开始时间，拒绝写入");
        }
        return period.getStartTime();
    }

    /** 数据级旧格式检查：该条目存在 lesson_date 为空的 EFFECTIVE 行且覆盖该日期。 */
    private boolean hasLegacyCover(Long entryId, LocalDate lessonDate) {
        return substitutions.findByEntryIdAndStatusAndLessonDateIsNull(entryId, Substitution.EFFECTIVE)
                .stream()
                .anyMatch(row -> !row.getEffectiveDate().isAfter(lessonDate));
    }

    /** 环内每个实例（当前日与落点日）同一时间最多一笔活跃修改。 */
    private Long activeRequestWithin(List<PlanMove> moves) {
        for (PlanMove move : moves) {
            Long raced = activeRequestFor(move.entryId(), move.fromDate());
            if (raced != null) {
                return raced;
            }
            raced = activeRequestFor(move.entryId(), move.toDate());
            if (raced != null) {
                return raced;
            }
        }
        return null;
    }

    private Long activeRequestFor(Long entryId, LocalDate date) {
        return requests.findByEntryIdAndEffectiveDate(entryId, date).stream()
                .filter(request -> SubstitutionRequest.SUBMITTED.equals(request.getStatus())
                        || SubstitutionRequest.PENDING_APPROVAL.equals(request.getStatus()))
                .map(SubstitutionRequest::getId)
                .findFirst()
                .orElse(null);
    }

    private void expire(SubstitutionPreview voucher) {
        voucher.setStatus(SubstitutionPreview.EXPIRED);
        previews.save(voucher);
    }

    private void requireManager(AppUser user) {
        if (!users.hasAllPermissions(user, AdminSubstitutionService.MANAGER_PERMISSIONS)) {
            throw ApiException.forbidden("管理调代工作台需要「全校课表查看 + 提交调代 + 审批调代」权限");
        }
    }

    private String actionHash(String action, String comment) {
        String payload = action + "|" + (comment == null ? "" : comment);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    }

    private Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    // ------------------------------------------------------- compare projection

    /** 受影响日期排序后的逐日左右对照（班级视角）。 */
    private List<SwapDayCompareDto> compareDays(PlanSnapshot snapshot, long classId,
                                                List<PlanMove> moves) {
        Set<LocalDate> dates = new TreeSet<>();
        for (PlanMove move : moves) {
            dates.add(move.fromDate());
            dates.add(move.toDate());
        }
        List<SwapDayCompareDto> days = new ArrayList<>();
        for (LocalDate date : dates) {
            List<PlanSnapshot.ScheduledLesson> lessons = snapshot.scheduleOn(date).ofClass(classId);
            Map<Long, GridCellDto> left = leftGrid(snapshot, lessons);
            Map<Long, GridCellDto> right = rightGrid(snapshot, lessons, date, moves);
            days.add(new SwapDayCompareDto(date,
                    marked(left, right, true), marked(left, right, false)));
        }
        return days;
    }

    private List<SwapChangeItemDto> changeItems(PlanSnapshot snapshot, List<PlanMove> moves) {
        return moves.stream()
                .map(move -> new SwapChangeItemDto(
                        move.entryId(),
                        move.classId(),
                        className(snapshot, move.classId()),
                        move.courseId(),
                        courseName(snapshot, move.courseId()),
                        move.fromDate(),
                        SubstitutionMapper.label(move.fromDayOfWeek(), move.fromPeriodNo()),
                        move.toDate(),
                        SubstitutionMapper.label(move.toDayOfWeek(), move.toPeriodNo()),
                        move.teacherId(),
                        teacherName(snapshot, move.teacherId()),
                        move.roomId() == null ? null : roomName(snapshot, move.roomId()),
                        "SLOT_MOVED"))
                .toList();
    }

    /** 左侧（调整前最新）的格子投影。 */
    private Map<Long, GridCellDto> leftGrid(PlanSnapshot snapshot,
                                            List<PlanSnapshot.ScheduledLesson> lessons) {
        Map<Long, GridCellDto> map = new LinkedHashMap<>();
        for (PlanSnapshot.ScheduledLesson lesson : lessons) {
            map.put(lesson.periodId(), new GridCellDto(
                    lesson.entryId(), lesson.periodNo(),
                    SubstitutionMapper.label(lesson.dayOfWeek(), lesson.periodNo()),
                    courseName(snapshot, lesson.courseId()),
                    className(snapshot, lesson.classId()),
                    lesson.teacherId(), teacherName(snapshot, lesson.teacherId()),
                    lesson.roomId() == null ? null : roomName(snapshot, lesson.roomId()),
                    false));
        }
        return map;
    }

    /** 右侧（应用整环方案后）的格子投影：移出来源、加入落点。 */
    private Map<Long, GridCellDto> rightGrid(PlanSnapshot snapshot,
                                             List<PlanSnapshot.ScheduledLesson> lessons,
                                             LocalDate date, List<PlanMove> moves) {
        Map<Long, GridCellDto> map = new LinkedHashMap<>();
        for (PlanSnapshot.ScheduledLesson lesson : lessons) {
            boolean movedOut = moves.stream().anyMatch(move ->
                    move.entryId() == lesson.entryId() && move.fromDate().equals(date));
            if (movedOut) {
                continue;
            }
            map.put(lesson.periodId(), new GridCellDto(
                    lesson.entryId(), lesson.periodNo(),
                    SubstitutionMapper.label(lesson.dayOfWeek(), lesson.periodNo()),
                    courseName(snapshot, lesson.courseId()),
                    className(snapshot, lesson.classId()),
                    lesson.teacherId(), teacherName(snapshot, lesson.teacherId()),
                    lesson.roomId() == null ? null : roomName(snapshot, lesson.roomId()),
                    false));
        }
        for (PlanMove move : moves) {
            if (!move.toDate().equals(date)) {
                continue;
            }
            var period = snapshot.period(move.toPeriodId());
            map.put(move.toPeriodId(), new GridCellDto(
                    move.entryId(),
                    period == null ? move.toPeriodNo() : period.periodNo(),
                    SubstitutionMapper.label(move.toDayOfWeek(), move.toPeriodNo()),
                    courseName(snapshot, move.courseId()),
                    className(snapshot, move.classId()),
                    move.teacherId(), teacherName(snapshot, move.teacherId()),
                    move.roomId() == null ? null : roomName(snapshot, move.roomId()),
                    false));
        }
        return map;
    }

    /**
     * 来源格/目标格/教师变化高亮：同一槽位两侧不一致（格子消失、换课、换教师、
     * 换教室）即标记 changed，清单与课表呈现同一方案投影。
     */
    private List<GridCellDto> marked(Map<Long, GridCellDto> left, Map<Long, GridCellDto> right,
                                     boolean beforeSide) {
        Map<Long, GridCellDto> own = beforeSide ? left : right;
        Map<Long, GridCellDto> other = beforeSide ? right : left;
        List<GridCellDto> cells = new ArrayList<>();
        for (Map.Entry<Long, GridCellDto> entry : own.entrySet()) {
            GridCellDto cell = entry.getValue();
            boolean changed = !sameCell(cell, other.get(entry.getKey()));
            cells.add(changed ? new GridCellDto(cell.entryId(), cell.periodNo(), cell.periodLabel(),
                    cell.courseName(), cell.className(), cell.teacherId(), cell.teacherName(),
                    cell.roomName(), true) : cell);
        }
        cells.sort(Comparator.comparingInt(GridCellDto::periodNo));
        return cells;
    }

    private boolean sameCell(GridCellDto cell, GridCellDto other) {
        return other != null
                && Objects.equals(cell.entryId(), other.entryId())
                && Objects.equals(cell.teacherId(), other.teacherId())
                && Objects.equals(cell.roomName(), other.roomName());
    }

    private String label(PlanSnapshot.ScheduledLesson lesson) {
        return SubstitutionMapper.label(lesson.dayOfWeek(), lesson.periodNo());
    }

    private static String className(PlanSnapshot snapshot, long classId) {
        var view = snapshot.classes().get(classId);
        return view == null ? null : view.name();
    }

    private static String courseName(PlanSnapshot snapshot, long courseId) {
        var view = snapshot.courses().get(courseId);
        return view == null ? null : view.name();
    }

    private static String teacherName(PlanSnapshot snapshot, long teacherId) {
        var view = snapshot.teacher(teacherId);
        return view == null ? null : view.name();
    }

    private static String roomName(PlanSnapshot snapshot, long roomId) {
        var view = snapshot.rooms().get(roomId);
        return view == null ? null : view.name();
    }
}
