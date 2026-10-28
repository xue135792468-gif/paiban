package com.paiban.substitution.plan;

import com.paiban.scheduling.constraint.ConstraintContext;
import com.paiban.scheduling.constraint.ConstraintContext.ClassView;
import com.paiban.scheduling.constraint.ConstraintContext.CourseView;
import com.paiban.scheduling.constraint.ConstraintContext.PeriodView;
import com.paiban.scheduling.constraint.ConstraintContext.RoomView;
import com.paiban.scheduling.constraint.ConstraintContext.TeacherView;
import com.paiban.scheduling.solver.ConstraintSettings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only, database-free view of a published timetable plus every effective
 * substitution in the semester. This is the only input the planner and verifier
 * see, which is what lets the CLI reuse both without Spring or a database.
 */
public record PlanSnapshot(long semesterId,
                           LocalDate semesterStart,
                           LocalDate semesterEnd,
                           Map<Long, PeriodView> periods,
                           Map<Long, RoomView> rooms,
                           Map<Long, CourseView> courses,
                           Map<Long, TeacherView> teachers,
                           Map<Long, ClassView> classes,
                           List<BaselineLesson> baseline,
                           List<AppliedSubstitution> substitutions,
                           ConstraintSettings settings) {

    public PlanSnapshot {
        baseline = List.copyOf(baseline);
        substitutions = List.copyOf(substitutions);
    }

    /** One published {@code timetable_entry}; the weekly baseline never changes. */
    public record BaselineLesson(long entryId,
                                 long classId,
                                 long courseId,
                                 long teacherId,
                                 Long roomId,
                                 long periodId,
                                 int dayOfWeek,
                                 int periodNo) {}

    /**
     * One effective overlay row. For SWAP the substitute teacher is the original.
     * {@code lessonDate} 非空 = 新格式日期实例（只影响该授课日期；SWAP 的落点在
     * {@code targetLessonDate}）；为 null = 旧格式历史行，按「effectiveDate 起持续覆盖」读取。
     */
    public record AppliedSubstitution(long id,
                                      long entryId,
                                      LocalDate effectiveDate,
                                      LocalDate lessonDate,
                                      LocalDate targetLessonDate,
                                      Instant createdAt,
                                      Long newPeriodId,
                                      Integer newDayOfWeek,
                                      Integer newPeriodNo,
                                      Long newRoomId,
                                      Long substituteTeacherId,
                                      String type) {}

    /** A lesson after the overlay has been applied, placed on one concrete date. */
    public record ScheduledLesson(long entryId,
                                  long classId,
                                  long courseId,
                                  long teacherId,
                                  Long roomId,
                                  long periodId,
                                  int dayOfWeek,
                                  int periodNo,
                                  int studentCount) {}

    public record DaySchedule(LocalDate date, List<ScheduledLesson> lessons) {

        public ScheduledLesson lessonAt(long classId, long periodId) {
            return lessons.stream()
                    .filter(lesson -> lesson.classId() == classId && lesson.periodId() == periodId)
                    .findFirst()
                    .orElse(null);
        }

        public List<ScheduledLesson> ofClass(long classId) {
            return lessons.stream().filter(lesson -> lesson.classId() == classId).toList();
        }

        public boolean teacherBusy(long teacherId, long periodId, long excludingEntryId) {
            return lessons.stream().anyMatch(lesson -> lesson.teacherId() == teacherId
                    && lesson.periodId() == periodId
                    && lesson.entryId() != excludingEntryId);
        }

        public boolean roomBusy(long roomId, long periodId, long excludingEntryId) {
            return lessons.stream().anyMatch(lesson -> lesson.roomId() != null
                    && lesson.roomId() == roomId
                    && lesson.periodId() == periodId
                    && lesson.entryId() != excludingEntryId);
        }
    }

    public ConstraintContext context() {
        return new ConstraintContext(periods, rooms, courses, teachers, classes, settings);
    }

    public int studentCount(long classId) {
        ClassView adminClass = classes.get(classId);
        return adminClass == null ? 0 : adminClass.studentCount();
    }

    /**
     * The timetable as it appears on one date. Mirrors the timetable read model
     * (ADR-0030): a new-format substitution applies only on its lesson date (or
     * the swap's target date), a legacy row keeps overriding from its effective
     * date onward, and the resulting weekday decides whether it is on this date.
     */
    public DaySchedule scheduleOn(LocalDate date) {
        int dayOfWeek = date.getDayOfWeek().getValue();
        Map<Long, AppliedSubstitution> latest = latestPerEntry(date);
        List<ScheduledLesson> lessons = new ArrayList<>();
        for (BaselineLesson lesson : baseline) {
            AppliedSubstitution substitution = latest.get(lesson.entryId());
            if (isMovedOutOn(substitution, date)) {
                // 调课来源日：这节课当天被移走，原槽不再出现（落点日由 target 分支呈现）。
                continue;
            }
            long periodId = substitution != null && substitution.newPeriodId() != null
                    ? substitution.newPeriodId() : lesson.periodId();
            int resolvedDay = substitution != null && substitution.newDayOfWeek() != null
                    ? substitution.newDayOfWeek() : lesson.dayOfWeek();
            int resolvedNo = substitution != null && substitution.newPeriodNo() != null
                    ? substitution.newPeriodNo() : lesson.periodNo();
            PeriodView period = periods.get(periodId);
            if (period != null) {
                resolvedDay = period.dayOfWeek();
                resolvedNo = period.periodNo();
            }
            if (resolvedDay != dayOfWeek) {
                continue;
            }
            long teacherId = substitution != null && substitution.substituteTeacherId() != null
                    ? substitution.substituteTeacherId() : lesson.teacherId();
            Long roomId = substitution != null && substitution.newRoomId() != null
                    ? substitution.newRoomId() : lesson.roomId();
            lessons.add(new ScheduledLesson(
                    lesson.entryId(),
                    lesson.classId(),
                    lesson.courseId(),
                    teacherId,
                    roomId,
                    periodId,
                    resolvedDay,
                    resolvedNo,
                    studentCount(lesson.classId())));
        }
        lessons.sort(Comparator.comparingLong(ScheduledLesson::periodId));
        return new DaySchedule(date, List.copyOf(lessons));
    }

    /** 新格式调课行在来源日命中时，该课当天被移出原槽。 */
    private static boolean isMovedOutOn(AppliedSubstitution substitution, LocalDate date) {
        return substitution != null
                && substitution.lessonDate() != null
                && substitution.targetLessonDate() != null
                && date.equals(substitution.lessonDate());
    }

    private Map<Long, AppliedSubstitution> latestPerEntry(LocalDate date) {
        List<AppliedSubstitution> reached = substitutions.stream()
                .filter(item -> touches(item, date))
                .sorted(Comparator
                        .comparing(AppliedSubstitution::createdAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingLong(AppliedSubstitution::id))
                .toList();
        Map<Long, AppliedSubstitution> latest = new LinkedHashMap<>();
        for (AppliedSubstitution item : reached) {
            latest.put(item.entryId(), item);
        }
        return latest;
    }

    /**
     * 该生效行是否作用于查询日：新格式按实例日期精确匹配（来源日或落点日），
     * 旧格式保持「自 effectiveDate 起在本学期内持续覆盖」的原语义。
     */
    private boolean touches(AppliedSubstitution item, LocalDate date) {
        if (item.lessonDate() == null) {
            return !item.effectiveDate().isAfter(date)
                    && !item.effectiveDate().isBefore(semesterStart);
        }
        return date.equals(item.lessonDate()) || date.equals(item.targetLessonDate());
    }

    public PeriodView period(long periodId) {
        return periods.get(periodId);
    }

    public CourseView course(long courseId) {
        return courses.get(courseId);
    }

    public RoomView room(Long roomId) {
        return roomId == null ? null : rooms.get(roomId);
    }

    public TeacherView teacher(long teacherId) {
        return teachers.get(teacherId);
    }

    /**
     * Deterministic hash of the whole semester timetable snapshot. Any published
     * change or newly effective substitution changes the hash, which is what makes
     * approving a stale plan a conflict instead of a partial write.
     */
    public String snapshotHash() {
        List<String> lines = new ArrayList<>();
        baseline.stream()
                .sorted(Comparator.comparingLong(BaselineLesson::entryId))
                .forEach(lesson -> lines.add("B|" + lesson.entryId() + "|" + lesson.classId() + "|"
                        + lesson.courseId() + "|" + lesson.teacherId() + "|" + lesson.roomId() + "|"
                        + lesson.periodId() + "|" + lesson.dayOfWeek() + "|" + lesson.periodNo()));
        substitutions.stream()
                .sorted(Comparator.comparingLong(AppliedSubstitution::id))
                .forEach(item -> lines.add("S|" + item.id() + "|" + item.entryId() + "|"
                        + item.effectiveDate() + "|" + item.newPeriodId() + "|" + item.newDayOfWeek()
                        + "|" + item.newPeriodNo() + "|" + item.newRoomId() + "|"
                        + item.substituteTeacherId() + "|" + item.type()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    }

    /** Copies the reference data and lessons with a different baseline list. */
    public PlanSnapshot withBaseline(List<BaselineLesson> newBaseline) {
        return new PlanSnapshot(semesterId, semesterStart, semesterEnd, periods, rooms, courses,
                teachers, classes, newBaseline, substitutions, settings);
    }

    /** Applies planned moves to the weekly baseline; used by the verifier and CLI. */
    public PlanSnapshot applyMoves(List<PlanMove> moves) {
        Map<Long, PlanMove> byEntry = new HashMap<>();
        for (PlanMove move : moves) {
            byEntry.put(move.entryId(), move);
        }
        List<BaselineLesson> updated = new ArrayList<>(baseline.size());
        for (BaselineLesson lesson : baseline) {
            PlanMove move = byEntry.get(lesson.entryId());
            if (move == null) {
                updated.add(lesson);
                continue;
            }
            PeriodView target = periods.get(move.toPeriodId());
            updated.add(new BaselineLesson(
                    lesson.entryId(),
                    lesson.classId(),
                    lesson.courseId(),
                    move.teacherId(),
                    move.roomId(),
                    move.toPeriodId(),
                    target == null ? move.toDayOfWeek() : target.dayOfWeek(),
                    target == null ? move.toPeriodNo() : target.periodNo()));
        }
        return withBaseline(updated);
    }
}
