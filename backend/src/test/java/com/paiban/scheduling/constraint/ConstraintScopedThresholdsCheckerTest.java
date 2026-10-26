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
 * Ticket 10/04: the five threshold types read one entry per scope — a global
 * default (no object) plus per-object exceptions — and every evaluation entry
 * point resolves the value through the same policy:
 *
 * <pre>
 * exception of this object, else the global default, else no limit at all
 * </pre>
 *
 * <p>An exception-only configuration therefore never invents a default for the
 * objects it did not list (ticket-10 spec, threshold paragraph), and one entry
 * never scores twice: the counts are per counting bucket, not per entry.
 */
class ConstraintScopedThresholdsCheckerTest {

    private static final long TEACHER_A = 11L;
    private static final long TEACHER_B = 12L;
    private static final long CLASS_1 = PlanFixtures.CLASS_1;
    private static final long CLASS_2 = PlanFixtures.CLASS_2;
    private static final long COURSE_CHINESE = 31L;
    private static final long COURSE_MATH = 32L;
    private static final long ROOM = 21L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void registryMarksTheFiveThresholdsAsEntryBacked() {
        assertThat(List.of(ConstraintType.values()).stream()
                .filter(ConstraintType::isEntryBacked)
                .toList())
                .hasSize(18)
                .contains(ConstraintType.TEACHER_MAX_PER_DAY,
                        ConstraintType.CLASS_MAX_PER_DAY,
                        ConstraintType.COURSE_MAX_PER_DAY,
                        ConstraintType.EVENING_MAX,
                        ConstraintType.TEACHER_MAX_CONSECUTIVE)
                .doesNotContain(ConstraintType.TEACHER_CONFLICT,
                        ConstraintType.CLASS_GAP_MINIMIZED);

        // Each threshold declares which entry field names its object and which one
        // carries the value, so validation and evaluation cannot drift apart.
        assertThat(ConstraintType.TEACHER_MAX_PER_DAY.thresholdScope().objectField())
                .isEqualTo("teacherId");
        assertThat(ConstraintType.TEACHER_MAX_CONSECUTIVE.thresholdScope().objectField())
                .isEqualTo("teacherId");
        assertThat(ConstraintType.CLASS_MAX_PER_DAY.thresholdScope().objectField())
                .isEqualTo("classId");
        assertThat(ConstraintType.EVENING_MAX.thresholdScope().objectField())
                .isEqualTo("classId");
        assertThat(ConstraintType.COURSE_MAX_PER_DAY.thresholdScope().objectField())
                .isEqualTo("courseId");
        assertThat(ConstraintType.COURSE_MAX_PER_DAY.thresholdScope().valueField())
                .isEqualTo("maxPerDay");
        assertThat(ConstraintType.TEACHER_MAX_CONSECUTIVE.thresholdScope().valueField())
                .isEqualTo("maxConsecutive");
        assertThat(ConstraintType.TEACHER_CONFLICT.thresholdScope()).isNull();
    }

