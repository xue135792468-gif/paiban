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
 * Multiple entries per type, evaluated through the shared rule catalogue. The
 * legacy single-params path must keep producing the same verdict as one explicit
 * entry, which is what keeps the other 23 types unchanged.
 */
class ConstraintEntriesCheckerTest {

    private static final long TEACHER_1 = 11L;
    private static final long TEACHER_2 = 12L;
    private static final long ROOM_1 = 21L;
    private static final long ROOM_2 = 22L;
    private static final long COURSE_1 = 31L;
    private static final long COURSE_2 = 32L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void twoTeachersEachKeepTheirOwnUnavailableSlot() {
        ConstraintSettings settings = settings(
                List.of(
                        config(ConstraintType.TEACHER_CONFLICT, true, 100, Map.of()),
                        config(ConstraintType.TEACHER_UNAVAILABLE, true, 100, Map.of())),
                List.of(
                        entry(ConstraintType.TEACHER_UNAVAILABLE, Map.of(
                                "teacherId", TEACHER_1, "dayOfWeek", 1, "periodNo", 1)),
                        entry(ConstraintType.TEACHER_UNAVAILABLE, Map.of(
                                "teacherId", TEACHER_2, "dayOfWeek", 1, "periodNo", 2))));

        // 1000: teacher1 at 1/1 (blocked), 1001: teacher2 at 1/2 (blocked),
        // 1002: teacher1 at 1/2 (allowed), 1003: teacher2 at 1/1 (allowed).
        PlanSnapshot snapshot = PlanFixtures.builder()
                .settings(settings)
                .adminClass(PlanFixtures.CLASS_1, "高一1班", 45, null)
                .adminClass(PlanFixtures.CLASS_2, "高一2班", 45, null)
                .teacher(TEACHER_1, "张老师", "语文")
                .teacher(TEACHER_2, "李老师", "数学")
                .course(COURSE_1, "语文", null)
                .course(COURSE_2, "数学", null)
                .room(ROOM_1, "教室1", "NORMAL", 50)
                .room(ROOM_2, "教室2", "NORMAL", 50)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_2, TEACHER_2, ROOM_2, 1, 2)
                .lesson(PlanFixtures.CLASS_2, COURSE_1, TEACHER_1, ROOM_1, 1, 2)
                .lesson(PlanFixtures.CLASS_2, COURSE_2, TEACHER_2, ROOM_2, 1, 1)
                .build();

        List<ConstraintViolation> violations = ConstraintChecker.checkHard(
                snapshot.context(), viewsOn(snapshot, 1));

