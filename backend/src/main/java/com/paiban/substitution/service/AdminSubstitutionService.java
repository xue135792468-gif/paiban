package com.paiban.substitution.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.Semester;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.service.UserService;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionPreview;
import com.paiban.substitution.domain.SubstitutionPreviewRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.plan.PlanMove;
import com.paiban.substitution.plan.PlanSnapshot;
import com.paiban.substitution.plan.PlanVerifier;
import com.paiban.substitution.service.LessonDeadline.ChangedSlot;
import com.paiban.substitution.web.SubstitutionDtos.DecisionRequest;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.CandidateDto;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.CandidateListDto;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.ChangeItemDto;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.GridCellDto;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.PreviewRequest;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.PreviewResponse;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.WorkbenchActionResult;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理调代工作台（票 03）：无教师绑定的管理员搜索教师、按实际日期选课、
 * 指定代课人、预览对照，然后提交审批或亲自确认。
 *
 * <p>红线：
 * <ul>
 *   <li>预览零申请、零生效、零通知；凭证是短期试算结果，不是待审批申请。</li>
 *   <li>提交审批始终进待批队列（即使教师自助模式是直接生效）；亲自确认在同一个
 *       事务里走与普通批准完全相同的 {@link SubstitutionRequestService#approve} 校验、
 *       审计与写入路径，不创建会自动生效的动作。</li>
 *   <li>确认前复核权限、凭证、快照版本与开课截止；获取学期行锁后重新读时钟。</li>
 *   <li>旧格式（lesson_date 为空）生效行覆盖的实例只读，数据级检查，不静默沿用。</li>
 * </ul>
 */
@Service
public class AdminSubstitutionService {

    private static final Duration PREVIEW_TTL = Duration.ofMinutes(30);

    /** 管理动作的权限组合（规格第 1 节）：全校课表查看 + 提交 + 审批；仅查看不可写。 */
    static final String[] MANAGER_PERMISSIONS = {
        Permissions.TIMETABLE_VIEW, Permissions.SUBSTITUTION_SUBMIT, Permissions.SUBSTITUTION_APPROVE,
    };

    private final SubstitutionPreviewRepository previews;
    private final SubstitutionRequestRepository requests;
    private final SubstitutionRepository substitutions;
    private final TimetableEntryRepository entries;
    private final CourseRepository courses;
    private final PeriodRepository periods;
    private final TeacherRepository teachers;
    private final SemesterService semesters;
    private final UserService users;
    private final PlanSnapshotFactory snapshots;
    private final SubstitutionRequestService requestService;
    private final SubstitutionEffects effects;
    private final AuditLogRepository auditLogs;
    private final java.time.Clock clock;

    public AdminSubstitutionService(
            SubstitutionPreviewRepository previews,
            SubstitutionRequestRepository requests,
            SubstitutionRepository substitutions,
            TimetableEntryRepository entries,
            CourseRepository courses,
            PeriodRepository periods,
            TeacherRepository teachers,
            SemesterService semesters,
            UserService users,
            PlanSnapshotFactory snapshots,
            SubstitutionRequestService requestService,
            SubstitutionEffects effects,
            AuditLogRepository auditLogs,
            java.time.Clock clock) {
        this.previews = previews;
        this.requests = requests;
        this.substitutions = substitutions;
        this.entries = entries;
        this.courses = courses;
        this.periods = periods;
        this.teachers = teachers;
        this.semesters = semesters;
        this.users = users;
        this.snapshots = snapshots;
        this.requestService = requestService;
        this.effects = effects;
        this.auditLogs = auditLogs;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- candidates

    /**
     * 候选代课教师：启用教师，排除当前实际教师，同科优先、跨科提示，
     * 冲突原因可见。这里只提示不裁决——硬冲突由确认时的校验器拒绝。
     */
    @Transactional(readOnly = true)
    public CandidateListDto candidates(String username, Long entryId, LocalDate lessonDate) {
        AppUser user = users.getByUsername(username);
        requireManager(user);
        Instance instance = loadInstance(entryId, lessonDate);
        PlanSnapshot snapshot = instance.snapshot();
        PlanSnapshot.ScheduledLesson current = instance.current();
        List<CandidateDto> candidateList = buildCandidates(instance, current);
        return new CandidateListDto(
                entryId,
                lessonDate,
                current.classId(),
                className(snapshot, current.classId()),
                current.courseId(),
                courseName(snapshot, current.courseId()),
                instance.courseSubject(),
                current.teacherId(),
                teacherName(snapshot, current.teacherId()),
                instance.entry().getTeacherId(),
                teacherName(snapshot, instance.entry().getTeacherId()),
                current.periodId(),
                current.periodNo(),
                SubstitutionMapper.label(current.dayOfWeek(), current.periodNo()),
                hasLegacyCover(entryId, lessonDate),
                activeRequestFor(entryId, lessonDate),
                candidateList);
    }

    private List<CandidateDto> buildCandidates(Instance instance, PlanSnapshot.ScheduledLesson current) {
        PlanSnapshot snapshot = instance.snapshot();
        PlanSnapshot.DaySchedule day = snapshot.scheduleOn(instance.lessonDate());
        List<CandidateDto> list = new ArrayList<>();
        for (var teacher : snapshot.teachers().values()) {
            if (teacher.id() == current.teacherId()) {
                continue;
            }
            boolean active = teachers.findById(teacher.id()).map(Teacher::isActive).orElse(false);
            if (!active) {
                continue;
            }
            boolean busy = day.teacherBusy(teacher.id(), current.periodId(), current.entryId());
            String busyReason = null;
            if (busy) {
                var clash = day.lessons().stream()
                        .filter(lesson -> lesson.teacherId() == teacher.id()
                                && lesson.periodId() == current.periodId())
                        .findFirst().orElse(null);
                busyReason = "该时段已有课："
                        + (clash == null ? "" : className(snapshot, clash.classId()) + " ")
                        + (clash == null ? "" : courseName(snapshot, clash.courseId()) + " ")
                        + SubstitutionMapper.label(current.dayOfWeek(), current.periodNo());
            }
            boolean sameSubject = instance.sameSubject(teacher.subject());
            list.add(new CandidateDto(
                    teacher.id(), teacher.name(), teacher.subject(),
                    sameSubject, busy, busyReason, !sameSubject));
        }
        // 同科优先、空闲优先；跨科不禁止，只是排后面并带提示。
        list.sort(Comparator
                .comparing(CandidateDto::crossSubject)
                .thenComparing(CandidateDto::busyAtPeriod)
                .thenComparing(CandidateDto::name, Comparator.nullsLast(String::compareTo)));
        return list;
    }

    // ------------------------------------------------------------------- preview

    /** 创建预览凭证：零申请、零生效、零通知；返回权威截止时间与左右对照。 */
    @Transactional
    public PreviewResponse preview(String username, PreviewRequest body) {
        AppUser user = users.getByUsername(username);
        requireManager(user);
        Instance instance = loadInstance(body.entryId(), body.lessonDate());
        PlanSnapshot snapshot = instance.snapshot();
        PlanSnapshot.ScheduledLesson current = instance.current();
        if (body.substituteTeacherId() == null) {
            throw ApiException.badRequest("请选择代课教师");
        }
        if (body.substituteTeacherId().equals(current.teacherId())) {
            throw ApiException.badRequest("代课教师不能与当前实际教师相同");
        }
        var substituteView = snapshot.teacher(body.substituteTeacherId());
        if (substituteView == null) {
            throw ApiException.notFound("代课教师不存在: " + body.substituteTeacherId());
        }
        if (!teachers.findById(body.substituteTeacherId()).map(Teacher::isActive).orElse(false)) {
            throw ApiException.badRequest("代课教师已停用，请选择启用中的教师");
        }

        // 锁定状态检查：旧格式只读（提示转换状态）、已有待处理单先挡下。
        // 已有生效代课的实例走修订链（票 04）：预览产出「修订」而非冲突。
        if (instance.legacyLocked()) {
            throw ApiException.conflict(instance.legacyMessage());
        }
        var baseRow = currentCoverRow(instance);
        Long revisesRequestId = baseRow.map(Substitution::getRequestId).orElse(null);
        String revisionKind = baseRow.isPresent()
                ? RevisionPolicy.revisionKind(body.substituteTeacherId(), instance.entry().getTeacherId())
                : null;
        Long raced = activeRequestFor(body.entryId(), body.lessonDate());
        if (raced != null) {
            throw ApiException.conflict("该课节已有待处理的申请（#" + raced + "），请先处理该单");
        }

        // 开课截止：COVER 保持当前日期与节次，当前 = 拟生效，最小值就是这节课的开课时刻。
        Instant deadline = instance.deadline();
        new DeadlineChecker(clock::instant).requireBeforeDeadline(deadline, instance.lessonLabel());

        PlanMove move = new PlanMove(
                current.entryId(), current.classId(), current.courseId(),
                body.substituteTeacherId(), current.roomId(),
                current.periodId(), current.dayOfWeek(), current.periodNo(), body.lessonDate(),
                current.periodId(), current.dayOfWeek(), current.periodNo(), body.lessonDate(), 0);
        PlanVerifier.Result verification = PlanVerifier.verify(
                snapshot, List.of(move), PlanVerifier.Kind.COVER, -1);
        // 校验问题只是预览提示；硬冲突以确认时的服务端裁决为准。
        List<String> issues = verification.issues().stream()
                .map(issue -> issue.code() + ": " + issue.message())
                .toList();

        Instant now = clock.instant();
        SubstitutionPreview voucher = new SubstitutionPreview();
        voucher.setSemesterId(instance.semesterId());
        voucher.setEntryId(body.entryId());
        voucher.setLessonDate(body.lessonDate());
        voucher.setSubstituteTeacherId(body.substituteTeacherId());
        voucher.setPreviousTeacherId(current.teacherId());
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

        boolean sameSubject = instance.sameSubject(substituteView.subject());
        return new PreviewResponse(
                saved.getId(),
                deadline,
                saved.getExpiresAt(),
                body.lessonDate(),
                current.classId(),
                className(snapshot, current.classId()),
                current.entryId(),
                current.teacherId(),
                teacherName(snapshot, current.teacherId()),
                body.substituteTeacherId(),
                substituteView.name(),
                sameSubject,
                gridFor(snapshot, current.classId(), body.lessonDate(), current.entryId(), null),
                gridFor(snapshot, current.classId(), body.lessonDate(), current.entryId(),
                        body.substituteTeacherId()),
                List.of(changeItem(snapshot, body.lessonDate(), current,
                        body.substituteTeacherId(), substituteView.name())),
                issues,
                true,
                true,
                revisesRequestId,
                revisionKind);
    }

    private Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    // ----------------------------------------------------------- submit / confirm

    /** 管理员代发：提交审批进待批队列，旧安排继续生效，不发已生效通知。 */
    @Transactional
    public WorkbenchActionResult submitApproval(Long voucherId, String username, String comment) {
        return consumeVoucher(voucherId, username,
                SubstitutionPreview.ACTION_SUBMIT_APPROVAL, comment, false);
    }

    /**
     * 管理员亲自确认：在同一事务里创建待批申请，然后走与普通批准完全相同的
     * approve 路径（busy 检查、学期行锁、校验器、生效行、审计与通知）。
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

        Instance instance = loadInstance(voucher.getEntryId(), voucher.getLessonDate());
        if (effectiveNow) {
            // 确认路径：先拿学期行锁（可能要等其他管理员提交），拿到锁后再重读时钟、
            // 重建快照——排队期间课表可能已被别人改动，一切复核都以锁后状态为准。
            instance.lockSemester();
        }
        new DeadlineChecker(clock::instant)
                .requireBeforeDeadline(voucher.getDeadlineAt(), instance.lessonLabel());
        PlanSnapshot snapshot = snapshots.build(instance.semesterId());
        if (!snapshot.snapshotHash().equals(voucher.getSnapshotHash())) {
            // 条件过期：即使与并发的消费请求竞争，也不会覆盖对方写入的消费结果。
            previews.markExpiredIfActive(voucher.getId());
            throw ApiException.conflict("课表已变化，旧预览不能生效，请重新预览");
        }
        if (instance.legacyLocked()) {
            throw ApiException.conflict(instance.legacyMessage());
        }
        var currentRow = currentCoverRow(instance);
        if (currentRow.isPresent()) {
            // 实例已有生效代课：只放行「修订基准仍然一致」的修订凭证。
            // 快照哈希校验已保证整个课表未变，这里再钉住基准申请，防止任何旁路。
            Substitution base = currentRow.get();
            if (voucher.getRevisesRequestId() == null
                    || !voucher.getRevisesRequestId().equals(base.getRequestId())) {
                previews.markExpiredIfActive(voucher.getId());
                throw ApiException.conflict("该课节的当前安排已变化，旧预览不能生效，请重新预览");
            }
        } else if (voucher.getRevisesRequestId() != null) {
            // 修订的基准已消失（数据被重新发布等）：不能盲替，回到重新预览。
            previews.markExpiredIfActive(voucher.getId());
            throw ApiException.conflict("该修订的基准安排已不存在，请重新预览");
        }
        Long raced = activeRequestFor(voucher.getEntryId(), voucher.getLessonDate());
        if (raced != null) {
            previews.markExpiredIfActive(voucher.getId());
            throw ApiException.conflict("该课节已有待处理的申请（#" + raced + "），请先处理该单");
        }

        SubstitutionRequest request = createDispatchRequest(user, voucher, instance);
        WorkbenchActionResult result;
        if (effectiveNow) {
            // 与普通批准完全同路径：busy/重复生效检查、校验器、生效行、历史与通知都在 approve 里，
            // 全部发生在当前事务内；失败则整单回滚、凭证不被消费。
            requestService.approve(request.getId(), username,
                    new DecisionRequest(voucher.getSubstituteTeacherId(), comment, null, null));
            SubstitutionRequest effective = SubstitutionLookups.requireRequest(requests, request.getId());
            // 调整后教师如实记录在申请上（批准结果可能与最初期望一致，此处统一落库）。
            effective.setFinalTeacherId(voucher.getSubstituteTeacherId());
            effective.setExpectedSubstituteTeacherId(voucher.getSubstituteTeacherId());
            effective.setUpdatedAt(clock.instant());
            requests.save(effective);
            auditLogs.save(new AuditLog(
                    username, "SUBSTITUTION_ADMIN_CONFIRMED", "SUBSTITUTION",
                    auditDetail(user, effective, voucher), 1, 0));
            result = new WorkbenchActionResult(request.getId(), SubstitutionRequest.EFFECTIVE, false);
        } else {
            auditLogs.save(new AuditLog(
                    username, "SUBSTITUTION_ADMIN_DISPATCHED", "SUBSTITUTION",
                    auditDetail(user, request, voucher), 1, 0));
            result = new WorkbenchActionResult(request.getId(), SubstitutionRequest.PENDING_APPROVAL, false);
        }
        voucher.consume(action, actionHash, result.toJson(), clock.instant());
        previews.save(voucher);
        return result;
    }

    /** 代发申请的公共部分：代发对象 = 基线原任课教师；操作者、调整前教师如实记录。 */
    private SubstitutionRequest createDispatchRequest(
            AppUser operator, SubstitutionPreview voucher, Instance instance) {
        PlanSnapshot.ScheduledLesson current = instance.current();
        TimetableEntry entry = instance.entry();
        SubstitutionRequest request = new SubstitutionRequest();
        request.setSemesterId(instance.semesterId());
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
        request.setReason(voucher.getReason());
        request.setExpectedSubstituteTeacherId(voucher.getSubstituteTeacherId());
        request.setType(Substitution.TYPE_COVER);
        // 修订链（票 04）：修订申请记录前驱与类型；首次代课两者皆为空。
        request.setRevisesRequestId(voucher.getRevisesRequestId());
        request.setRevisionKind(voucher.getRevisionKind());
        // 管理员的提交审批与亲自确认都不依赖全局教师自助模式：前者始终待批，后者显式批准。
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

    private String auditDetail(AppUser operator, SubstitutionRequest request, SubstitutionPreview voucher) {
        return "operator=" + operator.getUsername()
                + ", operatorUserId=" + operator.getId()
                + ", onBehalfOfTeacherId=" + request.getRequesterTeacherId()
                + ", baselineTeacherId=" + request.getOriginalTeacherId()
                + ", previousTeacherId=" + request.getPreviousTeacherId()
                + ", newTeacherId=" + voucher.getSubstituteTeacherId()
                + ", entryId=" + request.getEntryId()
                + ", lessonDate=" + request.getEffectiveDate()
                + ", voucherId=" + voucher.getId();
    }

    // ------------------------------------------------------------------- helpers

    /**
     * 装载来源实例（学期 + 基线条目 + 授课日期）并一次性解析全部判定数据：
     * 当前实际安排、开课截止、学科与锁定状态。
     */
    private Instance loadInstance(Long entryId, LocalDate lessonDate) {
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
        return new Instance(entry, semester.getId(), lessonDate, snapshot, current, this);
    }

    /** 数据级旧格式检查：该条目存在 lesson_date 为空的 EFFECTIVE 行且覆盖该日期。 */
    private boolean hasLegacyCover(Long entryId, LocalDate lessonDate) {
        return substitutions.findByEntryIdAndStatusAndLessonDateIsNull(entryId, Substitution.EFFECTIVE)
                .stream()
                .anyMatch(row -> !row.getEffectiveDate().isAfter(lessonDate));
    }

    /**
     * 该实例当天的新格式生效行（票 04/07 修订链的基准）：同日 COVER 行优先，
     * 否则落点命中该日期的 SWAP 行（票 07：原先调课后的实例仍可再代课——
     * 预览产出以 SWAP 申请为基准的修订，而不是冲突）。SWAP 来源日实例当天已被
     * 移走（装载即失败），不会出现在这里。
     */
    private java.util.Optional<Substitution> currentCoverRow(Instance instance) {
        return SubstitutionLookups.currentInstanceRow(
                substitutions, instance.entryId(), instance.lessonDate());
    }

    /** 同一实例同一时间最多一笔活跃修改；返回待处理申请 id 供界面引导。 */
    private Long activeRequestFor(Long entryId, LocalDate lessonDate) {
        return requests.findByEntryIdAndEffectiveDate(entryId, lessonDate).stream()
                .filter(request -> SubstitutionRequest.SUBMITTED.equals(request.getStatus())
                        || SubstitutionRequest.PENDING_APPROVAL.equals(request.getStatus()))
                .map(SubstitutionRequest::getId)
                .findFirst()
                .orElse(null);
    }

    private void requireManager(AppUser user) {
        if (!users.hasAllPermissions(user, MANAGER_PERMISSIONS)) {
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

    /** 左（调整前）或右（代课后）的班级单日网格；substituteTeacherId 非空时替换源课教师。 */
    private List<GridCellDto> gridFor(PlanSnapshot snapshot, long classId, LocalDate date,
                                      long sourceEntryId, Long substituteTeacherId) {
        List<GridCellDto> cells = new ArrayList<>();
        for (PlanSnapshot.ScheduledLesson lesson : snapshot.scheduleOn(date).ofClass(classId)) {
            boolean isSource = lesson.entryId() == sourceEntryId;
            long teacherId = isSource && substituteTeacherId != null
                    ? substituteTeacherId : lesson.teacherId();
            cells.add(new GridCellDto(
                    lesson.entryId(),
                    lesson.periodNo(),
                    SubstitutionMapper.label(lesson.dayOfWeek(), lesson.periodNo()),
                    courseName(snapshot, lesson.courseId()),
                    className(snapshot, lesson.classId()),
                    teacherId,
                    teacherName(snapshot, teacherId),
                    lesson.roomId() == null ? null : roomName(snapshot, lesson.roomId()),
                    isSource));
        }
        cells.sort(Comparator.comparingInt(GridCellDto::periodNo));
        return cells;
    }

    private ChangeItemDto changeItem(PlanSnapshot snapshot, LocalDate lessonDate,
                                     PlanSnapshot.ScheduledLesson current,
                                     Long substituteTeacherId, String substituteTeacherName) {
        String label = SubstitutionMapper.label(current.dayOfWeek(), current.periodNo());
        String room = current.roomId() == null ? null : roomName(snapshot, current.roomId());
        return new ChangeItemDto(
                current.entryId(),
                current.classId(),
                className(snapshot, current.classId()),
                current.courseId(),
                courseName(snapshot, current.courseId()),
                lessonDate,
                label,
                label,
                current.teacherId(),
                teacherName(snapshot, current.teacherId()),
                substituteTeacherId,
                substituteTeacherName,
                room,
                room,
                "TEACHER_CHANGED");
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

    /**
     * 一次预览绑定的一节课实例：基线条目 + 学期 + 授课日期，及其当前实际安排、
     * 开课截止与学科。所有判定数据在装载时解析完成。
     */
    private static final class Instance {

        private final TimetableEntry entry;
        private final Long semesterId;
        private final LocalDate lessonDate;
        private final PlanSnapshot snapshot;
        private final PlanSnapshot.ScheduledLesson current;
        private final AdminSubstitutionService owner;
        private final Instant deadline;
        private final String courseSubject;

        private Instance(TimetableEntry entry, Long semesterId, LocalDate lessonDate,
                         PlanSnapshot snapshot, PlanSnapshot.ScheduledLesson current,
                         AdminSubstitutionService owner) {
            this.entry = entry;
            this.semesterId = semesterId;
            this.lessonDate = lessonDate;
            this.snapshot = snapshot;
            this.current = current;
            this.owner = owner;
            this.courseSubject = owner.courseSubject(entry.getCourseId());
            this.deadline = owner.deadline(lessonDate, current.periodId());
        }

        TimetableEntry entry() {
            return entry;
        }

        Long entryId() {
            return entry.getId();
        }

        Long semesterId() {
            return semesterId;
        }

        LocalDate lessonDate() {
            return lessonDate;
        }

        PlanSnapshot snapshot() {
            return snapshot;
        }

        PlanSnapshot.ScheduledLesson current() {
            return current;
        }

        Instant deadline() {
            return deadline;
        }

        String courseSubject() {
            return courseSubject;
        }

        boolean sameSubject(String teacherSubject) {
            return owner.sameSubject(courseSubject, teacherSubject);
        }

        void lockSemester() {
            owner.effects.lockSemester(semesterId);
        }

        String lessonLabel() {
            return SubstitutionMapper.label(current.dayOfWeek(), current.periodNo())
                    + "（" + lessonDate + "）";
        }

        String legacyMessage() {
            return "该课节当前生效的是旧格式调代记录（无授课实例日期，历史数据），"
                    + "需先完成历史转换才能在管理端修改，本次操作被拒绝";
        }

        boolean legacyLocked() {
            return owner.hasLegacyCover(entry.getId(), lessonDate);
        }
    }

    private Instant deadline(LocalDate lessonDate, Long periodId) {
        Period period = periods.findById(periodId)
                .orElseThrow(() -> ApiException.badRequest(
                        "无法确定开课时刻：课节 " + periodId + " 不存在，拒绝写入"));
        if (period.getStartTime() == null) {
            throw ApiException.badRequest(
                    "无法确定开课时刻：课节 " + periodId + " 缺少开始时间，拒绝写入");
        }
        try {
            return LessonDeadline.earliest(List.of(
                    new ChangedSlot(lessonDate, period.getStartTime())));
        } catch (LessonDeadline.MissingStartTimeException ex) {
            throw ApiException.badRequest(ex.getMessage());
        }
    }

    private String courseSubject(Long courseId) {
        Course course = courses.findById(courseId).orElse(null);
        if (course == null) {
            return null;
        }
        if (course.getSubject() != null && !course.getSubject().isBlank()) {
            return course.getSubject().trim();
        }
        return course.getName() == null || course.getName().isBlank() ? null : course.getName().trim();
    }

    private boolean sameSubject(String courseSubject, String teacherSubject) {
        return courseSubject != null && teacherSubject != null && !teacherSubject.isBlank()
                && courseSubject.equalsIgnoreCase(teacherSubject.trim());
    }
}