    @Test
    void anExceptionReplacesTheGlobalDefaultOnlyForItsOwnObject() {
        ConstraintSettings settings = threshold(ConstraintType.TEACHER_MAX_PER_DAY, true, 60,
                Map.of("maxPerDay", 6),
                Map.of("teacherId", TEACHER_A, "maxPerDay", 3));

        PlanSnapshot snapshot = base()
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 1)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 2)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 3)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 4)
                // TEACHER_B is not listed: four lessons are still inside the global 6.
                .lesson(CLASS_2, COURSE_MATH, TEACHER_B, ROOM, 2, 1)
                .lesson(CLASS_2, COURSE_MATH, TEACHER_B, ROOM, 2, 2)
                .lesson(CLASS_2, COURSE_MATH, TEACHER_B, ROOM, 2, 3)
                .lesson(CLASS_2, COURSE_MATH, TEACHER_B, ROOM, 2, 4)
                .build();

        List<String> rows = codes(violations(settings, snapshot), "TEACHER_MAX_PER_DAY");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).contains("张老师").contains("4").contains("3");
    }

    @Test
    void removingTheExceptionFallsBackToTheGlobalDefault() {
        ConstraintSettings withException = threshold(ConstraintType.TEACHER_MAX_PER_DAY, true, 60,
                Map.of("maxPerDay", 6),
                Map.of("teacherId", TEACHER_A, "maxPerDay", 3));
        ConstraintSettings globalOnly = threshold(ConstraintType.TEACHER_MAX_PER_DAY, true, 60,
                Map.of("maxPerDay", 6));
        PlanSnapshot fourLessons = base()
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 1)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 2)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 3)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 4)
                .build();

        assertThat(codes(violations(withException, fourLessons), "TEACHER_MAX_PER_DAY")).hasSize(1);
        assertThat(codes(violations(globalOnly, fourLessons), "TEACHER_MAX_PER_DAY")).isEmpty();
    }

    @Test
    void exceptionsWithoutAGlobalLeaveEveryOtherObjectUnlimited() {
        ConstraintSettings settings = threshold(ConstraintType.TEACHER_MAX_PER_DAY, true, 60,
                Map.of("teacherId", TEACHER_A, "maxPerDay", 3));
        PlanSnapshot snapshot = base()
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 1)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 2)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 3)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 4)
                .lesson(CLASS_2, COURSE_MATH, TEACHER_B, ROOM, 2, 1)
                .lesson(CLASS_2, COURSE_MATH, TEACHER_B, ROOM, 2, 2)
                .lesson(CLASS_2, COURSE_MATH, TEACHER_B, ROOM, 2, 3)
                .lesson(CLASS_2, COURSE_MATH, TEACHER_B, ROOM, 2, 4)
                .build();

        assertThat(codes(violations(settings, snapshot), "TEACHER_MAX_PER_DAY")).hasSize(1);
        assertThat(codes(violations(settings, snapshot), "TEACHER_MAX_PER_DAY").get(0))
                .contains("张老师");
    }

    @Test
    void classMaxPerDayUsesTheAdministrativeClassBucket() {
        ConstraintSettings settings = threshold(ConstraintType.CLASS_MAX_PER_DAY, true, 50,
                Map.of("maxPerDay", 6),
                Map.of("classId", CLASS_1, "maxPerDay", 2));

        PlanSnapshot snapshot = base()
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 3, 1)
                .lesson(CLASS_1, COURSE_MATH, TEACHER_A, ROOM, 3, 2)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_B, ROOM, 3, 3)
                .lesson(CLASS_2, COURSE_CHINESE, TEACHER_A, ROOM, 3, 1)
                .lesson(CLASS_2, COURSE_MATH, TEACHER_B, ROOM, 3, 2)
                .lesson(CLASS_2, COURSE_CHINESE, TEACHER_B, ROOM, 3, 3)
                .lesson(CLASS_2, COURSE_MATH, TEACHER_A, ROOM, 3, 4)
                .lesson(CLASS_2, COURSE_CHINESE, TEACHER_A, ROOM, 3, 5)
                .build();

        List<String> rows = codes(violations(settings, snapshot), "CLASS_MAX_PER_DAY");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).contains("高一1班").contains("3").contains("2");
    }

    @Test
    void courseMaxPerDayCountsPerClassAndCourseButResolvesByCourse() {
        // The object of the entry is the course; the bucket stays "class + course + day".
        ConstraintSettings settings = threshold(ConstraintType.COURSE_MAX_PER_DAY, true, 50,
                Map.of("maxPerDay", 2),
                Map.of("courseId", COURSE_MATH, "maxPerDay", 1));

        PlanSnapshot snapshot = base()
                // Maths twice in class 1: over the exception's 1.
                .lesson(CLASS_1, COURSE_MATH, TEACHER_A, ROOM, 4, 1)
                .lesson(CLASS_1, COURSE_MATH, TEACHER_B, ROOM, 4, 2)
                // Maths twice in class 2: the same course exception applies per class.
                .lesson(CLASS_2, COURSE_MATH, TEACHER_A, ROOM, 4, 1)
                .lesson(CLASS_2, COURSE_MATH, TEACHER_B, ROOM, 4, 2)
                // Chinese twice in class 1: inside the global 2.
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_B, ROOM, 4, 3)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 4, 4)
                .build();

        List<String> rows = codes(violations(settings, snapshot), "COURSE_MAX_PER_DAY");
        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(row -> row.contains("数学"));
    }

    @Test
    void eveningMaxCountsPerClassAndResolvesByAdministrativeClass() {
        ConstraintSettings settings = threshold(ConstraintType.EVENING_MAX, true, 40,
                Map.of("maxPerDay", 2),
                Map.of("classId", CLASS_2, "maxPerDay", 1));

        // Period 9 and 10 are the evening slots of the fixture (8 periods per day).
        PlanSnapshot snapshot = base().periodsPerDay(10)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 1, 9)
                .lesson(CLASS_1, COURSE_MATH, TEACHER_B, ROOM, 1, 10)
                .lesson(CLASS_2, COURSE_CHINESE, TEACHER_A, ROOM, 1, 9)
                .lesson(CLASS_2, COURSE_MATH, TEACHER_B, ROOM, 1, 10)
                .build();

        List<String> rows = codes(violations(settings, snapshot), "EVENING_MAX");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).contains("高一2班").contains("2").contains("1");
    }

    @Test
    void teacherMaxConsecutiveResolvesPerTeacher() {
        ConstraintSettings settings = threshold(ConstraintType.TEACHER_MAX_CONSECUTIVE, true, 45,
                Map.of("maxConsecutive", 3),
                Map.of("teacherId", TEACHER_A, "maxConsecutive", 2));

        PlanSnapshot snapshot = base()
                // Three in a row for A, over its exception's 2.
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 1)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 2)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 3)
                // Three in a row for B, inside the global 3.
                .lesson(CLASS_2, COURSE_MATH, TEACHER_B, ROOM, 2, 1)
                .lesson(CLASS_2, COURSE_MATH, TEACHER_B, ROOM, 2, 2)
                .lesson(CLASS_2, COURSE_MATH, TEACHER_B, ROOM, 2, 3)
                .build();

        List<String> rows = codes(violations(settings, snapshot), "TEACHER_MAX_CONSECUTIVE");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).contains("3").contains("2");
    }

    @Test
    void disablingTheTypeSilencesEveryEntry() {
        ConstraintConfig disabled = config(ConstraintType.TEACHER_MAX_PER_DAY, true, 60, Map.of());
        disabled.setEnabled(false);
        ConstraintSettings settings = ConstraintSettings.from(
                List.of(disabled),
                List.of(entry(ConstraintType.TEACHER_MAX_PER_DAY, Map.of("maxPerDay", 6)),
                        entry(ConstraintType.TEACHER_MAX_PER_DAY,
                                Map.of("teacherId", TEACHER_A, "maxPerDay", 1))),
                MAPPER);
        PlanSnapshot fourLessons = base()
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 1)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 2)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 3)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 4)
                .build();

        assertThat(violations(settings, fourLessons)).isEmpty();
        assertThat(ConstraintChecker.softPenalty(context(settings, fourLessons),
                views(fourLessons))).isZero();
    }

    @Test
    void softPenaltyCountsTheExcessOnceNoMatterHowManyEntriesExist() {
        ConstraintSettings settings = threshold(ConstraintType.TEACHER_MAX_PER_DAY, false, 60,
                Map.of("maxPerDay", 6),
                Map.of("teacherId", TEACHER_B, "maxPerDay", 5),
                Map.of("teacherId", TEACHER_A, "maxPerDay", 3));
        PlanSnapshot fourForA = base()
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 1)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 2)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 3)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 4)
                .build();

        // Three entries, one exceeded bucket, excess 1: exactly one weight.
        assertThat(ConstraintChecker.softPenalty(context(settings, fourForA), views(fourForA)))
                .isEqualTo(60);
    }

    @Test
    void legacyParamsOfAThresholdAreNotConfiguredAnyMore() {
        // V28 moved every old single value into an entry row; a row that was never
        // migrated must not resurrect a rule the editor cannot show.
        ConstraintSettings legacyOnly = ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_MAX_PER_DAY, true, 60,
                        Map.of("maxPerDay", 1))), MAPPER);
        PlanSnapshot twoLessons = base()
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 1)
                .lesson(CLASS_1, COURSE_CHINESE, TEACHER_A, ROOM, 2, 2)
                .build();

        assertThat(violations(legacyOnly, twoLessons)).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private static PlanFixtures.Builder base() {
        return PlanFixtures.builder()
                .adminClass(CLASS_1, "高一1班", 45, null)
                .adminClass(CLASS_2, "高一2班", 45, null)
                .teacher(TEACHER_A, "张老师", "语文")
                .teacher(TEACHER_B, "李老师", "数学")
                .course(COURSE_CHINESE, "语文", null)
                .course(COURSE_MATH, "数学", null)
                .room(ROOM, "教室1", "NORMAL", 50);
    }

    /** One enabled threshold type plus explicit entries (global first, then exceptions). */
    @SafeVarargs
    private static ConstraintSettings threshold(
            ConstraintType type, boolean hard, int weight, Map<String, Object>... entries) {
        return ConstraintSettings.from(
                List.of(config(type, hard, weight, Map.of())),
                java.util.Arrays.stream(entries)
                        .map(params -> entry(type, params))
                        .toList(),
                MAPPER);
    }

    /** The same reference data under a second rule set: settings live in the context. */
    private static ConstraintContext context(ConstraintSettings settings, PlanSnapshot snapshot) {
        return new ConstraintContext(snapshot.periods(), snapshot.rooms(), snapshot.courses(),
                snapshot.teachers(), snapshot.classes(), settings);
    }

    private static List<ConstraintViolation> violations(
            ConstraintSettings settings, PlanSnapshot snapshot) {
        return ConstraintChecker.checkHard(context(settings, snapshot), views(snapshot));
    }

    private static List<LessonView> views(PlanSnapshot snapshot) {
        List<LessonView> views = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            PlanSnapshot.DaySchedule schedule = snapshot.scheduleOn(PlanFixtures.date(day));
            if (schedule == null) {
                continue;
            }
            for (PlanSnapshot.ScheduledLesson lesson : schedule.lessons()) {
                views.add(new LessonView(lesson.entryId(), lesson.classId(), lesson.courseId(),
                        lesson.teacherId(), lesson.roomId(), lesson.periodId(), lesson.studentCount()));
            }
        }
        return views;
    }

    private static List<String> codes(List<ConstraintViolation> violations, String code) {
        List<String> rows = new ArrayList<>();
        for (ConstraintViolation violation : violations) {
            if (code.equals(violation.code())) {
                rows.add(violation.message());
            }
        }
        return rows;
    }

    private static ConstraintConfig config(ConstraintType type, boolean hard, int weight,
                                           Map<String, Object> params) {
        ConstraintConfig config = new ConstraintConfig();
        config.setType(type.name());
        config.setHard(hard);
        config.setWeight(weight);
        config.setEnabled(true);
        try {
            config.setParamsJson(MAPPER.writeValueAsString(params));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return config;
    }

    private static ConstraintEntry entry(ConstraintType type, Map<String, Object> params) {
        ConstraintEntry entry = new ConstraintEntry();
        entry.setType(type.name());
        try {
            entry.setParamsJson(MAPPER.writeValueAsString(params));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        entry.setCreatedAt(Instant.EPOCH);
        return entry;
    }
}