        assertThat(unavailableLessonIds(violations)).containsExactlyInAnyOrder(1000L, 1001L);
    }

    @Test
    void twoSlotsOfTheSameTeacherAreBothBlocked() {
        ConstraintSettings settings = settings(
                config(ConstraintType.TEACHER_UNAVAILABLE, true, 100, Map.of()),
                entry(ConstraintType.TEACHER_UNAVAILABLE, Map.of(
                        "teacherId", TEACHER_1, "dayOfWeek", 1, "periodNo", 1)),
                entry(ConstraintType.TEACHER_UNAVAILABLE, Map.of(
                        "teacherId", TEACHER_1, "dayOfWeek", 1, "periodNo", 3)));

        PlanSnapshot snapshot = PlanFixtures.builder()
                .settings(settings)
                .adminClass(PlanFixtures.CLASS_1, "高一1班", 45, null)
                .teacher(TEACHER_1, "张老师", "语文")
                .course(COURSE_1, "语文", null)
                .room(ROOM_1, "教室1", "NORMAL", 50)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 3)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 2)
                .build();

        List<ConstraintViolation> violations = ConstraintChecker.checkHard(
                snapshot.context(), viewsOn(snapshot, 1));

        assertThat(unavailableLessonIds(violations)).containsExactlyInAnyOrder(1000L, 1001L);
    }

    @Test
    void anEntryForAnotherTeacherDoesNotBlockThisLesson() {
        ConstraintSettings settings = settings(
                config(ConstraintType.TEACHER_UNAVAILABLE, true, 100, Map.of()),
                entry(ConstraintType.TEACHER_UNAVAILABLE, Map.of(
                        "teacherId", TEACHER_2, "dayOfWeek", 1, "periodNo", 3)));

        PlanSnapshot snapshot = PlanFixtures.builder()
                .settings(settings)
                .adminClass(PlanFixtures.CLASS_1, "高一1班", 45, null)
                .teacher(TEACHER_1, "张老师", "语文")
                .course(COURSE_1, "语文", null)
                .room(ROOM_1, "教室1", "NORMAL", 50)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 3)
                .build();

        List<ConstraintViolation> violations = ConstraintChecker.checkHard(
                snapshot.context(), viewsOn(snapshot, 1));

        assertThat(violations).isEmpty();
    }

    @Test
    void legacySingleParamsAndOneExplicitEntryGiveTheSameVerdict() {
        ConstraintConfig legacy = config(ConstraintType.TEACHER_UNAVAILABLE, true, 100, Map.of(
                "teacherId", TEACHER_1, "dayOfWeek", 1, "periodNo", 1));
        ConstraintSettings legacySettings = ConstraintSettings.from(List.of(legacy), MAPPER);
        ConstraintSettings entriesSettings = settings(
                config(ConstraintType.TEACHER_UNAVAILABLE, true, 100, Map.of()),
                entry(ConstraintType.TEACHER_UNAVAILABLE, Map.of(
                        "teacherId", TEACHER_1, "dayOfWeek", 1, "periodNo", 1)));

        assertThat(verdict(legacySettings)).isEqualTo(verdict(entriesSettings));
        assertThat(verdict(legacySettings))
                .anyMatch(row -> row.startsWith("TEACHER_UNAVAILABLE@"));
    }

    @Test
    void courseNotInEveningKeepsItsLegacyMeaning() {
        ConstraintSettings legacy = ConstraintSettings.from(
                List.of(config(ConstraintType.COURSE_NOT_IN_EVENING, true, 100,
                        Map.of("courseId", COURSE_1))), MAPPER);
        ConstraintSettings entries = settings(
                config(ConstraintType.COURSE_NOT_IN_EVENING, true, 100, Map.of()),
                entry(ConstraintType.COURSE_NOT_IN_EVENING, Map.of("courseId", COURSE_1)));

        List<String> legacyVerdict = verdictForNightLesson(legacy);
        assertThat(legacyVerdict).hasSize(1);
        assertThat(legacyVerdict.get(0)).startsWith("COURSE_NOT_IN_EVENING@");
        assertThat(verdictForNightLesson(entries)).isEqualTo(legacyVerdict);
    }

    @Test
    void teacherMaxPerDayKeepsItsLegacyMeaning() {
        ConstraintSettings legacy = ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_MAX_PER_DAY, true, 60,
                        Map.of("maxPerDay", 1))), MAPPER);
        ConstraintSettings entries = settings(
                config(ConstraintType.TEACHER_MAX_PER_DAY, true, 60, Map.of()),
                entry(ConstraintType.TEACHER_MAX_PER_DAY, Map.of("maxPerDay", 1)));

        List<String> legacyVerdict = verdictForTwoLessonsADay(legacy);
        assertThat(legacyVerdict).hasSize(1);
        assertThat(legacyVerdict.get(0)).startsWith("TEACHER_MAX_PER_DAY@");
        assertThat(verdictForTwoLessonsADay(entries)).isEqualTo(legacyVerdict);
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> verdictForNightLesson(ConstraintSettings settings) {
        PlanSnapshot snapshot = PlanFixtures.builder()
                .settings(settings)
                .adminClass(PlanFixtures.CLASS_1, "高一1班", 45, null)
                .teacher(TEACHER_1, "张老师", "语文")
                .course(COURSE_1, "语文", null)
                .room(ROOM_1, "教室1", "NORMAL", 50)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 8)
                .build();
        return verdict(settings, snapshot);
    }

    private static List<String> verdictForTwoLessonsADay(ConstraintSettings settings) {
        PlanSnapshot snapshot = PlanFixtures.builder()
                .settings(settings)
                .adminClass(PlanFixtures.CLASS_1, "高一1班", 45, null)
                .teacher(TEACHER_1, "张老师", "语文")
                .course(COURSE_1, "语文", null)
                .room(ROOM_1, "教室1", "NORMAL", 50)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 2)
                .build();
        return verdict(settings, snapshot);
    }

    private static List<String> verdict(ConstraintSettings settings) {
        PlanSnapshot snapshot = PlanFixtures.builder()
                .settings(settings)
                .adminClass(PlanFixtures.CLASS_1, "高一1班", 45, null)
                .teacher(TEACHER_1, "张老师", "语文")
                .course(COURSE_1, "语文", null)
                .room(ROOM_1, "教室1", "NORMAL", 50)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .build();
        return verdict(settings, snapshot);
    }

    private static List<String> verdict(ConstraintSettings settings, PlanSnapshot snapshot) {
        return ConstraintChecker.check(snapshot.context(), viewsOn(snapshot, 1), null).stream()
                .map(violation -> violation.code() + "@" + violation.lessonId()
                        + ":" + violation.message())
                .sorted()
                .toList();
    }

    private static List<Long> unavailableLessonIds(List<ConstraintViolation> violations) {
        List<Long> ids = new ArrayList<>();
        violations.stream()
                .filter(violation -> "TEACHER_UNAVAILABLE".equals(violation.code()))
                .forEach(violation -> ids.add(violation.lessonId()));
        return ids;
    }

    private static List<LessonView> viewsOn(PlanSnapshot snapshot, int dayOfWeek) {
        return snapshot.scheduleOn(PlanFixtures.date(dayOfWeek)).lessons().stream()
                .map(lesson -> new LessonView(lesson.entryId(), lesson.classId(), lesson.courseId(),
                        lesson.teacherId(), lesson.roomId(), lesson.periodId(), lesson.studentCount()))
                .toList();
    }

    private static ConstraintSettings settings(ConstraintConfig config, ConstraintEntry... entries) {
        return settings(config, List.of(entries));
    }

    private static ConstraintSettings settings(ConstraintConfig config, List<ConstraintEntry> entries) {
        return settings(List.of(config), entries);
    }

    private static ConstraintSettings settings(List<ConstraintConfig> configs, List<ConstraintEntry> entries) {
        return ConstraintSettings.from(configs, entries, MAPPER);
    }

    /** One enabled config with no params, plus explicit entries (the new shape). */
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
        entry.setParamsJson(write(params));
        entry.setCreatedAt(Instant.EPOCH);
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
