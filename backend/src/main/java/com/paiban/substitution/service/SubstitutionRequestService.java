package com.paiban.substitution.service;

import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import com.paiban.notification.service.NotificationService;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionHistoryRepository;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.substitution.plan.PlanMove;
import com.paiban.substitution.plan.PlanSnapshot;
import com.paiban.substitution.plan.PlanVerifier;
import com.paiban.substitution.web.SubstitutionDtos.CreateSubstitutionRequest;
import com.paiban.substitution.web.SubstitutionDtos.DecisionRequest;
import com.paiban.substitution.web.SubstitutionDtos.HistoryDto;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionRequestDto;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
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
    private final SubstitutionRepository substitutions;
    private final TimetableEntryRepository entries;
    private final AdminClassRepository adminClasses;
    private final TeacherRepository teachers;
    private final RoomRepository rooms;
    private final PeriodRepository periods;
    private final SemesterService semesters;
    private final UserService users;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final PlanSnapshotFactory snapshots;
    private final SubstitutionAuditService audits;
    private final SubstitutionEffects effects;
    private final SubstitutionMapper mapper;

    public SubstitutionRequestService(
            SubstitutionRequestRepository requests,
            SubstitutionHistoryRepository history,
            SubstitutionRepository substitutions,
            TimetableEntryRepository entries,
            AdminClassRepository adminClasses,
            TeacherRepository teachers,
            RoomRepository rooms,
            PeriodRepository periods,
            SemesterService semesters,
            UserService users,
            SettingsService settings,
            NotificationService notifications,
            PlanSnapshotFactory snapshots,
            SubstitutionAuditService audits,
            SubstitutionEffects effects,
            SubstitutionMapper mapper) {
        this.requests = requests;
        this.history = history;
        this.substitutions = substitutions;
        this.entries = entries;
        this.adminClasses = adminClasses;
        this.teachers = teachers;
        this.rooms = rooms;
        this.periods = periods;
        this.semesters = semesters;
        this.users = users;
        this.settings = settings;
        this.notifications = notifications;
        this.snapshots = snapshots;
        this.audits = audits;
        this.effects = effects;
        this.mapper = mapper;
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
            SubstitutionLookups.requireTeacher(teachers, body.expectedSubstituteTeacherId());
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
        effects.recordHistory(saved.getId(), null, SubstitutionRequest.SUBMITTED, username, null);

        if (direct && Substitution.TYPE_COVER.equals(type)) {
            effects.lockSemester(semesterId);
            verifyCoverOrReject(username, saved, body.expectedSubstituteTeacherId(), null, null);
            saved.setStatus(SubstitutionRequest.EFFECTIVE);
            saved.setUpdatedAt(Instant.now());
            effects.recordHistory(saved.getId(), SubstitutionRequest.SUBMITTED,
                    SubstitutionRequest.EFFECTIVE, username, null);
            Substitution substitution = effects.createCoverSubstitution(
                    saved, body.expectedSubstituteTeacherId());
            effects.notifyEffective(saved, substitution);
        } else {
            saved.setStatus(SubstitutionRequest.PENDING_APPROVAL);
            saved.setUpdatedAt(Instant.now());
            effects.recordHistory(saved.getId(), SubstitutionRequest.SUBMITTED,
                    SubstitutionRequest.PENDING_APPROVAL, username, null);
        }
        return mapper.toDto(saved);
    }

    @Transactional
    public SubstitutionRequestDto approve(Long requestId, String username, DecisionRequest decision) {
        SubstitutionRequest request = SubstitutionLookups.requireRequest(requests, requestId);
        if (!SubstitutionRequest.PENDING_APPROVAL.equals(request.getStatus())) {
            throw ApiException.conflict("该申请当前状态为 " + request.getStatus() + "，不能审批");
        }
        if (Substitution.TYPE_SWAP.equals(SubstitutionMapper.typeOf(request))) {
            throw ApiException.badRequest("调课请生成方案后整单批准：POST /substitution-plans/{id}/approve");
        }
        Long substituteTeacherId = decision != null && decision.substituteTeacherId() != null
                ? decision.substituteTeacherId()
                : request.getExpectedSubstituteTeacherId();
        if (substituteTeacherId == null) {
            throw ApiException.badRequest("请指定代课教师");
        }
        SubstitutionLookups.requireTeacher(teachers, substituteTeacherId);
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
        if (decision != null && decision.newPeriodId() != null
                && !decision.newPeriodId().equals(request.getPeriodId())) {
            throw ApiException.badRequest("代课只换教师，不换节次；换时间请使用调课");
        }
        effects.lockSemester(request.getSemesterId());
        verifyCoverOrReject(username, request, substituteTeacherId,
                decision == null ? null : decision.newRoomId(),
                decision == null ? null : decision.newPeriodId());

        AppUser approver = users.getByUsername(username);
        request.setStatus(SubstitutionRequest.APPROVED);
        request.setApproverId(approver.getId());
        request.setApprovedAt(Instant.now());
        request.setApprovalComment(decision == null ? null : decision.comment());
        request.setUpdatedAt(Instant.now());
        effects.recordHistory(
                requestId,
                SubstitutionRequest.PENDING_APPROVAL,
                SubstitutionRequest.APPROVED,
                username,
                decision == null ? null : decision.comment());

        Substitution substitution = effects.createCoverSubstitution(request, substituteTeacherId);
        if (decision != null && decision.newRoomId() != null) {
            if (!rooms.existsById(decision.newRoomId())) {
                throw ApiException.notFound("教室不存在: " + decision.newRoomId());
            }
            substitution.setNewRoomId(decision.newRoomId());
            substitutions.save(substitution);
        }
        request.setStatus(SubstitutionRequest.EFFECTIVE);
        request.setUpdatedAt(Instant.now());
        effects.recordHistory(
                requestId, SubstitutionRequest.APPROVED, SubstitutionRequest.EFFECTIVE, username, null);
        effects.notifyEffective(request, substitution);
        return mapper.toDto(request);
    }

    @Transactional
    public SubstitutionRequestDto reject(Long requestId, String username, String comment) {
        SubstitutionRequest request = SubstitutionLookups.requireRequest(requests, requestId);
        if (!SubstitutionRequest.PENDING_APPROVAL.equals(request.getStatus())) {
            throw ApiException.conflict("该申请当前状态为 " + request.getStatus() + "，不能驳回");
        }
        AppUser approver = users.getByUsername(username);
        request.setStatus(SubstitutionRequest.REJECTED);
        request.setApproverId(approver.getId());
        request.setApprovedAt(Instant.now());
        request.setApprovalComment(comment);
        request.setUpdatedAt(Instant.now());
        effects.recordHistory(
                requestId,
                SubstitutionRequest.PENDING_APPROVAL,
                SubstitutionRequest.REJECTED,
                username,
                comment);
        Map<String, Object> variables = effects.variables(request);
        variables.put("comment", comment == null ? "" : comment);
        notifications.substitutionRejected(request, variables);
        return mapper.toDto(request);
    }

    @Transactional(readOnly = true)
    public List<SubstitutionRequestDto> list(
            String username, Long semesterId, String status, boolean mineOnly) {
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
        return result.stream().map(mapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public SubstitutionRequestDto detail(String username, Long requestId) {
        SubstitutionRequest request = SubstitutionLookups.requireRequest(requests, requestId);
        AppUser user = users.getByUsername(username);
        if (!users.hasPermission(user, com.paiban.identity.domain.Permissions.SUBSTITUTION_VIEW)
                && !Objects.equals(user.getTeacherId(), request.getRequesterTeacherId())) {
            throw ApiException.forbidden("不能查看他人的调代申请");
        }
        return mapper.toDto(request);
    }

    @Transactional(readOnly = true)
    public List<HistoryDto> history(String username, Long requestId) {
        SubstitutionRequest request = SubstitutionLookups.requireRequest(requests, requestId);
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

    @Transactional(readOnly = true)
    public List<SubstitutionRequestDto> pending(String username) {
        return list(username, null, SubstitutionRequest.PENDING_APPROVAL, false);
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
