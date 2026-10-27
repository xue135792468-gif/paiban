package com.paiban.scheduling.constraint;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.substitution.plan.PlanFixtures;
import com.paiban.substitution.plan.PlanSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Ticket 14/01: the per-requirement controls (pause, must/prefer, weight) and the new
 * {@code COURSE_UNAVAILABLE} type, proven through the same checker every evaluation
 * entry point uses. The key promises are that one violation fact is counted once, hard
 * wins over soft when requirements overlap, and a threshold exception replaces both the
 * global number and the global strength.
 */
class ConstraintRequirementControlsCheckerTest {

    private static final long TEACHER_1 = 11L;
    private static final long TEACHER_2 = 12L;
    private static final long ROOM_1 = 21L;
    private static final long COURSE_1 = 31L;
    private static final long COURSE_2 = 32L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void overlappingHardAndSoftRequirementsOnOneFactCountOnceAsHard() {
        ConstraintSettings settings = settings(
                config(ConstraintType.TEACHER_UNAVAILABLE, true, 100),
                entry(ConstraintType.TEACHER_UNAVAILABLE, "a", true, null, Map.of(
                        "teacherId", TEACHER_1, "dayOfWeek", 1, "periodNo", 1)),
                entry(ConstraintType.TEACHER_UNAVAILABLE, "b", false, 80, Map.of(
                        "teacherId", TEACHER_1, "dayOfWeek", 1, "periodNo", 1)));

        PlanSnapshot snapshot = oneLesson(settings, 1, 1, COURSE_1, TEACHER_1, 1);
        List<LessonView> views = viewsOn(snapshot);

        assertThat(ConstraintChecker.checkHard(snapshot.context(), views))
                .filteredOn(violation -> "TEACHER_UNAVAILABLE".equals(violation.code()))
                .hasSize(1);
        // The same fact is never charged twice: the hard requirement already covers it.
        assertThat(ConstraintChecker.softPenalty(snapshot.context(), views)).isZero();
    }

    @Test
    void twoSoftOverlapsUseTheHigherWeightExactlyOnce() {
        ConstraintSettings settings = settings(
                config(ConstraintType.TEACHER_UNAVAILABLE, true, 100),
                entry(ConstraintType.TEACHER_UNAVAILABLE, "a", false, 30, Map.of(
                        "teacherId", TEACHER_1, "dayOfWeek", 1, "periodNo", 1)),
                entry(ConstraintType.TEACHER_UNAVAILABLE, "b", false, 70, Map.of(
                        "teacherId", TEACHER_1, "dayOfWeek", 1, "periodNo", 1)));

        PlanSnapshot snapshot = oneLesson(settings, 1, 1, COURSE_1, TEACHER_1, 1);
        List<LessonView> views = viewsOn(snapshot);

        assertThat(ConstraintChecker.checkHard(snapshot.context(), views)).isEmpty();
        assertThat(ConstraintChecker.softPenalty(snapshot.context(), views)).isEqualTo(70);
    }

    @Test
    void anEntryOverrideWinsInBothDirections() {
        // Type-level hard, entry soft: not a blocker, but it costs its weight.
        ConstraintSettings softened = settings(
                config(ConstraintType.TEACHER_UNAVAILABLE, true, 100),
                entry(ConstraintType.TEACHER_UNAVAILABLE, "a", false, 40, Map.of(
                        "teacherId", TEACHER_1, "dayOfWeek", 1, "periodNo", 1)));
        PlanSnapshot soft = oneLesson(softened, 1, 1, COURSE_1, TEACHER_1, 1);
        assertThat(ConstraintChecker.checkHard(soft.context(), viewsOn(soft))).isEmpty();
        assertThat(ConstraintChecker.softPenalty(soft.context(), viewsOn(soft))).isEqualTo(40);

        // Type-level soft, entry hard: the requirement blocks.
        ConstraintSettings hardened = settings(
                config(ConstraintType.TEACHER_UNAVAILABLE, false, 40),
                entry(ConstraintType.TEACHER_UNAVAILABLE, "a", true, null, Map.of(
                        "teacherId", TEACHER_1, "dayOfWeek", 1, "periodNo", 1)));
        PlanSnapshot hard = oneLesson(hardened, 1, 1, COURSE_1, TEACHER_1, 1);
        assertThat(ConstraintChecker.checkHard(hard.context(), viewsOn(hard)))
                .filteredOn(violation -> "TEACHER_UNAVAILABLE".equals(violation.code()))
                .hasSize(1);
        assertThat(ConstraintChecker.softPenalty(hard.context(), viewsOn(hard))).isZero();
    }

