package com.paiban.substitution.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
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
import com.paiban.substitution.plan.PlanSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final AdminClassRepository adminClasses;
    private final CourseRepository courses;
    private final TeacherRepository teachers;

    public SubstitutionEffects(
            SubstitutionRepository substitutions,
            SubstitutionHistoryRepository history,
            NotificationService notifications,
            SemesterRepository semesterRows,
            AdminClassRepository adminClasses,
            CourseRepository courses,
            TeacherRepository teachers) {
        this.substitutions = substitutions;
        this.history = history;
        this.notifications = notifications;
        this.semesterRows = semesterRows;
        this.adminClasses = adminClasses;
        this.courses = courses;
        this.teachers = teachers;
    }

    /** Row lock on the semester: all substitution writes in it are serialised. */
    public void lockSemester(Long semesterId) {
        semesterRows.findWithLockById(semesterId)
                .orElseThrow(() -> ApiException.notFound("学期不存在: " + semesterId));
    }

    public Substitution createCoverSubstitution(
            SubstitutionRequest request, Long substituteTeacherId) {
        return createCoverSubstitution(request, substituteTeacherId, null);
    }

    /**
     * 代课生效行。{@code placement} 非空时沿用该实例当前的槽位：代课只换教师不换
     * 时间，实例被调课移到别的节次/日期后，覆盖行仍须完整描述它当前的落点，
     * 否则读模型会把这个实例退回基线槽。这里只搬槽位，不建修订链（无前驱申请、
     * 无修订类型）。
     */
    public Substitution createCoverSubstitution(
            SubstitutionRequest request, Long substituteTeacherId,
            PlanSnapshot.ScheduledLesson placement) {
        Substitution substitution = new Substitution();
        substitution.setSemesterId(request.getSemesterId());
        substitution.setRequestId(request.getId());
        substitution.setEntryId(request.getEntryId());
        substitution.setEffectiveDate(request.getEffectiveDate());
        // 新格式：代课只影响所指的授课实例（学期 + 条目 + 原始授课日期）。
        substitution.setLessonDate(request.getEffectiveDate());
        substitution.setOriginalTeacherId(request.getOriginalTeacherId());
        substitution.setSubstituteTeacherId(substituteTeacherId);
        if (placement != null) {
            substitution.setNewPeriodId(placement.periodId());
            substitution.setNewDayOfWeek(placement.dayOfWeek());
            substitution.setNewPeriodNo(placement.periodNo());
        }
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
        // 新格式：来源实例 = 基线来源日（移出），落点实例 = 目标日。
        substitution.setLessonDate(move.fromDate());
        substitution.setTargetLessonDate(move.toDate());
        substitution.setOriginalTeacherId(move.teacherId());
        substitution.setSubstituteTeacherId(move.teacherId());
        substitution.setNewPeriodId(move.toPeriodId());
        substitution.setNewDayOfWeek(move.toDayOfWeek());
        substitution.setNewPeriodNo(move.toPeriodNo());
        substitution.setType(Substitution.TYPE_SWAP);
        substitution.setPlanId(planId);
        substitution.setStatus(Substitution.EFFECTIVE);
        substitution.setCreatedAt(now);
        return substitutions.save(substitution);
    }

    /**
     * 该授课实例（条目 + 授课日期）的当前生效行退出「当前有效安排」：行保留、
     * 内容不改写，只把状态迁到 CANCELLED。命中口径与读模型一致——覆盖行看
     * lessonDate，调课行看落点 targetLessonDate。再次调整 = 旧行退出 + 新行生效，
     * 同一实例同一时刻只留一条 EFFECTIVE（不建修订链、不写 SUPERSEDED）。
     */
    public void retireEffectiveRowAt(Long entryId, LocalDate lessonDate) {
        for (Substitution row : substitutions.findByEntryId(entryId)) {
            if (!Substitution.EFFECTIVE.equals(row.getStatus())) {
                continue;
            }
            boolean sameInstance = lessonDate.equals(row.getLessonDate())
                    || lessonDate.equals(row.getTargetLessonDate());
            if (sameInstance) {
                row.setStatus(Substitution.CANCELLED);
                substitutions.save(row);
            }
        }
    }

    /**
     * 同一实例的旧生效行退出「当前有效安排」：行保留、内容不改写，只把状态迁到
     * CANCELLED，读模型只装载 EFFECTIVE 行，因此这次状态迁移就是原子的版本切换。
     */
    public void retireEffectiveRowsOfEntries(java.util.Collection<Long> entryIds) {
        for (Long entryId : entryIds) {
            for (Substitution row : substitutions.findByEntryId(entryId)) {
                if (Substitution.EFFECTIVE.equals(row.getStatus())) {
                    row.setStatus(Substitution.CANCELLED);
                    substitutions.save(row);
                }
            }
        }
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

    public void notifyApprovalApproved(SubstitutionRequest request) {
        notifications.approvalApproved(request, request.getApprovalComment());
    }

    public void notifyApprovalRejected(SubstitutionRequest request) {
        notifications.approvalRejected(request, request.getApprovalComment());
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
        notifications.swapEffective(request, details);
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
        // 修订通知（票 04）：前教师 = 调整前实际教师；首次代课没有记录时退回基线原教师。
        Long previousTeacherId = request.getPreviousTeacherId() != null
                ? request.getPreviousTeacherId() : request.getOriginalTeacherId();
        variables.put("previousTeacher", teachers.findById(previousTeacherId)
                .map(Teacher::getName).orElse(""));
        Long substituteId = request.getEntryId() == null
                ? request.getExpectedSubstituteTeacherId()
                : substitutions.findFirstByEntryIdAndEffectiveDateAndStatus(
                                request.getEntryId(), request.getEffectiveDate(), Substitution.EFFECTIVE)
                        .map(Substitution::getSubstituteTeacherId)
                        .orElse(request.getExpectedSubstituteTeacherId());
        variables.put("substituteTeacher", substituteId == null ? "" : teachers.findById(substituteId)
                .map(Teacher::getName).orElse(""));
        variables.put("reason", request.getReason() == null ? "" : request.getReason());
        return variables;
    }
}
