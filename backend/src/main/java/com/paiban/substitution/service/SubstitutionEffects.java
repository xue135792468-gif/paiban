package com.paiban.substitution.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Room;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.SemesterRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.notification.service.NotificationService;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionHistory;
import com.paiban.substitution.domain.SubstitutionHistoryRepository;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.plan.PlanMove;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The side effects every substitution write shares: the semester row lock, the
 * substitution row, the status history and the notifications. Both the cover and
 * the swap path call this, so the two can never drift apart.
 */
@Component
public class SubstitutionEffects {

    private final SubstitutionRepository substitutions;
    private final SubstitutionHistoryRepository history;
    private final NotificationService notifications;
    private final SemesterRepository semesterRows;
    private final TimetableEntryRepository entries;
    private final AdminClassRepository adminClasses;
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final RoomRepository rooms;

    public SubstitutionEffects(
            SubstitutionRepository substitutions,
            SubstitutionHistoryRepository history,
            NotificationService notifications,
            SemesterRepository semesterRows,
            TimetableEntryRepository entries,
            AdminClassRepository adminClasses,
            CourseRepository courses,
            TeacherRepository teachers,
            RoomRepository rooms) {
        this.substitutions = substitutions;
        this.history = history;
        this.notifications = notifications;
        this.semesterRows = semesterRows;
        this.entries = entries;
        this.adminClasses = adminClasses;
        this.courses = courses;
        this.teachers = teachers;
        this.rooms = rooms;
    }

    /** Row lock on the semester: all substitution writes in it are serialised. */
    public void lockSemester(Long semesterId) {
        semesterRows.findWithLockById(semesterId)
                .orElseThrow(() -> ApiException.notFound("学期不存在: " + semesterId));
    }

    public Substitution createCoverSubstitution(
            SubstitutionRequest request, Long substituteTeacherId) {
        Substitution substitution = new Substitution();
        substitution.setSemesterId(request.getSemesterId());
        substitution.setRequestId(request.getId());
        substitution.setEntryId(request.getEntryId());
        substitution.setEffectiveDate(request.getEffectiveDate());
        // 新格式：代课只影响所指的授课实例（学期 + 条目 + 原始授课日期）。
        substitution.setLessonDate(request.getEffectiveDate());
        substitution.setOriginalTeacherId(request.getOriginalTeacherId());
        substitution.setSubstituteTeacherId(substituteTeacherId);
        substitution.setType(SubstitutionMapper.typeOf(request));
        substitution.setStatus(Substitution.EFFECTIVE);
        substitution.setCreatedAt(Instant.now());
        return substitutions.save(substitution);
    }

    /** One row per moved lesson; the teacher follows the lesson. */
    public Substitution createSwapSubstitution(
            SubstitutionRequest request, Long planId, PlanMove move, Instant now) {
        Substitution substitution = new Substitution();
        substitution.setSemesterId(request.getSemesterId());
        substitution.setRequestId(request.getId());
        substitution.setEntryId(move.entryId());
        substitution.setEffectiveDate(move.fromDate());
        // 新格式：来源实例 = 移出日，落点实例 = 目标日（旧实现丢失 toDate，只能反推周几）。
        substitution.setLessonDate(move.fromDate());
        substitution.setTargetLessonDate(move.toDate());
        substitution.setOriginalTeacherId(move.teacherId());
        substitution.setSubstituteTeacherId(move.teacherId());
        substitution.setNewRoomId(move.roomId());
        substitution.setNewPeriodId(move.toPeriodId());
        substitution.setNewDayOfWeek(move.toDayOfWeek());
        substitution.setNewPeriodNo(move.toPeriodNo());
        substitution.setType(Substitution.TYPE_SWAP);
        substitution.setPlanId(planId);
        substitution.setStatus(Substitution.EFFECTIVE);
        substitution.setCreatedAt(now);
        return substitutions.save(substitution);
    }

    public void recordHistory(Long requestId, String from, String to, String actor, String comment) {
        SubstitutionHistory item = new SubstitutionHistory();
        item.setRequestId(requestId);
        item.setFromStatus(from);
        item.setToStatus(to);
        item.setActor(actor);
        item.setComment(comment);
        item.setCreatedAt(Instant.now());
        history.save(item);
    }

    public void notifyEffective(SubstitutionRequest request, Substitution substitution) {
        notifications.substitutionEffective(request, substitution, variables(request));
    }

    public void notifySwap(SubstitutionRequest request, List<PlanMove> moves) {
        Map<Long, String> details = new LinkedHashMap<>();
        for (PlanMove move : moves) {
            String courseName = courses.findById(move.courseId()).map(Course::getName).orElse("课程");
            String text = courseName + " " + SubstitutionMapper.label(move.fromDayOfWeek(), move.fromPeriodNo())
                    + " → " + SubstitutionMapper.label(move.toDayOfWeek(), move.toPeriodNo())
                    + "（" + move.toDate() + "）";
            details.merge(move.teacherId(), text, (left, right) -> left + "；" + right);
        }
        String summary = details.values().stream().collect(Collectors.joining("；"));
        notifications.swapEffective(request, details, summary);
    }

    public Map<String, Object> variables(SubstitutionRequest request) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("className", adminClasses.findById(request.getAdminClassId())
                .map(AdminClass::getName).orElse(""));
        variables.put("courseName", courses.findById(request.getCourseId())
                .map(Course::getName).orElse(""));
        variables.put("date", request.getEffectiveDate().toString());
        variables.put("periodLabel", SubstitutionMapper.label(request.getDayOfWeek(), request.getPeriodNo()));
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
}
