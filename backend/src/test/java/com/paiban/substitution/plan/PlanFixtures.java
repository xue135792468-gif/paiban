package com.paiban.substitution.plan;

import com.paiban.foundation.domain.PeriodSection;
import com.paiban.scheduling.constraint.ConstraintContext.ClassView;
import com.paiban.scheduling.constraint.ConstraintContext.CourseView;
import com.paiban.scheduling.constraint.ConstraintContext.PeriodView;
import com.paiban.scheduling.constraint.ConstraintContext.RoomView;
import com.paiban.scheduling.constraint.ConstraintContext.TeacherView;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.substitution.plan.PlanSnapshot.AppliedSubstitution;
import com.paiban.substitution.plan.PlanSnapshot.BaselineLesson;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory timetable builder shared by the pure planner and verifier tests.
 * No Spring, no database: the whole point of the seam.
 */
public final class PlanFixtures {

    /** A Monday; tests count weekdays from here. */
    public static final LocalDate MONDAY = LocalDate.of(2025, 9, 1);

    public static final long CLASS_1 = 1L;
    public static final long CLASS_2 = 2L;

    public static long periodId(int dayOfWeek, int periodNo) {
        return dayOfWeek * 100L + periodNo;
    }

    public static LocalDate date(int dayOfWeek) {
        return MONDAY.plusDays(dayOfWeek - 1L);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<Long, PeriodView> periods = new HashMap<>();
        private final Map<Long, RoomView> rooms = new HashMap<>();
        private final Map<Long, CourseView> courses = new HashMap<>();
        private final Map<Long, TeacherView> teachers = new HashMap<>();
        private final Map<Long, ClassView> classes = new HashMap<>();
        private final List<BaselineLesson> baseline = new ArrayList<>();
        private final List<AppliedSubstitution> substitutions = new ArrayList<>();
        private ConstraintSettings settings = ConstraintSettings.defaults();
        private int days = 5;
        private int periodsPerDay = 8;
        private long nextEntryId = 1000L;

        public Builder days(int days) {
            this.days = days;
            return this;
        }

        public Builder periodsPerDay(int periodsPerDay) {
            this.periodsPerDay = periodsPerDay;
            return this;
        }

        public Builder settings(ConstraintSettings settings) {
            this.settings = settings;
            return this;
        }

        public Builder adminClass(long id, String name, int studentCount, Long headTeacherId) {
            classes.put(id, new ClassView(id, name, studentCount, headTeacherId));
            return this;
        }

        public Builder teacher(long id, String name, String subject) {
            teachers.put(id, new TeacherView(id, name, subject));
            return this;
        }

        public Builder course(long id, String name, String requiredRoomType) {
            courses.put(id, new CourseView(id, name, requiredRoomType));
            return this;
        }

        public Builder room(long id, String name, String type, int capacity) {
            rooms.put(id, new RoomView(id, name, type, capacity));
            return this;
        }

        /** Adds a lesson for fluent setup. */
        public Builder lesson(long classId, long courseId, long teacherId, Long roomId,
                              int dayOfWeek, int periodNo) {
            addLesson(classId, courseId, teacherId, roomId, dayOfWeek, periodNo);
            return this;
        }

        /** Adds a lesson and returns its auto-generated entry id. */
        public long addLesson(long classId, long courseId, long teacherId, Long roomId,
                              int dayOfWeek, int periodNo) {
            long entryId = nextEntryId++;
            baseline.add(new BaselineLesson(entryId, classId, courseId, teacherId, roomId,
                    periodId(dayOfWeek, periodNo), dayOfWeek, periodNo));
            return entryId;
        }

        public Builder substitution(long entryId, LocalDate effectiveDate, Long newPeriodId,
                                    int newDayOfWeek, int newPeriodNo, Long newRoomId,
                                    Long substituteTeacherId, String type) {
            substitutions.add(new AppliedSubstitution(
                    nextEntryId++, entryId, effectiveDate, Instant.EPOCH.plusSeconds(nextEntryId),
                    newPeriodId, newDayOfWeek, newPeriodNo, newRoomId, substituteTeacherId, type));
            return this;
        }

        public PlanSnapshot build() {
            for (int day = 1; day <= days; day++) {
                for (int no = 1; no <= periodsPerDay; no++) {
                    long id = periodId(day, no);
                    periods.put(id, new PeriodView(id, day, no, section(no)));
                }
            }
            return new PlanSnapshot(
                    1L, MONDAY, MONDAY.plusDays(119), periods, rooms, courses, teachers, classes,
                    baseline, substitutions, settings);
        }

        private PeriodSection section(int periodNo) {
            if (periodNo <= 4) {
                return PeriodSection.MORNING;
            }
            return periodNo <= 7 ? PeriodSection.AFTERNOON : PeriodSection.EVENING;
        }
    }

    private PlanFixtures() {}
}
