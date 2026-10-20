package com.paiban.substitution.service;

import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
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
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.substitution.web.SubstitutionDtos.CreateSubstitutionRequest;
import com.paiban.substitution.web.SubstitutionDtos.DecisionRequest;
import com.paiban.substitution.web.SubstitutionDtos.HistoryDto;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionRecordDto;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionRequestDto;
import com.paiban.substitution.web.SubstitutionDtos.TeacherStatsDto;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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

    private final SubstitutionRequestRepository requests;
    private final SubstitutionHistoryRepository history;
    private final SubstitutionRepository substitutions;
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

    public SubstitutionService(
            SubstitutionRequestRepository requests,
            SubstitutionHistoryRepository history,
            SubstitutionRepository substitutions,
            TimetableEntryRepository entries,
            AdminClassRepository adminClasses,
            CourseRepository courses,
            TeacherRepository teachers,
            RoomRepository rooms,
            PeriodRepository periods,
            SemesterService semesters,
            UserService users,
            SettingsService settings,
            NotificationService notifications) {
        this.requests = requests;
        this.history = history;
        this.substitutions = substitutions;
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
        boolean admin = user.hasRole("admin");
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
        request.setApprovalMode(direct ? "direct" : "approval");
        request.setStatus(SubstitutionRequest.SUBMITTED);
        request.setCreatedAt(Instant.now());
        request.setUpdatedAt(Instant.now());
        SubstitutionRequest saved = requests.save(request);
        recordHistory(saved.getId(), null, SubstitutionRequest.SUBMITTED, username, null);

        if (direct) {
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
    public SubstitutionRequestDto approve(Long requestId, String username, DecisionRequest decision) {
        SubstitutionRequest request = requireRequest(requestId);
        if (!SubstitutionRequest.PENDING_APPROVAL.equals(request.getStatus())) {
            throw ApiException.conflict("该申请当前状态为 " + request.getStatus() + "，不能审批");
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
        boolean admin = user.hasRole("admin");
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
        if (!user.hasRole("admin") && !Objects.equals(user.getTeacherId(), request.getRequesterTeacherId())) {
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
        if (!user.hasRole("admin") && !requester && !substitute && !headTeacher) {
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
            Long semesterId, Long teacherId, Long classId, LocalDate dateFrom, LocalDate dateTo) {
        Long resolved = semesterId != null ? semesterId : semesters.requireCurrent().getId();
        List<Substitution> result = substitutions.findBySemesterIdAndStatus(resolved, Substitution.EFFECTIVE);
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
        long out = substitutions.countByOriginalTeacherIdAndStatus(teacherId, Substitution.EFFECTIVE);
        long in = substitutions.countBySubstituteTeacherIdAndStatus(teacherId, Substitution.EFFECTIVE);
        Teacher teacher = teachers.findById(teacherId).orElse(null);
        return new TeacherStatsDto(teacherId, teacher == null ? null : teacher.getName(), out, in);
    }

    private Substitution createSubstitution(SubstitutionRequest request, Long substituteTeacherId) {
        Substitution substitution = new Substitution();
        substitution.setSemesterId(request.getSemesterId());
        substitution.setRequestId(request.getId());
        substitution.setEntryId(request.getEntryId());
        substitution.setEffectiveDate(request.getEffectiveDate());
        substitution.setOriginalTeacherId(request.getOriginalTeacherId());
        substitution.setSubstituteTeacherId(substituteTeacherId);
        substitution.setStatus(Substitution.EFFECTIVE);
        substitution.setCreatedAt(Instant.now());
        return substitutions.save(substitution);
    }

    private void notifyEffective(SubstitutionRequest request, Substitution substitution) {
        notifications.substitutionEffective(request, substitution, variables(request));
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
        return new SubstitutionRecordDto(
                substitution.getId(),
                substitution.getRequestId(),
                substitution.getEntryId(),
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
                substitution.getCreatedAt());
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
        return records(null, user.getTeacherId(), null, null, null);
    }

    @Transactional(readOnly = true)
    public List<SubstitutionRecordDto> allRecords() {
        return new ArrayList<>(records(null, null, null, null, null));
    }
}
