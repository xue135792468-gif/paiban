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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The four core hard rules, exercised through the published-timetable seam. */
class ConstraintCheckerTest {

    private static final long TEACHER_1 = 11L;
    private static final long TEACHER_2 = 12L;
    private static final long ROOM_1 = 21L;
    private static final long ROOM_2 = 22L;
    private static final long COURSE_1 = 31L;
    private static final long COURSE_2 = 32L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void teacherCannotBeInTwoClassesAtTheSameTime() {
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_2, COURSE_2, TEACHER_1, ROOM_2, 1, 1)
                .build();

        List<ConstraintViolation> violations = ConstraintChecker.checkHard(
                snapshot.context(), viewsOn(snapshot, 1));

        assertThat(violations).extracting(ConstraintViolation::code).contains("TEACHER_CONFLICT");
    }

    @Test
    void classCannotHaveTwoCoursesInTheSameSlot() {
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_2, TEACHER_2, ROOM_2, 1, 1)
                .build();

        List<ConstraintViolation> violations = ConstraintChecker.checkHard(
                snapshot.context(), viewsOn(snapshot, 1));

        assertThat(violations).extracting(ConstraintViolation::code).contains("CLASS_CONFLICT");
    }

    @Test
    void roomCannotBeUsedByTwoClassesAtTheSameTime() {
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_2, COURSE_2, TEACHER_2, ROOM_1, 1, 1)
                .build();

        List<ConstraintViolation> violations = ConstraintChecker.checkHard(
                snapshot.context(), viewsOn(snapshot, 1));

        assertThat(violations).extracting(ConstraintViolation::code).contains("ROOM_CONFLICT");
    }

    @Test
    void courseRequiresTheConfiguredRoomType() {
        PlanSnapshot snapshot = PlanFixtures.builder()
                .settings(settings(
                        ConstraintType.TEACHER_CONFLICT,
                        ConstraintType.CLASS_CONFLICT,
                        ConstraintType.ROOM_CONFLICT,
                        ConstraintType.COURSE_REQUIRED_ROOM_TYPE))
                .adminClass(PlanFixtures.CLASS_1, "高一1班", 45, null)
                .teacher(TEACHER_1, "张老师", "化学")
                .course(COURSE_1, "化学实验", "LAB")
                .room(ROOM_1, "普通教室", "NORMAL", 50)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .build();

        List<ConstraintViolation> violations = ConstraintChecker.checkHard(
                snapshot.context(), viewsOn(snapshot, 1));

        assertThat(violations).extracting(ConstraintViolation::code).contains("ROOM_TYPE");
    }

    @Test
    void eveningMaxDoesNotCountDaytimeLessons() {
        ConstraintSettings eveningCap = settingsWith(
                ConstraintType.EVENING_MAX, true, 40, Map.of("maxPerDay", 0));
        // One morning and one afternoon lesson: neither belongs to the evening section.
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_2, TEACHER_2, ROOM_2, 1, 6)
                .build();

        assertThat(ConstraintChecker.checkHard(context(eveningCap, snapshot), viewsOn(snapshot, 1)))
                .extracting(ConstraintViolation::code)
                .doesNotContain("EVENING_MAX");
    }

    @Test
    void eveningMaxCountsEveningLessons() {
        ConstraintSettings eveningCap = settingsWith(
                ConstraintType.EVENING_MAX, true, 40, Map.of("maxPerDay", 1));
        PlanSnapshot snapshot = base().periodsPerDay(10)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 9)
                .lesson(PlanFixtures.CLASS_1, COURSE_2, TEACHER_2, ROOM_2, 1, 10)
                .build();

        assertThat(ConstraintChecker.checkHard(context(eveningCap, snapshot), viewsOn(snapshot, 1)))
                .extracting(ConstraintViolation::code)
                .contains("EVENING_MAX");
    }

    @Test
    void eveningMaxClassExceptionReplacesTheGlobalCap() {
        ConstraintSettings eveningCap = settingsWith(
                ConstraintType.EVENING_MAX, true, 40,
                Map.of("maxPerDay", 2),
                Map.of("classId", PlanFixtures.CLASS_2, "maxPerDay", 1));
        // Class 1 has two evening lessons, inside its global cap of 2; class 2 has two,
        // over its exception of 1. The exception must replace the global value.
        PlanSnapshot snapshot = base().periodsPerDay(10)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 9)
                .lesson(PlanFixtures.CLASS_1, COURSE_2, TEACHER_1, ROOM_1, 1, 10)
                .lesson(PlanFixtures.CLASS_2, COURSE_1, TEACHER_2, ROOM_2, 1, 9)
                .lesson(PlanFixtures.CLASS_2, COURSE_2, TEACHER_2, ROOM_2, 1, 10)
                .build();

        List<ConstraintViolation> violations = ConstraintChecker.checkHard(
                context(eveningCap, snapshot), viewsOn(snapshot, 1));
        assertThat(violations).extracting(ConstraintViolation::code).contains("EVENING_MAX");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("高一2班");
    }

    @Test
    void eveningMaxDisabledSilencesTheCap() {
        ConstraintSettings cancelled = ConstraintSettings.from(
                List.of(config(ConstraintType.EVENING_MAX, true, 40, false)),
                List.of(entry(ConstraintType.EVENING_MAX, Map.of("maxPerDay", 0))), MAPPER);
        PlanSnapshot snapshot = base().periodsPerDay(10)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 9)
                .build();

        assertThat(ConstraintChecker.checkHard(context(cancelled, snapshot), viewsOn(snapshot, 1)))
                .extracting(ConstraintViolation::code)
                .doesNotContain("EVENING_MAX");
    }

    @Test
    void eveningMaxSoftControlIsScoredNotBlocked() {
        ConstraintSettings softCap = settingsWith(
                ConstraintType.EVENING_MAX, false, 40, Map.of("maxPerDay", 0));
        PlanSnapshot daytime = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_2, TEACHER_2, ROOM_2, 1, 6)
                .build();
        PlanSnapshot withEvening = base().periodsPerDay(10)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 9)
                .build();

        assertThat(ConstraintChecker.softPenalty(context(softCap, daytime), viewsOn(daytime, 1)))
                .isZero();
        assertThat(ConstraintChecker.softPenalty(context(softCap, withEvening), viewsOn(withEvening, 1)))
                .isEqualTo(40);
    }

    private static PlanFixtures.Builder base() {
        return PlanFixtures.builder()
                .adminClass(PlanFixtures.CLASS_1, "高一1班", 45, null)
                .adminClass(PlanFixtures.CLASS_2, "高一2班", 45, null)
                .teacher(TEACHER_1, "张老师", "语文")
                .teacher(TEACHER_2, "李老师", "数学")
                .course(COURSE_1, "语文", null)
                .course(COURSE_2, "数学", null)
                .room(ROOM_1, "教室1", "NORMAL", 50)
                .room(ROOM_2, "教室2", "NORMAL", 50);
    }

    private static List<LessonView> viewsOn(PlanSnapshot snapshot, int dayOfWeek) {
        return snapshot.scheduleOn(PlanFixtures.date(dayOfWeek)).lessons().stream()
                .map(lesson -> new LessonView(lesson.entryId(), lesson.classId(), lesson.courseId(),
                        lesson.teacherId(), lesson.roomId(), lesson.periodId(), lesson.studentCount()))
                .toList();
    }

    /** The same reference data under a second rule set: settings live in the context. */
    private static ConstraintContext context(ConstraintSettings settings, PlanSnapshot snapshot) {
        return new ConstraintContext(snapshot.periods(), snapshot.rooms(), snapshot.courses(),
                snapshot.teachers(), snapshot.classes(), settings);
    }

    private static ConstraintConfig config(
            ConstraintType type, boolean hard, int weight, boolean enabled) {
        ConstraintConfig config = new ConstraintConfig();
        config.setType(type.name());
        config.setEnabled(enabled);
        config.setHard(hard);
        config.setWeight(weight);
        config.setParamsJson("{}");
        return config;
    }

    private static ConstraintEntry entry(ConstraintType type, Map<String, Object> params) {
        ConstraintEntry entry = new ConstraintEntry();
        entry.setType(type.name());
        entry.setCreatedAt(Instant.EPOCH);
        try {
            entry.setParamsJson(MAPPER.writeValueAsString(params));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return entry;
    }

    private static ConstraintSettings settings(ConstraintType... types) {
        List<ConstraintConfig> configs = Arrays.stream(types).map(type -> {
            ConstraintConfig config = new ConstraintConfig();
            config.setType(type.name());
            config.setEnabled(true);
            config.setHard(true);
            config.setWeight(type.getDefaultWeight());
            config.setParamsJson("{}");
            return config;
        }).toList();
        return ConstraintSettings.from(configs, MAPPER);
    }

    /**
     * One threshold type plus its explicitly stored entry rows. Entry-backed types read
     * their global value and per-object exceptions from these rows, not from the config.
     */
    private static ConstraintSettings settingsWith(
            ConstraintType type, boolean hard, int weight, Map<String, Object>... entries) {
        ConstraintConfig config = new ConstraintConfig();
        config.setType(type.name());
        config.setEnabled(true);
        config.setHard(hard);
        config.setWeight(weight);
        config.setParamsJson("{}");
        List<ConstraintEntry> rows = new ArrayList<>();
        for (Map<String, Object> params : entries) {
            ConstraintEntry entry = new ConstraintEntry();
            entry.setType(type.name());
            entry.setCreatedAt(Instant.EPOCH);
            try {
                entry.setParamsJson(MAPPER.writeValueAsString(params));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            rows.add(entry);
        }
        return ConstraintSettings.from(List.of(config), rows, MAPPER);
    }
}
