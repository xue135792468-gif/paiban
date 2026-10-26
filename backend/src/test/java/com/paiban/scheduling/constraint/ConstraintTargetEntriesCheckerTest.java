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
 * Ticket 10/03: the twelve remaining target rules read every entry and combine
 * them with the semantics the spec fixes per type — union for blocking rules,
 * an OR set for preferred slots, per-entry judgement for the scalar rules — and
 * an overlap between an "all targets" entry and a concrete one is one violation,
 * never two. Also pins the empty-entry rule that separates this ticket from 02.
 */
class ConstraintTargetEntriesCheckerTest {

    private static final long TEACHER_1 = 11L;
    private static final long TEACHER_2 = 12L;
    private static final long TEACHER_3 = 13L;
    private static final long ROOM_NORMAL = 21L;
    private static final long ROOM_LAB = 22L;
    private static final long ROOM_PLAYGROUND = 23L;
    private static final long COURSE_CHINESE = 31L;
    private static final long COURSE_MATH = 32L;
    private static final long COURSE_PE = 33L;
    private static final long COURSE_ART = 34L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void registryMarksExactlyTheThirteenTargetTypes() {
        List<ConstraintType> entryBacked = java.util.Arrays.stream(ConstraintType.values())
                .filter(ConstraintType::isEntryBacked)
                .toList();

        assertThat(entryBacked).hasSize(13).contains(
                ConstraintType.TEACHER_UNAVAILABLE,
                ConstraintType.TEACHER_MEETING_BLOCKED,
                ConstraintType.COURSE_REQUIRED_ROOM_TYPE,
                ConstraintType.COURSE_NOT_IN_EVENING,
                ConstraintType.SAME_COURSE_ONCE_PER_DAY,
                ConstraintType.SPORT_NOT_FIRST_AFTERNOON,
                ConstraintType.CONSECUTIVE_LESSONS,
                ConstraintType.COURSE_SPREAD,
                ConstraintType.MAIN_SUBJECT_MORNING,
                ConstraintType.TEACHER_PREFERRED_SLOT,
                ConstraintType.TEACHER_NO_FIRST_PERIOD,
                ConstraintType.TEACHER_DAY_OFF,
                ConstraintType.PE_LAST_PERIOD_AVOID);
        assertThat(entryBacked).doesNotContain(
                ConstraintType.TEACHER_MAX_PER_DAY,
                ConstraintType.CLASS_MAX_PER_DAY,
                ConstraintType.COURSE_MAX_PER_DAY,
                ConstraintType.EVENING_MAX,
                ConstraintType.TEACHER_MAX_CONSECUTIVE,
                ConstraintType.TEACHER_CONFLICT,
                ConstraintType.HEAD_TEACHER_FIRST_PERIOD);
    }

    @Test
    void twoCoursesEachStayOutOfTheEvening() {
        ConstraintSettings settings = settings(ConstraintType.COURSE_NOT_IN_EVENING, true, 100,
                Map.of("courseId", COURSE_CHINESE), Map.of("courseId", COURSE_MATH));
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 8)
                .lesson(PlanFixtures.CLASS_2, COURSE_MATH, TEACHER_2, ROOM_NORMAL, 1, 8)
                .lesson(PlanFixtures.CLASS_1, COURSE_ART, TEACHER_3, ROOM_LAB, 1, 7)
                .build();

