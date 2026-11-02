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
import java.util.HashMap;
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

    /**
     * 票03：空堂软扣分按「真实存在的作息里、首末课程之间未占的每一格」累计。
     * 完整 1..8 节作息中，课在 1 与 3 空出第 2 节（40）；课在 1 与 5 空出第
     * 2、3、4 节（120）。旧实现按节次编号跨度推算，在作息缺号时会虚增空堂。
     */
    @Test
    void classGapSoftPenaltyCountsEveryRealEmptyPeriod() {
        ConstraintSettings soft = settingsWith(ConstraintType.CLASS_GAP_MINIMIZED, false, 40);
        PlanSnapshot oneGap = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_2, TEACHER_2, ROOM_2, 1, 3)
                .build();
        PlanSnapshot threeGaps = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_2, TEACHER_2, ROOM_2, 1, 5)
                .build();

        assertThat(ConstraintChecker.softPenalty(context(soft, oneGap), viewsOn(oneGap, 1)))
                .isEqualTo(40);
        assertThat(ConstraintChecker.softPenalty(context(soft, threeGaps), viewsOn(threeGaps, 1)))
                .isEqualTo(120);
    }

    /**
     * 票03：作息里不存在的节次编号不能凭空变成空堂。课在周一第 1、3 节，而真实
     * 作息只有这两节时，中间没有可用的第 2 节，硬软两侧都不得报出空堂；同一课表
     * 放回完整 1..8 节作息才构成违规。
     */
    @Test
    void classGapIgnoresPeriodNumbersThatHaveNoRealPeriod() {
        ConstraintSettings soft = settingsWith(ConstraintType.CLASS_GAP_MINIMIZED, false, 40);
        ConstraintSettings hard = settingsWith(ConstraintType.CLASS_GAP_MINIMIZED, true, 40);
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_2, TEACHER_2, ROOM_2, 1, 3)
                .build();

        assertThat(ConstraintChecker.softPenalty(
                contextWithoutPeriod(soft, snapshot, 1, 2), viewsOn(snapshot, 1)))
                .isZero();
        assertThat(ConstraintChecker.checkHard(
                contextWithoutPeriod(hard, snapshot, 1, 2), viewsOn(snapshot, 1)))
                .extracting(ConstraintViolation::code)
                .doesNotContain("CLASS_GAP_MINIMIZED");
        assertThat(ConstraintChecker.checkHard(context(hard, snapshot), viewsOn(snapshot, 1)))
                .extracting(ConstraintViolation::code)
                .contains("CLASS_GAP_MINIMIZED");
    }

    /** 票03：空堂数按班级/星期分别累计，逐格相加（4 格 × 40 = 160）。 */
    @Test
    void classGapSoftPenaltySumsAcrossClassesAndDays() {
        ConstraintSettings soft = settingsWith(ConstraintType.CLASS_GAP_MINIMIZED, false, 40);
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_2, TEACHER_2, ROOM_2, 1, 3)
                .lesson(PlanFixtures.CLASS_2, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_2, COURSE_2, TEACHER_2, ROOM_2, 1, 3)
                .lesson(PlanFixtures.CLASS_2, COURSE_1, TEACHER_1, ROOM_1, 1, 5)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 2, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_2, TEACHER_2, ROOM_2, 2, 3)
                .build();
        List<LessonView> views = new ArrayList<>(viewsOn(snapshot, 1));
        views.addAll(viewsOn(snapshot, 2));

        assertThat(ConstraintChecker.softPenalty(context(soft, snapshot), views)).isEqualTo(160);
    }

    /** 票03：当天首末课程之外的格子（边缘无课）不计空堂。 */
    @Test
    void classGapPenaltyIgnoresFreePeriodsOutsideTheFirstAndLastLesson() {
        ConstraintSettings soft = settingsWith(ConstraintType.CLASS_GAP_MINIMIZED, false, 40);
        // 周一第 3、5 节：只有第 4 节算内部空堂，第 1、2 节和 6..8 节都不计入。
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 3)
                .lesson(PlanFixtures.CLASS_1, COURSE_2, TEACHER_2, ROOM_2, 1, 5)
                .build();

        assertThat(ConstraintChecker.softPenalty(context(soft, snapshot), viewsOn(snapshot, 1)))
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

    /** 同一份参考数据，但作息里没有该天该节：验证真实存在的 PeriodView 集合才计数。 */
    private static ConstraintContext contextWithoutPeriod(
            ConstraintSettings settings, PlanSnapshot snapshot, int dayOfWeek, int periodNo) {
        Map<Long, ConstraintContext.PeriodView> periods = new HashMap<>(snapshot.periods());
        periods.remove(PlanFixtures.periodId(dayOfWeek, periodNo));
        return new ConstraintContext(periods, snapshot.rooms(), snapshot.courses(),
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