    @Test
    void aPausedRequirementKeepsItsContentButIsNotEvaluated() {
        ConstraintSettings settings = settings(
                config(ConstraintType.TEACHER_UNAVAILABLE, true, 100),
                entry(ConstraintType.TEACHER_UNAVAILABLE, "a", false, null, false, Map.of(
                        "teacherId", TEACHER_1, "dayOfWeek", 1, "periodNo", 1)));

        assertThat(settings.entries(ConstraintType.TEACHER_UNAVAILABLE)).isEmpty();
        PlanSnapshot snapshot = oneLesson(settings, 1, 1, COURSE_1, TEACHER_1, 1);
        assertThat(ConstraintChecker.checkHard(snapshot.context(), viewsOn(snapshot))).isEmpty();
        assertThat(ConstraintChecker.softPenalty(snapshot.context(), viewsOn(snapshot))).isZero();
    }

    @Test
    void courseUnavailableBlocksThatCourseWithoutTouchingTheTeachersOtherCourse() {
        ConstraintSettings settings = settings(
                config(ConstraintType.COURSE_UNAVAILABLE, true, 100),
                entry(ConstraintType.COURSE_UNAVAILABLE, "a", true, null, Map.of(
                        "courseId", COURSE_1, "dayOfWeek", 1, "periodNo", 1)));

        PlanSnapshot snapshot = PlanFixtures.builder()
                .settings(settings)
                .adminClass(PlanFixtures.CLASS_1, "高一1班", 45, null)
                .adminClass(PlanFixtures.CLASS_2, "高一2班", 45, null)
                .teacher(TEACHER_1, "张老师", "语文")
                .course(COURSE_1, "体育", null)
                .course(COURSE_2, "数学", null)
                .room(ROOM_1, "教室1", "NORMAL", 50)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_2, COURSE_2, TEACHER_1, ROOM_1, 1, 1)
                .build();