        assertThat(codes(violations(settings, snapshot, 1), "COURSE_NOT_IN_EVENING")).hasSize(2);
    }

    @Test
    void teacherNoFirstPeriodCoversBothTeachers() {
        ConstraintSettings settings = settings(ConstraintType.TEACHER_NO_FIRST_PERIOD, true, 35,
                Map.of("teacherId", TEACHER_1), Map.of("teacherId", TEACHER_2));
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 1)
                .lesson(PlanFixtures.CLASS_2, COURSE_MATH, TEACHER_2, ROOM_NORMAL, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_ART, TEACHER_3, ROOM_LAB, 1, 2)
                .build();

        assertThat(codes(violations(settings, snapshot, 1), "TEACHER_NO_FIRST_PERIOD")).hasSize(2);
    }

    @Test
    void teacherDayOffIsTheUnionOfEveryTeacherDayPair() {
        ConstraintSettings settings = settings(ConstraintType.TEACHER_DAY_OFF, true, 30,
                Map.of("teacherId", TEACHER_1, "dayOfWeek", 2),
                Map.of("teacherId", TEACHER_2, "dayOfWeek", 3));
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 2, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_MATH, TEACHER_2, ROOM_NORMAL, 3, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_ART, TEACHER_1, ROOM_LAB, 3, 2)
                .lesson(PlanFixtures.CLASS_2, COURSE_ART, TEACHER_2, ROOM_LAB, 2, 2)
                .build();

        assertThat(codes(violations(settings, snapshot, 2), "TEACHER_DAY_OFF")).hasSize(1);
        assertThat(codes(violations(settings, snapshot, 2), "TEACHER_DAY_OFF"))
                .allMatch(row -> row.startsWith("TEACHER_DAY_OFF@1000"));
        // Tuesday is only a day off for teacher 1 and Wednesday only for teacher 2, so
        // each day reports its own teacher and never the other one.
        assertThat(codes(violations(settings, snapshot, 3), "TEACHER_DAY_OFF"))
                .containsExactly("TEACHER_DAY_OFF@1001");
    }

    @Test
    void preferredSlotsOfOneTeacherAreAnOrSet() {
        ConstraintSettings settings = settings(ConstraintType.TEACHER_PREFERRED_SLOT, true, 40,
                Map.of("teacherId", TEACHER_1, "dayOfWeek", 1, "periodNo", 1),
                Map.of("teacherId", TEACHER_1, "dayOfWeek", 1, "periodNo", 2));
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 2)
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 3)
                .lesson(PlanFixtures.CLASS_2, COURSE_MATH, TEACHER_2, ROOM_NORMAL, 1, 3)
                .build();

        // Two entries are a choice, never an "and": only the slot outside both fails,
        // and a teacher without entries is not judged at all.
        List<ConstraintViolation> violations =
                violations(settings, snapshot, 1).stream()
                        .filter(violation -> "TEACHER_PREFERRED_SLOT".equals(violation.code()))
                        .toList();
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).lessonId()).isEqualTo(1002L);
    }

    @Test
    void sportEntriesUnionAndScoreOncePerLesson() {
        ConstraintSettings hard = settings(ConstraintType.SPORT_NOT_FIRST_AFTERNOON, true, 70,
                Map.of(), Map.of("courseId", COURSE_MATH));
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_PE, TEACHER_1, ROOM_PLAYGROUND, 1, 5)
                .lesson(PlanFixtures.CLASS_2, COURSE_MATH, TEACHER_2, ROOM_NORMAL, 1, 5)
                .lesson(PlanFixtures.CLASS_1, COURSE_ART, TEACHER_3, ROOM_LAB, 1, 5)
                .build();

        // The PE lesson matches the "sport class" entry only once even though the
        // blank entry would also cover it; the art lesson is untouched.
        List<String> rows = codes(violations(hard, snapshot, 1), "SPORT_NOT_FIRST_AFTERNOON");
        assertThat(rows).hasSize(2);
        assertThat(rows).anyMatch(row -> row.startsWith("SPORT_NOT_FIRST_AFTERNOON@1000"));
        assertThat(rows).anyMatch(row -> row.startsWith("SPORT_NOT_FIRST_AFTERNOON@1001"));

        ConstraintSettings soft = settings(ConstraintType.SPORT_NOT_FIRST_AFTERNOON, false, 70,
                Map.of(), Map.of("courseId", COURSE_MATH));
        assertThat(ConstraintChecker.softPenalty(snapshot.context(), viewsOn(snapshot, 1)))
                .isEqualTo(0);
        assertThat(ConstraintChecker.softPenalty(
                withSettings(snapshot, soft).context(), viewsOn(snapshot, 1)))
                .isEqualTo(2 * 70);
    }

    @Test
    void peLastPeriodCountsOncePerLesson() {
        ConstraintSettings hard = settings(ConstraintType.PE_LAST_PERIOD_AVOID, true, 35,
                Map.of(), Map.of("courseId", COURSE_MATH));
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_PE, TEACHER_1, ROOM_PLAYGROUND, 1, 8)
                .lesson(PlanFixtures.CLASS_2, COURSE_MATH, TEACHER_2, ROOM_NORMAL, 1, 8)
                .lesson(PlanFixtures.CLASS_1, COURSE_ART, TEACHER_3, ROOM_LAB, 1, 7)
                .build();

        assertThat(codes(violations(hard, snapshot, 1), "PE_LAST_PERIOD_AVOID")).hasSize(2);

        ConstraintSettings soft = settings(ConstraintType.PE_LAST_PERIOD_AVOID, false, 35,
                Map.of(), Map.of("courseId", COURSE_MATH));
        assertThat(ConstraintChecker.softPenalty(
                withSettings(snapshot, soft).context(), viewsOn(snapshot, 1)))
                .isEqualTo(2 * 35);
    }

    @Test
    void courseSpreadCountsEachClassCourseDayOnce() {
        ConstraintSettings hard = settings(ConstraintType.COURSE_SPREAD, true, 50,
                Map.of(), Map.of("courseId", COURSE_CHINESE));
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 2)
                .lesson(PlanFixtures.CLASS_2, COURSE_MATH, TEACHER_2, ROOM_NORMAL, 1, 1)
                .build();

        // The same (class, course, day) fact matches both the "all courses" and the
        // concrete entry: one violation, one penalty.
        assertThat(codes(violations(hard, snapshot, 1), "COURSE_SPREAD")).hasSize(1);

        ConstraintSettings soft = settings(ConstraintType.COURSE_SPREAD, false, 50,
                Map.of(), Map.of("courseId", COURSE_CHINESE));
        assertThat(ConstraintChecker.softPenalty(
                withSettings(snapshot, soft).context(), viewsOn(snapshot, 1)))
                .isEqualTo(50);
    }

    @Test
    void sameCourseOncePerDayCountsEachPairOnce() {
        ConstraintSettings settings = settings(ConstraintType.SAME_COURSE_ONCE_PER_DAY, true, 100,
                Map.of(), Map.of("courseId", COURSE_CHINESE));
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 2)
                .lesson(PlanFixtures.CLASS_1, COURSE_MATH, TEACHER_2, ROOM_NORMAL, 1, 3)
                .build();

        assertThat(codes(violations(settings, snapshot, 1), "SAME_COURSE_PER_DAY")).hasSize(1);
    }

    @Test
    void mainSubjectMorningUsesEachCoursesOwnCutoff() {
        ConstraintSettings settings = settings(ConstraintType.MAIN_SUBJECT_MORNING, true, 55,
                Map.of("courseId", COURSE_CHINESE, "beforePeriodNo", 4),
                Map.of("courseId", COURSE_MATH, "beforePeriodNo", 6));
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 5)
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 3)
                .lesson(PlanFixtures.CLASS_2, COURSE_MATH, TEACHER_2, ROOM_NORMAL, 1, 5)
                .lesson(PlanFixtures.CLASS_2, COURSE_MATH, TEACHER_2, ROOM_NORMAL, 1, 7)
                .build();

        List<String> rows = codes(violations(settings, snapshot, 1), "MAIN_SUBJECT_MORNING");
        assertThat(rows).hasSize(2);
        assertThat(rows).anyMatch(row -> row.startsWith("MAIN_SUBJECT_MORNING@1000"));
        assertThat(rows).anyMatch(row -> row.startsWith("MAIN_SUBJECT_MORNING@1003"));
    }

    @Test
    void consecutiveLessonsAppliesPerCourseEntry() {
        ConstraintSettings hard = settings(ConstraintType.CONSECUTIVE_LESSONS, true, 60,
                Map.of("courseId", COURSE_CHINESE, "minConsecutive", 2),
                Map.of("courseId", COURSE_MATH, "minConsecutive", 2));
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 3)
                .lesson(PlanFixtures.CLASS_2, COURSE_MATH, TEACHER_2, ROOM_NORMAL, 1, 1)
                .lesson(PlanFixtures.CLASS_2, COURSE_MATH, TEACHER_2, ROOM_NORMAL, 1, 2)
                .build();

        // Only the gapped Chinese pair complains; the adjacent maths pair is fine.
        assertThat(codes(violations(hard, snapshot, 1), "CONSECUTIVE_LESSONS")).hasSize(1);

        ConstraintSettings soft = settings(ConstraintType.CONSECUTIVE_LESSONS, false, 60,
                Map.of("courseId", COURSE_CHINESE, "minConsecutive", 2),
                Map.of("courseId", COURSE_MATH, "minConsecutive", 2));
        assertThat(ConstraintChecker.softPenalty(
                withSettings(snapshot, soft).context(), viewsOn(snapshot, 1)))
                .isEqualTo(60);
    }

    @Test
    void courseRequiredRoomTypePerCourseDoesNotInterfere() {
        ConstraintSettings settings = settings(ConstraintType.COURSE_REQUIRED_ROOM_TYPE, true, 100,
                Map.of("courseId", COURSE_CHINESE, "roomType", "LAB"),
                Map.of("courseId", COURSE_MATH, "roomType", "PLAYGROUND"));
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_LAB, 1, 2)
                .lesson(PlanFixtures.CLASS_2, COURSE_MATH, TEACHER_2, ROOM_PLAYGROUND, 1, 1)
                .lesson(PlanFixtures.CLASS_2, COURSE_MATH, TEACHER_2, ROOM_NORMAL, 1, 2)
                .build();

        List<String> rows = codes(violations(settings, snapshot, 1), "ROOM_TYPE");
        assertThat(rows).hasSize(2);
        assertThat(rows).anyMatch(row -> row.startsWith("ROOM_TYPE@1000"));
        assertThat(rows).anyMatch(row -> row.startsWith("ROOM_TYPE@1003"));
    }

    @Test
    void meetingBlockedIsTheUnionOfItsSubjectEntries() {
        ConstraintSettings settings = settings(ConstraintType.TEACHER_MEETING_BLOCKED, true, 100,
                Map.of("subject", "语文", "dayOfWeek", 1, "periodNo", 2),
                Map.of("dayOfWeek", 1, "periodNo", 3));
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 2)
                .lesson(PlanFixtures.CLASS_2, COURSE_MATH, TEACHER_2, ROOM_NORMAL, 1, 2)
                .lesson(PlanFixtures.CLASS_1, COURSE_MATH, TEACHER_2, ROOM_NORMAL, 1, 3)
                .build();

        // The blank-subject entry covers every subject at Mon-3; the 语文 entry only
        // covers Chinese teachers at Mon-2.
        List<String> rows = codes(violations(settings, snapshot, 1), "TEACHER_MEETING");
        assertThat(rows).hasSize(2);
        assertThat(rows).anyMatch(row -> row.startsWith("TEACHER_MEETING@1000"));
        assertThat(rows).anyMatch(row -> row.startsWith("TEACHER_MEETING@1002"));
    }

    @Test
    void entryBackedTypeWithOnlyLegacyParamsIsNotConfigured() {
        // A pre-V26 row that nothing migrated: `params_json` must not come back to
        // life, or an upgraded school would keep a rule the editor no longer shows.
        ConstraintSettings legacyParamsOnly = ConstraintSettings.from(
                List.of(config(ConstraintType.COURSE_NOT_IN_EVENING, true, 100,
                        Map.of("courseId", COURSE_CHINESE))), MAPPER);
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 8)
                .build();

        assertThat(violations(legacyParamsOnly, snapshot, 1)).isEmpty();

        // The threshold types still fall back to their legacy params (ticket 04 will
        // move them into the registry).
        ConstraintSettings threshold = ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_MAX_PER_DAY, true, 60,
                        Map.of("maxPerDay", 1))), MAPPER);
        PlanSnapshot twoLessons = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_CHINESE, TEACHER_1, ROOM_NORMAL, 1, 2)
                .build();
        assertThat(codes(violations(threshold, twoLessons, 1), "TEACHER_MAX_PER_DAY")).hasSize(1);
    }

    // ------------------------------------------------------------------ helpers

    private static PlanFixtures.Builder base() {
        return PlanFixtures.builder()
                .adminClass(PlanFixtures.CLASS_1, "高一1班", 45, null)
                .adminClass(PlanFixtures.CLASS_2, "高一2班", 45, null)
                .teacher(TEACHER_1, "张老师", "语文")
                .teacher(TEACHER_2, "李老师", "数学")
                .teacher(TEACHER_3, "王老师", "美术")
                .course(COURSE_CHINESE, "语文", null)
                .course(COURSE_MATH, "数学", null)
                .course(COURSE_PE, "体育", null)
                .course(COURSE_ART, "美术", null)
                .room(ROOM_NORMAL, "普通教室", "NORMAL", 50)
                .room(ROOM_LAB, "实验室", "LAB", 50)
                .room(ROOM_PLAYGROUND, "操场", "PLAYGROUND", 50);
    }

    private static List<ConstraintViolation> violations(
            ConstraintSettings settings, PlanSnapshot snapshot, int dayOfWeek) {
        return ConstraintChecker.checkHard(
                withSettings(snapshot, settings).context(), viewsOn(snapshot, dayOfWeek));
    }

    private static List<String> codes(List<ConstraintViolation> violations, String code) {
        List<String> rows = new ArrayList<>();
        for (ConstraintViolation violation : violations) {
            if (code.equals(violation.code())) {
                rows.add(code + "@" + violation.lessonId());
            }
        }
        return rows;
    }

    /** Rebuilds the snapshot with other settings: the fixture is immutable. */
    private static PlanSnapshot withSettings(PlanSnapshot snapshot, ConstraintSettings settings) {
        return PlanFixtures.builder()
                .settings(settings)
                .adminClass(PlanFixtures.CLASS_1, "高一1班", 45, null)
                .adminClass(PlanFixtures.CLASS_2, "高一2班", 45, null)
                .teacher(TEACHER_1, "张老师", "语文")
                .teacher(TEACHER_2, "李老师", "数学")
                .teacher(TEACHER_3, "王老师", "美术")
                .course(COURSE_CHINESE, "语文", null)
                .course(COURSE_MATH, "数学", null)
                .course(COURSE_PE, "体育", null)
                .course(COURSE_ART, "美术", null)
                .room(ROOM_NORMAL, "普通教室", "NORMAL", 50)
                .room(ROOM_LAB, "实验室", "LAB", 50)
                .room(ROOM_PLAYGROUND, "操场", "PLAYGROUND", 50)
                .replay(snapshot)
                .build();
    }

    private static List<LessonView> viewsOn(PlanSnapshot snapshot, int dayOfWeek) {
        return snapshot.scheduleOn(PlanFixtures.date(dayOfWeek)).lessons().stream()
                .map(lesson -> new LessonView(lesson.entryId(), lesson.classId(), lesson.courseId(),
                        lesson.teacherId(), lesson.roomId(), lesson.periodId(), lesson.studentCount()))
                .toList();
    }

    /** One enabled config with no params plus explicit entries (the new shape). */
    private static ConstraintSettings settings(
            ConstraintType type, boolean hard, int weight, Map<String, Object>... entries) {
        return ConstraintSettings.from(
                List.of(config(type, hard, weight, Map.of())),
                java.util.Arrays.stream(entries)
                        .map(params -> entry(type, params))
                        .toList(),
                MAPPER);
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
