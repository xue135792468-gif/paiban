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
        return createCoverSubstitution(request, substituteTeacherId, null);
    }

    /**
     * COVER 生效行。{@code baseRow} 非空时（修订链，票 04/07）继承它对槽位与教室的
     * 覆盖：基准是 SWAP 行时（调课后代课），实例的当前槽位 = SWAP 的落点槽——
     * COVER 行必须完整描述该实例的当前有效安排，否则「最新行遮蔽」读模型会把
     * 实例退回基线槽（SWAP 的移出/落点语义丢失）。
     */
    public Substitution createCoverSubstitution(
            SubstitutionRequest request, Long substituteTeacherId, Substitution baseRow) {
        Substitution substitution = new Substitution();
        substitution.setSemesterId(request.getSemesterId());
        substitution.setRequestId(request.getId());
        substitution.setEntryId(request.getEntryId());
        substitution.setEffectiveDate(request.getEffectiveDate());
        // 新格式：代课只影响所指的授课实例（学期 + 条目 + 原始授课日期）。
        substitution.setLessonDate(request.getEffectiveDate());
        substitution.setOriginalTeacherId(request.getOriginalTeacherId());
        substitution.setSubstituteTeacherId(substituteTeacherId);
        if (baseRow != null) {
            substitution.setNewPeriodId(baseRow.getNewPeriodId());
            substitution.setNewDayOfWeek(baseRow.getNewDayOfWeek());
            substitution.setNewPeriodNo(baseRow.getNewPeriodNo());
            substitution.setNewRoomId(baseRow.getNewRoomId());
        }
        substitution.setType(SubstitutionMapper.typeOf(request));
        substitution.setStatus(Substitution.EFFECTIVE);
        substitution.setCreatedAt(Instant.now());
        return substitutions.save(substitution);
    }

    /** One row per moved lesson; the teacher follows the lesson. */
    public Substitution createSwapSubstitution(
            SubstitutionRequest request, Long planId, PlanMove move, Instant now) {
        return createSwapSubstitution(request, planId, move, move.fromDate(), now);
    }

    /**
     * SWAP 生效行（票 07 净差行语义）：{@code lessonDate} = 实例的基线来源日。
     * 首次调课时它就是本次来源日（当前实际 = 基线）；修订调课时继承基准 SWAP 行的
     * 基线来源日——这样基准行转 SUPERSEDED 后，「来源日移出」的语义由净差行接管，
     * 读模型的整条移动链仍然闭合。落点完全回到基线槽的恢复环成员不写行
     * （调用方先经 {@link RevisionPolicy#restoresBaseline} 判断）。
     */
    public Substitution createSwapSubstitution(
            SubstitutionRequest request, Long planId, PlanMove move,
            java.time.LocalDate baselineLessonDate, Instant now) {
        Substitution substitution = new Substitution();
        substitution.setSemesterId(request.getSemesterId());
        substitution.setRequestId(request.getId());
        substitution.setEntryId(move.entryId());
        substitution.setEffectiveDate(move.fromDate());
        // 新格式：来源实例 = 基线来源日（移出），落点实例 = 目标日（旧实现丢失 toDate，只能反推周几）。
        substitution.setLessonDate(baselineLessonDate);
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

    /**
     * COVER 修订生效（票 04 建立，票 07 扩展为实例维度）：该实例（条目 + 授课日期）
     * 当前的非 SWAP 生效行退出「当前有效安排」，状态改为 SUPERSEDED。行保留、
     * 内容不改写——历史 A→B 仍完整可追溯，读模型只装载 EFFECTIVE 行，因此这次
     * 状态迁移就是原子的版本切换。SWAP 行永不由此方法替代：它的「来源日移出」
     * 分支承担整条移动链的读模型语义。{@code exceptRequestId} 排除本修订自己
     * 刚写入的生效行（同条目同日期，先建行后切换）。
     */
    public void supersedeCurrentCoverAt(Long entryId, java.time.LocalDate lessonDate,
                                        Long exceptRequestId) {
        substitutions.findFirstByEntryIdAndEffectiveDateAndStatus(entryId, lessonDate, Substitution.EFFECTIVE)
                .filter(row -> !Substitution.TYPE_SWAP.equals(row.getType()))
                .filter(row -> !row.getRequestId().equals(exceptRequestId))
                .ifPresent(row -> {
                    row.setStatus(Substitution.SUPERSEDED);
                    substitutions.save(row);
                });
    }

    /**
     * SWAP 修订生效（票 07）：本次方案涉及的每个实例的全部生效行退出「当前有效安排」。
     * 环成员的当前行可能分属不同申请（例：语文来自上次修订、数学来自更早的环），
     * 净差行以它们联合投影为起点计算，所以全部一并 SUPERSEDED；快照哈希校验
     * 已保证生成方案后没有任何并发改动。
     */
    public void supersedeEffectiveRowsOfEntries(java.util.Collection<Long> entryIds) {
        for (Long entryId : entryIds) {
            for (Substitution row : substitutions.findByEntryId(entryId)) {
                if (Substitution.EFFECTIVE.equals(row.getStatus())) {
                    row.setStatus(Substitution.SUPERSEDED);
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

    public void notifySwap(SubstitutionRequest request, List<PlanMove> moves) {
        // 修订调课（票 07）：文案注明本次取代的申请号，教师能对上版本变化。
        String revisionSuffix = request.getRevisesRequestId() == null
                ? "" : "，取代申请 #" + request.getRevisesRequestId();
        Map<Long, String> details = new LinkedHashMap<>();
        for (PlanMove move : moves) {
            String courseName = courses.findById(move.courseId()).map(Course::getName).orElse("课程");
            String text = courseName + " " + SubstitutionMapper.label(move.fromDayOfWeek(), move.fromPeriodNo())
                    + " → " + SubstitutionMapper.label(move.toDayOfWeek(), move.toPeriodNo())
                    + "（" + move.toDate() + "）" + revisionSuffix;
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
        // 修订通知（票 04）：前教师 = 调整前实际教师；首次代课没有记录时退回基线原教师。
        Long previousTeacherId = request.getPreviousTeacherId() != null
                ? request.getPreviousTeacherId() : request.getOriginalTeacherId();
        variables.put("previousTeacher", teachers.findById(previousTeacherId)
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