        assertThat(ConstraintChecker.checkHard(snapshot.context(), viewsOn(snapshot)))
                .filteredOn(violation -> "COURSE_UNAVAILABLE".equals(violation.code()))
                .extracting(ConstraintViolation::lessonId)
                .containsExactly(1000L);
    }

    @Test
    void thresholdExceptionReplacesBothTheNumberAndTheStrength() {
        ConstraintSettings settings = settings(
                config(ConstraintType.TEACHER_MAX_PER_DAY, true, 100),
                entry(ConstraintType.TEACHER_MAX_PER_DAY, null, true, null, Map.of("maxPerDay", 2)),
                entry(ConstraintType.TEACHER_MAX_PER_DAY, null, false, 40, Map.of(
                        "teacherId", TEACHER_1, "maxPerDay", 5)));

        // Teacher 1: 6 lessons in one day — one over its own soft limit of 5.
        // Teacher 2: 3 lessons — one over the hard global limit of 2.
        PlanSnapshot snapshot = PlanFixtures.builder()
                .settings(settings)
                .adminClass(PlanFixtures.CLASS_1, "高一1班", 45, null)
                .adminClass(PlanFixtures.CLASS_2, "高一2班", 45, null)
                .teacher(TEACHER_1, "张老师", "语文")
                .teacher(TEACHER_2, "李老师", "数学")
                .course(COURSE_1, "语文", null)
                .course(COURSE_2, "数学", null)
                .room(ROOM_1, "教室1", "NORMAL", 50)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 2)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 3)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 4)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 5)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 6)
                .lesson(PlanFixtures.CLASS_1, COURSE_2, TEACHER_2, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_2, TEACHER_2, ROOM_1, 1, 2)
                .lesson(PlanFixtures.CLASS_1, COURSE_2, TEACHER_2, ROOM_1, 1, 3)
                .build();

        List<ConstraintViolation> hard = ConstraintChecker.checkHard(
                snapshot.context(), viewsOn(snapshot));
        assertThat(hard)
                .filteredOn(violation -> "TEACHER_MAX_PER_DAY".equals(violation.code()))
                .hasSize(1);
        // The hard global is never stacked back on top of the replacing soft exception.
        assertThat(ConstraintChecker.softPenalty(snapshot.context(), viewsOn(snapshot)))
                .isEqualTo(40);
    }

    @Test
    void aPreferredSlotSetIsSatisfiedByAnySlotRegardlessOfStrength() {
        ConstraintSettings settings = settings(
                config(ConstraintType.TEACHER_PREFERRED_SLOT, false, 40),
                entry(ConstraintType.TEACHER_PREFERRED_SLOT, "a", true, null, Map.of(
                        "teacherId", TEACHER_1, "dayOfWeek", 1, "periodNo", 1)),
                entry(ConstraintType.TEACHER_PREFERRED_SLOT, "b", false, 60, Map.of(
                        "teacherId", TEACHER_1, "dayOfWeek", 1, "periodNo", 2)));

        PlanSnapshot satisfied = oneLesson(settings, 1, 2, COURSE_1, TEACHER_1, 1);
        assertThat(ConstraintChecker.checkHard(satisfied.context(), viewsOn(satisfied))).isEmpty();
        assertThat(ConstraintChecker.softPenalty(satisfied.context(), viewsOn(satisfied))).isZero();

        PlanSnapshot missed = oneLesson(settings, 1, 3, COURSE_1, TEACHER_1, 1);
        assertThat(ConstraintChecker.checkHard(missed.context(), viewsOn(missed)))
                .filteredOn(violation -> "TEACHER_PREFERRED_SLOT".equals(violation.code()))
                .hasSize(1);
        assertThat(ConstraintChecker.softPenalty(missed.context(), viewsOn(missed))).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private static PlanSnapshot oneLesson(
            ConstraintSettings settings, int day, int periodNo,
            long courseId, long teacherId, int lessonIndex) {
        return PlanFixtures.builder()
                .settings(settings)
                .adminClass(PlanFixtures.CLASS_1, "高一1班", 45, null)
                .teacher(teacherId, "张老师", "语文")
                .course(courseId, "语文", null)
                .room(ROOM_1, "教室1", "NORMAL", 50)
                .lesson(PlanFixtures.CLASS_1, courseId, teacherId, ROOM_1, day, periodNo)
                .build();
    }

    private static List<LessonView> viewsOn(PlanSnapshot snapshot) {
        List<LessonView> views = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            snapshot.scheduleOn(PlanFixtures.date(day)).lessons().stream()
                    .map(lesson -> new LessonView(lesson.entryId(), lesson.classId(),
                            lesson.courseId(), lesson.teacherId(), lesson.roomId(),
                            lesson.periodId(), lesson.studentCount()))
                    .forEach(views::add);
        }
        return views;
    }

    private static ConstraintSettings settings(ConstraintConfig config, ConstraintEntry... entries) {
        return ConstraintSettings.from(List.of(config), List.of(entries), MAPPER);
    }

    private static ConstraintConfig config(ConstraintType type, boolean hard, int weight) {
        ConstraintConfig config = new ConstraintConfig();
        config.setType(type.name());
        config.setHard(hard);
        config.setWeight(weight);
        config.setEnabled(true);
        config.setParamsJson("{}");
        return config;
    }

    private static ConstraintEntry entry(
            ConstraintType type, String group, boolean hard, Integer weight,
            Map<String, Object> params) {
        return entry(type, group, hard, weight, true, params);
    }

    private static ConstraintEntry entry(
            ConstraintType type, String group, boolean hard, Integer weight,
            boolean enabled, Map<String, Object> params) {
        ConstraintEntry entry = new ConstraintEntry();
        entry.setType(type.name());
        entry.setParamsJson(write(params));
        entry.setCreatedAt(Instant.EPOCH);
        entry.setEnabled(enabled);
        entry.setHard(hard);
        entry.setWeight(weight);
        entry.setGroupKey(group);
        return entry;
    }

    private static String write(Map<String, Object> params) {
        try {
            return MAPPER.writeValueAsString(params);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
