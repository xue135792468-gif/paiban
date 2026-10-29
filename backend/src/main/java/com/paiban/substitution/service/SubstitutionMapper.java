package com.paiban.substitution.service;

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
import com.paiban.identity.service.UserService;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionPlan;
import com.paiban.substitution.domain.SubstitutionPlanMove;
import com.paiban.substitution.domain.SubstitutionPlanMoveRepository;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.plan.PlanMove;
import com.paiban.substitution.plan.RankingPreset;
import com.paiban.substitution.web.SubstitutionDtos.PlanMoveDto;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionPlanDto;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionRecordDto;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionRequestDto;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns persisted rows into the shapes the HTTP layer returns. Kept out of the
 * write services so a DTO change never touches transaction logic.
 */
@Component
public class SubstitutionMapper {

    static final String[] DAY_NAMES = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final SubstitutionRepository substitutions;
    private final SubstitutionPlanMoveRepository planMoves;
    private final TimetableEntryRepository entries;
    private final AdminClassRepository adminClasses;
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final RoomRepository rooms;
    private final PeriodRepository periods;
    private final UserService users;

    public SubstitutionMapper(
            SubstitutionRepository substitutions,
            SubstitutionPlanMoveRepository planMoves,
            TimetableEntryRepository entries,
            AdminClassRepository adminClasses,
            CourseRepository courses,
            TeacherRepository teachers,
            RoomRepository rooms,
            PeriodRepository periods,
            UserService users) {
        this.substitutions = substitutions;
        this.planMoves = planMoves;
        this.entries = entries;
        this.adminClasses = adminClasses;
        this.courses = courses;
        this.teachers = teachers;
        this.rooms = rooms;
        this.periods = periods;
        this.users = users;
    }

    public SubstitutionRequestDto toDto(SubstitutionRequest request) {
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
                request.getTargetDate(),
                typeOf(request),
                request.getReason(),
                request.getStatus(),
                request.getApprovalMode(),
                approver,
                request.getApprovedAt(),
                request.getApprovalComment(),
                request.getCreatedAt(),
                request.getRevisesRequestId(),
                request.getRevisionKind());
    }

    public SubstitutionRecordDto toRecordDto(Substitution substitution) {
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
                substitution.getLessonDate(),
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

    public SubstitutionPlanDto toPlanDto(SubstitutionPlan plan) {
        List<PlanMoveDto> moves = planMoves.findByPlanIdOrderByMoveOrderAsc(plan.getId()).stream()
                .map(this::toPlanMoveDto)
                .toList();
        RankingPreset preset = RankingPreset.fromCode(plan.getRankingPreset());
        return new SubstitutionPlanDto(
                plan.getId(), plan.getRequestId(), plan.getStatus(), plan.getRankingPreset(),
                preset.label(), plan.getImpactDays(), plan.getImpactTeachers(), plan.getSoftPenalty(),
                plan.getCycleLength(), plan.isSameWeek(), plan.getCreatedAt(), plan.getExpiresAt(), moves);
    }

    public PlanMoveDto toPlanMoveDto(SubstitutionPlanMove move) {
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

    public SubstitutionPlanMove toMoveEntity(Long planId, PlanMove move) {
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

    public PlanMove toPlanMove(SubstitutionPlanMove entity) {
        return new PlanMove(
                entity.getEntryId(), entity.getClassId(), entity.getCourseId(), entity.getTeacherId(),
                entity.getRoomId(), entity.getFromPeriodId(), entity.getFromDayOfWeek(),
                entity.getFromPeriodNo(), entity.getFromDate(), entity.getToPeriodId(),
                entity.getToDayOfWeek(), entity.getToPeriodNo(), entity.getToDate(), entity.getMoveOrder());
    }

    public static String typeOf(SubstitutionRequest request) {
        return request.getType() == null ? Substitution.TYPE_COVER : request.getType();
    }

    public static String label(int dayOfWeek, int periodNo) {
        String day = dayOfWeek >= 1 && dayOfWeek <= 7 ? DAY_NAMES[dayOfWeek] : "周" + dayOfWeek;
        return day + "第" + periodNo + "节";
    }
}
